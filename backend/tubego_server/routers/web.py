"""Laptop entry point. Browser sessions never register a media recipient."""
from datetime import datetime, timedelta, timezone
from pathlib import Path
import secrets
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import HTMLResponse
from pydantic import Field

from tubego_server.auth import require_approved, token_hash, utcnow, verify_password
from tubego_server.rate_limits import rate_limit
from tubego_server.routers.login import DUMMY_HASH
from tubego_server.routers.registration import EmailInput
from tubego_server.tasks import live_scope, task_value

router = APIRouter()


class BrowserLogin(EmailInput):
    password: str = Field(min_length=1, max_length=128)


@router.get('/send', response_class=HTMLResponse)
def send_page():
    nonce = secrets.token_urlsafe(24)
    html = (Path(__file__).resolve().parents[1] / 'web' / 'send.html').read_text()
    return HTMLResponse(html.replace('CSP_NONCE', nonce), headers={
        'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer',
        'X-Content-Type-Options': 'nosniff',
        'Content-Security-Policy': f"default-src 'none'; script-src 'nonce-{nonce}'; style-src 'nonce-{nonce}'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    })


@router.post('/api/v1/web/login')
def browser_login(body: BrowserLogin, request: Request):
    # Share the mobile login limiter; switching entry points cannot bypass it.
    rate_limit(request, 'login', body.email, 10, 15)
    with request.app.state.db.transaction() as conn:
        user = conn.execute('SELECT * FROM users WHERE email=?', (body.email,)).fetchone()
        valid = verify_password(body.password, user['password_hash'] if user else DUMMY_HASH)
        if not valid or user is None:
            raise HTTPException(401, 'Correo o contraseña incorrectos.')
        if user['status'] != 'approved' or not user['email_verified_at']:
            raise HTTPException(403, 'La cuenta debe estar verificada y aprobada.')
        now = datetime.now(timezone.utc)
        token = secrets.token_urlsafe(32)
        session_id = str(uuid.uuid4())
        expires = (now + timedelta(hours=8)).isoformat()
        conn.execute('INSERT INTO sessions VALUES (?,?,NULL,?,?,NULL,?)',
                     (session_id, user['id'], token_hash(token), expires, now.isoformat()))
        conn.execute("INSERT INTO audit(actor_user_id,action,target_id,created_at) VALUES (?,'web.session.created',?,?)",
                     (user['id'], session_id, now.isoformat()))
    return {'token': token, 'expires_at': expires}


@router.post('/api/v1/web/logout')
def browser_logout(request: Request, principal=Depends(require_approved)):
    if principal['device_id'] is not None:
        raise HTTPException(403, 'Browser session required')
    with request.app.state.db.transaction() as conn:
        conn.execute('UPDATE sessions SET revoked_at=? WHERE id=?', (utcnow(), principal['session_id']))
    return {'status': 'logged_out'}


@router.get('/api/v1/web/deliveries')
def browser_deliveries(request: Request, principal=Depends(require_approved)):
    with request.app.state.db.transaction() as conn:
        scope = live_scope(conn, principal)
        devices = conn.execute('SELECT id,name FROM devices WHERE user_id=? AND revoked_at IS NULL ORDER BY created_at', (scope.user_id,)).fetchall()
        tasks = conn.execute('''SELECT t.*,r.title,r.source_url FROM tasks t
            JOIN resources r ON r.id=t.resource_id AND r.user_id=t.user_id
            WHERE t.user_id=? ORDER BY t.created_at DESC,t.id LIMIT 50''', (scope.user_id,)).fetchall()
        activity = [task_value(conn, t) | {'title': t['title'], 'source_url': t['source_url']} for t in tasks]
        rows = conn.execute('''SELECT r.id,r.title,r.source_url,d.name AS device_name,l.status
            FROM resources r JOIN deliveries l ON l.resource_id=r.id
            JOIN devices d ON d.id=l.device_id AND d.user_id=r.user_id
            WHERE r.user_id=? AND d.revoked_at IS NULL
            ORDER BY r.created_at DESC,r.id,d.id LIMIT 100''', (scope.user_id,)).fetchall()
    return {'devices': [dict(d) for d in devices], 'items': [dict(r) for r in rows], 'tasks': activity}
