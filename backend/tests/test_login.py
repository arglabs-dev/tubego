from datetime import datetime,timezone,timedelta
import pytest
from fastapi.testclient import TestClient
from tubego_server.main import create_app
from tubego_server.config import Settings
from tubego_server.auth import hash_password,token_hash,utcnow

class Mail:
    def __init__(self): self.messages=[]
    def send_verification(self,email,token): self.messages.append(('verify',email,token))
    def send_reset(self,email,token): self.messages.append(('reset',email,token))

@pytest.fixture
def service(tmp_path):
    app=create_app(Settings(tmp_path));app.state.mailer=Mail()
    with TestClient(app) as client: yield app,client

def user(app,status='approved'):
    with app.state.db.transaction() as conn:
        now=utcnow()
        conn.execute('INSERT INTO users VALUES (?,?,?,\'user\',?,?,?,?)',('user','hello@example.com',hash_password('correct horse battery'),status,now if status!='pending_verification' else None,now,now))

def login(client,password='correct horse battery',email='HELLO@example.com'):
    return client.post('/api/v1/auth/login',json={'email':email,'password':password,'device_name':'Test phone'})

def headers(response): return {'Authorization':'Bearer '+response.json()['token']}

def test_login_multidevice_and_pending_status(service):
    app,client=service;user(app,'pending_approval')
    first=login(client);second=login(client)
    assert first.status_code==200 and first.json()['device_id']!=second.json()['device_id']
    assert first.headers['cache-control']=='no-store'
    assert first.json()['token']!=second.json()['token']
    assert client.get('/api/v1/account/status',headers=headers(first)).json()['status']=='pending_approval'
    assert client.get('/api/v1/admin/registrations',headers=headers(first)).status_code==403
    with app.state.db.transaction() as conn:
        hashes=[row[0] for row in conn.execute('SELECT token_hash FROM sessions')]
    assert token_hash(first.json()['token']) in hashes and first.json()['token'] not in hashes

def test_login_wrong_unknown_blocked(service):
    app,client=service;user(app,'blocked')
    assert login(client,password='incorrect').status_code==401
    assert login(client,email='unknown@example.com').status_code==401
    assert login(client).status_code==403
    with app.state.db.transaction() as conn: assert conn.execute('SELECT count(*) FROM sessions').fetchone()[0]==0

def test_rate_limits_persist(service):
    app,client=service;user(app)
    for _ in range(10): assert login(client,password='wrong').status_code==401
    assert login(client).status_code==429
    with app.state.db.transaction() as conn:
        assert conn.execute("SELECT attempts FROM auth_limits WHERE scope='login.email'").fetchone()[0]==11
        conn.execute("UPDATE auth_limits SET window_start='2000-01-01T00:00:00+00:00'")
    assert login(client).status_code==200

def test_reset_generic_one_use_and_revoke(service):
    app,client=service;user(app)
    first=login(client);second=login(client)
    unknown=client.post('/api/v1/auth/password/forgot',json={'email':'unknown@example.com'})
    known=client.post('/api/v1/auth/password/forgot',json={'email':'hello@example.com'})
    assert unknown.json()==known.json() and known.status_code==202
    token=app.state.mailer.messages[-1][2]
    body={'token':token,'password':'new correct password','revoke_other_sessions':True}
    assert client.post('/api/v1/auth/password/reset',json=body).status_code==200
    assert client.post('/api/v1/auth/password/reset',json=body).status_code==400
    assert login(client).status_code==401
    assert login(client,password=body['password']).status_code==200
    assert client.get('/api/v1/account/status',headers=headers(first)).json()['detail']['code']=='session_revoked'
    assert client.get('/api/v1/account/status',headers=headers(second)).status_code==401

def test_reset_expired_replaced_and_blocked(service):
    app,client=service;user(app)
    client.post('/api/v1/auth/password/forgot',json={'email':'hello@example.com'});old=app.state.mailer.messages[-1][2]
    with app.state.db.transaction() as conn: conn.execute("UPDATE password_reset_tokens SET expires_at='2000-01-01'")
    assert client.post('/api/v1/auth/password/reset',json={'token':old,'password':'new correct password'}).status_code==400
    client.post('/api/v1/auth/password/forgot',json={'email':'hello@example.com'});new=app.state.mailer.messages[-1][2]
    with app.state.db.transaction() as conn: conn.execute("UPDATE users SET status='blocked'")
    assert client.post('/api/v1/auth/password/reset',json={'token':new,'password':'new correct password'}).status_code==400

def test_change_password_preserves_current_revokes_others(service):
    app,client=service;user(app)
    first=login(client);second=login(client)
    response=client.post('/api/v1/account/password',headers=headers(first),json={'current_password':'correct horse battery','password':'new correct password','revoke_other_sessions':True})
    assert response.status_code==200
    assert client.get('/api/v1/account/status',headers=headers(first)).status_code==200
    assert client.get('/api/v1/account/status',headers=headers(second)).status_code==401

def test_reset_opt_out_and_expiration_code(service):
    app,client=service;user(app);session=login(client)
    client.post('/api/v1/auth/password/forgot',json={'email':'hello@example.com'})
    token=app.state.mailer.messages[-1][2]
    assert client.post('/api/v1/auth/password/reset',json={'token':token,'password':'new correct password','revoke_other_sessions':False}).status_code==200
    assert client.get('/api/v1/account/status',headers=headers(session)).status_code==200
    with app.state.db.transaction() as conn: conn.execute("UPDATE sessions SET expires_at='2000-01-01'")
    assert client.get('/api/v1/account/status',headers=headers(session)).json()['detail']['code']=='session_expired'

def test_reset_mail_failure_generic_and_no_token(service):
    app,client=service;user(app)
    def fail(email,token): raise OSError('SMTP failed')
    app.state.mailer.send_reset=fail
    assert client.post('/api/v1/auth/password/forgot',json={'email':'hello@example.com'}).status_code==202
    with app.state.db.transaction() as conn: assert conn.execute('SELECT count(*) FROM password_reset_tokens').fetchone()[0]==0

def test_login_rotates_existing_owned_device(service):
    app,client=service;user(app);first=login(client)
    second=client.post('/api/v1/auth/login',json={'email':'hello@example.com','password':'correct horse battery','device_name':'Renamed','device_id':first.json()['device_id']})
    assert second.status_code==200 and second.json()['device_id']==first.json()['device_id']
    assert client.get('/api/v1/account/status',headers=headers(first)).json()['detail']['code']=='session_revoked'
    assert client.get('/api/v1/account/status',headers=headers(second)).status_code==200
    with app.state.db.transaction() as conn: assert conn.execute('SELECT count(*) FROM devices').fetchone()[0]==1

def test_recovery_rate_limit_and_public_error_redaction(service):
    app,client=service;user(app)
    for _ in range(3): assert client.post('/api/v1/auth/password/forgot',json={'email':'hello@example.com'}).status_code==202
    assert client.post('/api/v1/auth/password/forgot',json={'email':'hello@example.com'}).status_code==429
    response=client.post('/api/v1/auth/password/reset',json={'token':'sensitive','password':'secret'})
    assert response.status_code==422 and 'sensitive' not in response.text and 'secret' not in response.text
    response=client.get('/api/v1/auth/password/reset')
    assert response.status_code==200 and 'history.replaceState' in response.text
    assert response.headers['cache-control']=='no-store'

def test_session_device_owner_mismatch_denied(service):
    app,client=service;user(app);session=login(client)
    with app.state.db.transaction() as conn:
        now=utcnow()
        conn.execute("INSERT INTO users VALUES ('other','other@example.com','unused','user','approved',?,?,?)",(now,now,now))
        conn.execute("UPDATE devices SET user_id='other' WHERE id=?",(session.json()['device_id'],))
    assert client.get('/api/v1/account/status',headers=headers(session)).status_code==401
