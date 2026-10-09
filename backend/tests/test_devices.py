import pytest
from fastapi.testclient import TestClient
from tubego_server.main import create_app
from tubego_server.config import Settings
from tubego_server.auth import hash_password,utcnow

@pytest.fixture
def service(tmp_path):
    app=create_app(Settings(tmp_path))
    with TestClient(app) as client:
        with app.state.db.transaction() as conn:
            now=utcnow()
            for identity in ['a','b']:
                conn.execute('INSERT INTO users VALUES (?,?,?,\'user\',\'approved\',?,?,?)',(identity,identity+'@example.com',hash_password('correct horse battery'),now,now,now))
        yield app,client

def login(client,email='a@example.com'):
    response=client.post('/api/v1/auth/login',json={'email':email,'password':'correct horse battery','device_name':'Phone'})
    assert response.status_code==200
    return response.json()

def headers(session):return {'Authorization':'Bearer '+session['token']}

def test_devices_current_ownership_and_revocation(service):
    app,client=service;first=login(client);second=login(client);other=login(client,'b@example.com')
    rows=client.get('/api/v1/account/devices',headers=headers(first)).json()
    assert len(rows)==2 and sum(row['current'] for row in rows)==1
    assert client.post('/api/v1/account/devices/'+other['device_id']+'/revoke',headers=headers(first)).status_code==404
    assert client.post('/api/v1/account/devices/'+second['device_id']+'/revoke',headers=headers(first)).json()['wipe_local'] is False
    assert client.get('/api/v1/account/status',headers=headers(first)).status_code==200
    remote=client.get('/api/v1/account/status',headers=headers(second))
    assert remote.status_code==401 and remote.json()['detail']['wipe_local'] is True
    with app.state.db.transaction() as conn:
        assert conn.execute("SELECT count(*) FROM events WHERE kind='device.wipe'").fetchone()[0]==1
        assert conn.execute('SELECT revoked_at FROM devices WHERE id=?',(second['device_id'],)).fetchone()[0] is not None
    # Repeated remote revocation is idempotent.
    assert client.post('/api/v1/account/devices/'+second['device_id']+'/revoke',headers=headers(first)).status_code==200
    assert client.get('/api/v1/account/status',headers=headers(other)).status_code==200

def test_offline_logout_expired_session_and_other_devices_preserved(service):
    app,client=service;first=login(client);second=login(client)
    with app.state.db.transaction() as conn:conn.execute("UPDATE sessions SET expires_at='2000-01-01' WHERE device_id=?",(first['device_id'],))
    assert client.post('/api/v1/account/session/logout',headers=headers(first)).status_code==200
    assert client.post('/api/v1/account/session/logout',headers=headers(first)).status_code==200
    assert client.get('/api/v1/account/status',headers=headers(first)).json()['detail']['code']=='session_revoked'
    assert client.get('/api/v1/account/status',headers=headers(second)).status_code==200
    with app.state.db.transaction() as conn:
        assert conn.execute("SELECT count(*) FROM devices WHERE revoked_at IS NULL").fetchone()[0]==1
        assert conn.execute('SELECT count(*) FROM audit WHERE action=\'device.revoked\'').fetchone()[0]==1

def test_logout_invalid_and_cross_owner(service):
    app,client=service;session=login(client)
    assert client.post('/api/v1/account/session/logout').status_code==401
    assert client.post('/api/v1/account/session/logout',headers={'Authorization':'Bearer random'}).status_code==401
    with app.state.db.transaction() as conn:conn.execute("UPDATE devices SET user_id='b' WHERE id=?",(session['device_id'],))
    assert client.post('/api/v1/account/session/logout',headers=headers(session)).status_code==401

def test_login_restores_history_except_deliberately_deleted(service):
    app,client=service;previous=login(client)
    with app.state.db.transaction() as conn:
        now=utcnow()
        for key in ['ready','deleted','unavailable']:
            conn.execute('INSERT INTO resources(id,user_id,source_url,server_path,ready_at,server_deleted_at,created_at,updated_at) VALUES (?,\'a\',\'https://example.com\',\'media/file\',?,?,?,?)',(key,now,now if key=='unavailable' else None,now,now))
        conn.execute('INSERT INTO deliveries(resource_id,device_id,status,deleted_at,updated_at) VALUES (?,?,\'deleted\',?,?)',('deleted',previous['device_id'],now,now))
    fresh=login(client)
    with app.state.db.transaction() as conn:
        assert conn.execute('SELECT count(*) FROM deliveries WHERE device_id=?',(fresh['device_id'],)).fetchone()[0]==0
    current=client.post('/api/v1/auth/login',json={'email':'a@example.com','password':'correct horse battery','device_name':'Phone','device_id':previous['device_id']}).json()
    with app.state.db.transaction() as conn:
        records=conn.execute("SELECT resource_id FROM deliveries WHERE device_id=? AND status='pending'",(current['device_id'],)).fetchall()
        assert conn.execute("SELECT deleted_at FROM deliveries WHERE resource_id='deleted' AND device_id=?",(previous['device_id'],)).fetchone()[0] is not None
    assert [row[0] for row in records]==['ready']
    assert client.post('/api/v1/account/session/logout',headers=headers(current)).status_code==200
    again=client.post('/api/v1/auth/login',json={'email':'a@example.com','password':'correct horse battery','device_name':'Phone','device_id':current['device_id']}).json()
    with app.state.db.transaction() as conn:
        assert conn.execute('SELECT resource_id FROM deliveries WHERE device_id=?',(again['device_id'],)).fetchone()[0]=='ready'

def test_login_after_pending_offline_logout_cannot_revoke_new_session(service):
    app,client=service;old=login(client)
    replacement=client.post('/api/v1/auth/login',json={'email':'a@example.com','password':'correct horse battery','device_name':'Phone','device_id':old['device_id'],'replace_device':True}).json()
    assert replacement['device_id']!=old['device_id']
    assert client.post('/api/v1/account/session/logout',headers=headers(old)).status_code==200
    assert client.get('/api/v1/account/status',headers=headers(replacement)).status_code==200
