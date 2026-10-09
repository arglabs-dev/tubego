"""Device sessions and password recovery. Tokens are never persisted in cleartext."""
from datetime import datetime, timezone, timedelta
import secrets
import uuid
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field
from tubego_server.auth import get_principal, hash_password, verify_password, token_hash, utcnow
from tubego_server.routers.registration import EmailInput
from tubego_server.rate_limits import rate_limit

router = APIRouter()
DUMMY_HASH = hash_password(secrets.token_urlsafe(32))

class Login(EmailInput):
    password: str = Field(min_length=1, max_length=128)
    device_name: str = Field(min_length=1, max_length=100)
    device_id: str | None = Field(default=None, min_length=1, max_length=100)
    replace_device: bool = False
    # A login starts a new device session. A currently authenticated device may
    # rotate its session through a future session-management card.

class PasswordReset(BaseModel):
    token: str = Field(min_length=20, max_length=256)
    password: str = Field(min_length=12, max_length=128)
    revoke_other_sessions: bool = True

class PasswordChange(BaseModel):
    current_password: str = Field(min_length=1, max_length=128)
    password: str = Field(min_length=12, max_length=128)
    revoke_other_sessions: bool = True


@router.post('/auth/login')
def login(body: Login, request: Request):
    rate_limit(request,'login',body.email,10,15)
    with request.app.state.db.transaction() as conn:
        user=conn.execute('SELECT * FROM users WHERE email=?',(body.email,)).fetchone()
        valid=verify_password(body.password,user['password_hash'] if user else DUMMY_HASH)
        if not valid or user is None:
            raise HTTPException(401,'Invalid email or password')
        if user['status'] in ('blocked','rejected','deleted'):
            raise HTTPException(403,{'code':'account_unavailable'})
        now=datetime.now(timezone.utc); stamp=now.isoformat(); device=str(uuid.uuid4())
        token=secrets.token_urlsafe(32); expires=(now+timedelta(days=30)).isoformat()
        previous = conn.execute("SELECT * FROM devices WHERE id=? AND user_id=?", (body.device_id,user['id'])).fetchone() if body.device_id else None
        if previous and body.replace_device:
            from tubego_server.routers.devices import revoke_device
            revoke_device(conn,user['id'],previous['id'],user['id'])
            previous=conn.execute("SELECT * FROM devices WHERE id=?",(previous['id'],)).fetchone()
        if previous and previous['revoked_at'] is None:
            device=previous['id']
            conn.execute("UPDATE devices SET name=?,last_seen_at=? WHERE id=?", (body.device_name,stamp,device))
            conn.execute("UPDATE sessions SET revoked_at=? WHERE device_id=? AND revoked_at IS NULL",(stamp,device))
        else:
            conn.execute("INSERT INTO devices(id,user_id,name,platform,last_seen_at,created_at) VALUES (?,?,?,'android',?,?)",(device,user['id'],body.device_name,stamp,stamp))
        # Only a known owned device being signed back in restores older available history.
        # A genuinely new device receives future publications, not the full old library.
        if previous:
            conn.execute("""INSERT OR IGNORE INTO deliveries(resource_id,device_id,status,updated_at)
                SELECT r.id,?,'pending',? FROM resources r WHERE r.user_id=? AND r.ready_at IS NOT NULL
                AND r.server_deleted_at IS NULL AND r.server_path IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM deliveries previous WHERE previous.resource_id=r.id AND previous.deleted_at IS NOT NULL)""",(device,stamp,user['id']))
        conn.execute('INSERT INTO sessions VALUES (?,?,?,?,?,NULL,?)',(str(uuid.uuid4()),user['id'],device,token_hash(token),expires,stamp))
        conn.execute("INSERT INTO audit(actor_user_id,action,target_id,created_at) VALUES (?,'session.created',?,?)",(user['id'],device,stamp))
    return {'email':user['email'],'user_id':user['id'],'token':token,'expires_at':expires,'device_id':device,'status':user['status'],'role':user['role']}

@router.post('/auth/password/forgot',status_code=202)
def forgot(body: EmailInput,request: Request):
    rate_limit(request,'reset',body.email,3,60)
    # Public responses remain generic even when account-specific SMTP fails.
    with request.app.state.db.transaction() as conn:
        user=conn.execute('SELECT * FROM users WHERE email=?',(body.email,)).fetchone()
        if user and user['status'] not in ('blocked','rejected','deleted'):
            token=secrets.token_urlsafe(32); now=datetime.now(timezone.utc)
            try: request.app.state.mailer.send_reset(user['email'],token)
            except (OSError,RuntimeError): return {'message':'Si la cuenta permite recuperación, recibirás un correo.'}
            conn.execute('UPDATE password_reset_tokens SET used_at=? WHERE user_id=? AND used_at IS NULL',(now.isoformat(),user['id']))
            conn.execute('INSERT INTO password_reset_tokens VALUES (?,?,?,?,?)',(token_hash(token),user['id'],(now+timedelta(hours=1)).isoformat(),None,now.isoformat()))
    return {'message':'Si la cuenta permite recuperación, recibirás un correo.'}

@router.get('/auth/password/reset',response_class=HTMLResponse)
def reset_page():
    nonce=secrets.token_urlsafe(24)
    html="""<html lang='es'><meta name='referrer' content='no-referrer'><h1>Nueva contraseña</h1>
    <input id='password' type='password' minlength='12' maxlength='128' autocomplete='new-password' placeholder='12–128 caracteres'>
    <label><input id='revoke' type='checkbox' checked>Cerrar todas las sesiones</label>
    <button id='submit'>Cambiar contraseña</button><p id='status'></p><script nonce='NONCE'>
    const token=new URLSearchParams(location.hash.slice(1)).get('token'); history.replaceState(null,'',location.pathname);
    document.getElementById('submit').onclick=async()=>{
      const button=document.getElementById('submit'); button.disabled=true;
      try {
        const response=await fetch('/api/v1/auth/password/reset',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token,password:document.getElementById('password').value,revoke_other_sessions:document.getElementById('revoke').checked})});
        document.getElementById('status').textContent=response.ok?'Contraseña actualizada. Inicia sesión desde Tubego.':'No se pudo cambiar. Revisa el enlace y la longitud de la contraseña.';
        if(!response.ok) button.disabled=false;
        document.getElementById('password').value='';
      } catch(e) {document.getElementById('status').textContent='Sin conexión. Inténtalo otra vez.';button.disabled=false;}
    };</script></html>""".replace('NONCE',nonce)
    return HTMLResponse(html,headers={'Cache-Control':'no-store','Referrer-Policy':'no-referrer','Content-Security-Policy':f"default-src 'none'; script-src 'nonce-{nonce}'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'"})

@router.post('/auth/password/reset')
def reset(body: PasswordReset,request: Request):
    # Limit even random invalid tokens before expensive password hashing.
    rate_limit(request,'reset.complete',token_hash(body.token),10,15)
    digest=hash_password(body.password); now=utcnow()
    with request.app.state.db.transaction() as conn:
        row=conn.execute('SELECT t.*,u.status FROM password_reset_tokens t JOIN users u ON u.id=t.user_id WHERE t.token_hash=?',(token_hash(body.token),)).fetchone()
        if row is None or row['used_at'] or row['expires_at']<=now or row['status'] in ('blocked','rejected','deleted'):
            raise HTTPException(400,'Invalid or expired reset link')
        conn.execute('UPDATE password_reset_tokens SET used_at=? WHERE token_hash=?',(now,row['token_hash']))
        conn.execute('UPDATE users SET password_hash=?,updated_at=? WHERE id=?',(digest,now,row['user_id']))
        if body.revoke_other_sessions:
            conn.execute('UPDATE sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL',(now,row['user_id']))
        conn.execute("INSERT INTO audit(actor_user_id,action,target_id,created_at) VALUES (?,'password.reset',?,?)",(row['user_id'],row['user_id'],now))
    return {'message':'Password updated'}

@router.post('/account/password')
def change(body: PasswordChange,request: Request,principal=Depends(get_principal)):
    rate_limit(request,'password.change',principal['email'],10,15)
    digest=hash_password(body.password);now=utcnow()
    with request.app.state.db.transaction() as conn:
        user=conn.execute('SELECT * FROM users WHERE id=?',(principal['id'],)).fetchone()
        if not verify_password(body.current_password,user['password_hash']):
            raise HTTPException(401,'Invalid password')
        conn.execute('UPDATE users SET password_hash=?,updated_at=? WHERE id=?',(digest,now,principal['id']))
        conn.execute('UPDATE password_reset_tokens SET used_at=? WHERE user_id=? AND used_at IS NULL',(now,principal['id']))
        if body.revoke_other_sessions:
            conn.execute('UPDATE sessions SET revoked_at=? WHERE user_id=? AND id<>? AND revoked_at IS NULL',(now,principal['id'],principal['session_id']))
        conn.execute("INSERT INTO audit(actor_user_id,action,target_id,created_at) VALUES (?,'password.changed',?,?)",(principal['id'],principal['id'],now))
    return {'message':'Password updated'}
