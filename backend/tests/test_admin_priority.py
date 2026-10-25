from datetime import datetime, timezone, timedelta
import uuid
import pytest
from fastapi.testclient import TestClient
from tubego_server.main import create_app
from tubego_server.config import Settings
from tubego_server.auth import token_hash, utcnow
from tubego_server.scheduler import Scheduler

@pytest.fixture
def service(tmp_path):
    app=create_app(Settings(tmp_path))
    with TestClient(app) as client:
        yield app,client


def user(app,role='user',status='approved',verified=True):
    uid=uuid.uuid4().hex; token=uuid.uuid4().hex
    with app.state.db.transaction() as conn:
        conn.execute('INSERT INTO users VALUES(?,?,?,?,?,?,?,?)',(uid,uid+'@example.com','hash',role,status,utcnow() if verified else None,utcnow(),utcnow()))
        conn.execute('INSERT INTO sessions VALUES(?,?,NULL,?,?,NULL,?)',(uuid.uuid4().hex,uid,token_hash(token),(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat(),utcnow()))
    return uid,{'Authorization':'Bearer '+token}


def test_admin_setting_persists_and_is_audited(service):
    app,client=service
    target,_=user(app); aid,admin=user(app,role='admin')
    endpoint=f'/api/v1/admin/users/{target}/priority'
    assert client.put(endpoint,headers=admin,json={'level':'prioritario'}).json()=={'user_id':target,'level':'prioritario'}
    with app.state.db.transaction() as conn:
        assert conn.execute("SELECT value_json FROM settings WHERE owner_id=? AND key='priority_level'",(target,)).fetchone()[0]=='"prioritario"'
        row=conn.execute('SELECT * FROM audit').fetchone()
        assert row['actor_user_id']==aid and row['target_id']==target
    assert client.put(endpoint,headers=admin,json={'level':'normal'}).status_code==200
    assert client.put(endpoint,headers=admin,json={'level':'urgent'}).status_code==422
    assert client.put('/api/v1/admin/users/missing/priority',headers=admin,json={'level':'normal'}).status_code==404

@pytest.mark.parametrize('role,status,verified',[('user','approved',True),('admin','pending_approval',True),('admin','blocked',True),('admin','approved',False)])
def test_non_admin_and_ineligible_accounts_cannot_read_or_write(service,role,status,verified):
    app,client=service; target,_=user(app); _,headers=user(app,role,status,verified)
    assert client.get('/api/v1/admin/users/priorities',headers=headers).status_code==403
    assert client.put(f'/api/v1/admin/users/{target}/priority',headers=headers,json={'level':'prioritario'}).status_code==403
    with app.state.db.transaction() as conn:
        assert conn.execute("SELECT 1 FROM settings WHERE key='priority_level'").fetchone() is None


def test_missing_auth_and_paginated_listing(service):
    app,client=service; _,admin=user(app,role='admin'); user(app); user(app)
    assert client.get('/api/v1/admin/users/priorities').status_code==401
    first=client.get('/api/v1/admin/users/priorities?limit=2',headers=admin).json()
    assert len(first['items'])==2 and first['next_cursor']
    second=client.get('/api/v1/admin/users/priorities',params={'after':first['next_cursor'],'limit':2},headers=admin).json()
    assert len(second['items'])==1 and second['next_cursor'] is None
    assert {x['id'] for x in first['items']}.isdisjoint({x['id'] for x in second['items']})
    assert 'password_hash' not in str(first)
