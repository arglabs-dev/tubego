from datetime import datetime, timezone, timedelta
import re
import secrets
import uuid
from fastapi import APIRouter, Depends, HTTPException, Request, Query
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field, field_validator
from tubego_server.auth import get_principal, require_admin, hash_password, token_hash, utcnow
from tubego_server.rate_limits import rate_limit

router = APIRouter()
GENERIC = {"message": "Si la cuenta necesita verificación, recibirás un correo. Después requiere aprobación administrativa."}

class EmailInput(BaseModel):
    email: str = Field(max_length=254)

    @field_validator("email")
    @classmethod
    def email_valid(cls, value):
        value = value.strip().lower()
        if not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", value) or any(ord(c)<32 for c in value):
            raise ValueError("Correo inválido")
        return value

class Registration(EmailInput):
    password: str = Field(min_length=12, max_length=128)


def issue_verification(conn, request, user):
    now = datetime.now(timezone.utc)
    recent = conn.execute("SELECT created_at FROM verification_tokens WHERE user_id=? ORDER BY created_at DESC LIMIT 1", (user["id"],)).fetchone()
    if recent and datetime.fromisoformat(recent[0]) > now - timedelta(minutes=1):
        return
    token = secrets.token_urlsafe(32)
    # SMTP before commit: failure rolls back registration/token; never log token or SMTP details.
    request.app.state.mailer.send_verification(user["email"], token)
    conn.execute("UPDATE verification_tokens SET used_at=? WHERE user_id=? AND used_at IS NULL", (now.isoformat(), user["id"]))
    conn.execute("INSERT INTO verification_tokens VALUES (?,?,?,?,?)", (token_hash(token), user["id"], (now+timedelta(hours=1)).isoformat(), None, now.isoformat()))

@router.post("/auth/register", status_code=202)
def register(body: Registration, request: Request):
    rate_limit(request, "registration", body.email, 5, 60)
    digest = hash_password(body.password)  # Match expensive work for existing accounts.
    try:
        with request.app.state.db.transaction() as conn:
            user = conn.execute("SELECT * FROM users WHERE email=?", (body.email,)).fetchone()
            if user is None:
                now = utcnow()
                conn.execute("INSERT INTO users(id,email,password_hash,created_at,updated_at) VALUES (?,?,?,?,?)", (str(uuid.uuid4()), body.email, digest, now, now))
                user = conn.execute("SELECT * FROM users WHERE email=?", (body.email,)).fetchone()
            if user["status"] == "pending_verification":
                issue_verification(conn, request, user)
    except (OSError, RuntimeError):
        return GENERIC
    return GENERIC

@router.post("/auth/verification/resend", status_code=202)
def resend(body: EmailInput, request: Request):
    rate_limit(request, "verification.resend", body.email, 5, 60)
    try:
        with request.app.state.db.transaction() as conn:
            user = conn.execute("SELECT * FROM users WHERE email=?", (body.email,)).fetchone()
            if user and user["status"] == "pending_verification":
                issue_verification(conn, request, user)
    except (OSError, RuntimeError):
        return GENERIC
    return GENERIC

class Verification(BaseModel):
    token: str = Field(min_length=20, max_length=256)

@router.get("/auth/verify", response_class=HTMLResponse)
def verify_page():
    # Fragment tokens never reach proxy/access logs. User interaction prevents mail scanners consuming links.
    nonce = secrets.token_urlsafe(24)
    html = """<html lang='es'><meta name='referrer' content='no-referrer'><h1>Verificar correo</h1>
    <button id='verify'>Confirmar verificación</button><p id='status'></p><script nonce='NONCE'>
    const token = new URLSearchParams(location.hash.slice(1)).get('token');
    history.replaceState(null, '', location.pathname);
    document.getElementById('verify').onclick = async () => {
      const button=document.getElementById('verify'); button.disabled=true;
      try {
        const response=await fetch('/api/v1/auth/verify', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({token})});
        document.getElementById('status').textContent=response.ok ? 'Correo verificado. Espera aprobación del administrador.' : 'Enlace inválido o vencido. Solicita otro desde la app.';
      } catch(e) { document.getElementById('status').textContent='Sin conexión. Inténtalo nuevamente.'; button.disabled=false; }
    };
    </script></html>""".replace('NONCE',nonce)
    return HTMLResponse(html, headers={"Cache-Control":"no-store", "Referrer-Policy":"no-referrer", "Content-Security-Policy":f"default-src 'none'; script-src 'nonce-{nonce}'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'"})

@router.post("/auth/verify")
def verify(request: Request, body: Verification):
    now = utcnow()
    with request.app.state.db.transaction() as conn:
        row = conn.execute("SELECT * FROM verification_tokens WHERE token_hash=?", (token_hash(body.token),)).fetchone()
        if row is None or row["used_at"] or row["expires_at"] <= now:
            raise HTTPException(400, "Invalid or expired verification link")
        conn.execute("UPDATE verification_tokens SET used_at=? WHERE token_hash=?", (now, row["token_hash"]))
        changed=conn.execute("UPDATE users SET status='pending_approval',email_verified_at=?,updated_at=? WHERE id=? AND status='pending_verification'", (now,now,row["user_id"]))
        if changed.rowcount:
            from tubego_server.alerts import notify_admins
            notify_admins(conn,'registration_pending',{'user_id':row['user_id']},'registration:'+row['user_id'])
    return {"status":"pending_approval"}

@router.get("/account/status")
def account_status(principal=Depends(get_principal)):
    return {"email": principal["email"], "status": principal["status"], "role": principal["role"]}

@router.get("/admin/registrations")
def pending(request: Request, admin=Depends(require_admin)):
    with request.app.state.db.transaction() as conn:
        return [dict(row) for row in conn.execute("SELECT id,email,status,created_at FROM users WHERE status='pending_approval' AND email_verified_at IS NOT NULL ORDER BY created_at")]

class Decision(BaseModel):
    approve: bool

@router.post("/admin/registrations/{user_id}/decision")
def decide(user_id: str, body: Decision, request: Request, admin=Depends(require_admin)):
    with request.app.state.db.transaction() as conn:
        status = "approved" if body.approve else "rejected"
        cursor = conn.execute("UPDATE users SET status=?,updated_at=? WHERE id=? AND status='pending_approval' AND email_verified_at IS NOT NULL", (status,utcnow(),user_id))
        if cursor.rowcount != 1:
            raise HTTPException(409, "Account is not awaiting approval")
        conn.execute("INSERT INTO audit(actor_user_id,action,target_id,created_at) VALUES (?,?,?,?)", (admin["id"],"registration."+status,user_id,utcnow()))
    return {"status": status}
