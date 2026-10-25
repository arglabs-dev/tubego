from datetime import datetime,timezone,timedelta
import uuid
import pytest
from fastapi.testclient import TestClient
from tubego_server.main import create_app
from tubego_server.config import Settings
from tubego_server.auth import hash_password, verify_password, token_hash, utcnow, require_approved

class FakeMail:
    def __init__(self): self.messages=[]
    def send_verification(self,email,token): self.messages.append((email,token))

@pytest.fixture
def service(tmp_path):
    app=create_app(Settings(tmp_path)); app.state.mailer=FakeMail()
    @app.get('/protected')
    def protected(request: __import__('fastapi').Request):
        return {'user': require_approved(request)['id']}
    with TestClient(app) as client: yield app,client

def seed(app,status='approved',role='user',verified=True):
    user=str(uuid.uuid4()); token=str(uuid.uuid4()); now=utcnow()
    with app.state.db.transaction() as conn:
        conn.execute('INSERT INTO users VALUES (?,?,?,?,?,?,?,?)',(user,user+'@example.com',hash_password('a secure password'),role,status,now if verified else None,now,now))
        conn.execute('INSERT INTO sessions VALUES (?,?,NULL,?,?,NULL,?)',(str(uuid.uuid4()),user,token_hash(token),(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat(),now))
    return user,{'Authorization':'Bearer '+token}

def test_register_verify_approve(service):
    app,client=service
    body={'email':'  HELLO@Example.COM ','password':'correct horse battery'}
    response=client.post('/api/v1/auth/register',json=body)
    assert response.status_code==202 and 'token' not in response.text
    assert app.state.mailer.messages[0][0]=='hello@example.com'
    token=app.state.mailer.messages[0][1]
    assert client.post('/api/v1/auth/register',json=body).json()==response.json()
    assert client.post('/api/v1/auth/verification/resend',json={'email':'hello@example.com'}).status_code==202
    assert len(app.state.mailer.messages)==1
    _,admin=seed(app,role='admin')
    assert client.get('/api/v1/admin/registrations',headers=admin).json()==[]
    assert client.post('/api/v1/auth/verify',json={'token':token}).json()=={'status':'pending_approval'}
    assert client.post('/api/v1/auth/verify',json={'token':token}).status_code==400
    pending=client.get('/api/v1/admin/registrations',headers=admin).json()
    assert len(pending)==1
    uid=pending[0]['id']
    assert client.post(f'/api/v1/admin/registrations/{uid}/decision',json={'approve':True},headers=admin).json()=={'status':'approved'}
    with app.state.db.transaction() as conn:
        assert conn.execute('SELECT count(*) FROM audit').fetchone()[0]==1
        encoded=conn.execute('SELECT password_hash FROM users WHERE id=?',(uid,)).fetchone()[0]
    assert verify_password(body['password'],encoded) and not verify_password('wrong',encoded)

def test_expired_resend_and_invalid_inputs(service):
    app,client=service
    assert client.post('/api/v1/auth/register',json={'email':'bad','password':'short'}).status_code==422
    body={'email':'test@example.com','password':'correct horse battery'}
    client.post('/api/v1/auth/register',json=body)
    token=app.state.mailer.messages[-1][1]
    with app.state.db.transaction() as conn:
        conn.execute("UPDATE verification_tokens SET expires_at='2000-01-01', created_at='2000-01-01T00:00:00+00:00'")
    assert client.post('/api/v1/auth/verify',json={'token':token}).status_code==400
    client.post('/api/v1/auth/verification/resend',json={'email':body['email']})
    assert len(app.state.mailer.messages)==2
    assert client.post('/api/v1/auth/verify',json={'token':app.state.mailer.messages[-1][1]}).status_code==200

@pytest.mark.parametrize('status,verified,expected',[('approved',True,200),('pending_verification',False,403),('pending_approval',True,403),('blocked',True,403),('rejected',True,403)])
def test_service_gate(service,status,verified,expected):
    app,client=service; _,headers=seed(app,status=status,verified=verified)
    assert client.get('/protected',headers=headers).status_code==expected
    assert client.get('/api/v1/admin/registrations',headers=headers).status_code==403
    assert client.get('/api/v1/account/status',headers=headers).status_code==(403 if status in ('blocked','rejected') else 200)

def test_reject_authorization_and_missing_session(service):
    app,client=service
    user,_=seed(app,status='pending_approval'); _,admin=seed(app,role='admin')
    assert client.post(f'/api/v1/admin/registrations/{user}/decision',json={'approve':False}).status_code==401
    assert client.post(f'/api/v1/admin/registrations/{user}/decision',json={'approve':False},headers=admin).json()=={'status':'rejected'}
    assert client.post(f'/api/v1/admin/registrations/{user}/decision',json={'approve':True},headers=admin).status_code==409

def test_mail_failure_rolls_back(service):
    app,client=service
    def fail(email,token): raise OSError('smtp unavailable')
    app.state.mailer.send_verification=fail
    assert client.post('/api/v1/auth/register',json={'email':'new@example.com','password':'correct horse battery'}).status_code==202
    with app.state.db.transaction() as conn:
        assert conn.execute('SELECT count(*) FROM users').fetchone()[0]==0

def test_revoked_expired_sessions(service):
    app,client=service
    _,headers=seed(app)
    with app.state.db.transaction() as conn: conn.execute("UPDATE sessions SET revoked_at=?",(utcnow(),))
    assert client.get('/api/v1/account/status',headers=headers).status_code==401
    with app.state.db.transaction() as conn: conn.execute("UPDATE sessions SET revoked_at=NULL,expires_at='2000-01-01'")
    assert client.get('/api/v1/account/status',headers=headers).status_code==401

def test_verify_page_has_no_query_token(service):
    app,client=service
    page=client.get('/api/v1/auth/verify')
    assert page.status_code==200
    assert page.headers['cache-control']=='no-store'
    assert 'history.replaceState' in page.text
    assert 'frame-ancestors' in page.headers['content-security-policy']

def test_validation_never_echoes_secrets(service):
    app,client=service
    response=client.post('/api/v1/auth/register',json={'email':'test@example.com','password':'secret'})
    assert response.status_code==422 and 'secret' not in response.text
    response=client.post('/api/v1/auth/verify',json={'token':'private-token'})
    assert response.status_code==422 and 'private-token' not in response.text

def test_version_one_migrates_without_losing_users(tmp_path):
    import sqlite3
    from tubego_server.db import SCHEMA,Database,SCHEMA_VERSION
    path=tmp_path/'previous.sqlite3'
    with sqlite3.connect(path) as conn:
        for statement in SCHEMA: conn.execute(statement)
        conn.execute('PRAGMA user_version=1')
        conn.execute("INSERT INTO users VALUES ('u','test@example.com','hash','user','pending_verification',NULL,'now','now')")
    db=Database(path); db.initialize(); db.initialize()
    with db.transaction() as conn:
        assert conn.execute('SELECT email FROM users').fetchone()[0]=='test@example.com'
        assert conn.execute('PRAGMA user_version').fetchone()[0]==SCHEMA_VERSION
        assert conn.execute('SELECT count(*) FROM verification_tokens').fetchone()[0]==0
