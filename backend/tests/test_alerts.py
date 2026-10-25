import json
import uuid
import pytest
from test_private_library import service,owner
from test_device_delivery import add_device
from tubego_server.alerts import notify_admins
from tubego_server.auth import token_hash,utcnow


def event(app,user,kind,device=None,payload=None):
    with app.state.db.transaction() as conn:
        return conn.execute('INSERT INTO events(user_id,device_id,kind,payload_json,created_at) VALUES(?,?,?,?,?)',(user['id'],device,kind,json.dumps(payload or {}),utcnow())).lastrowid


def test_user_scope_device_cursors_bounded_and_no_completion_alerts(service):
    app,client=service;alice=owner(app);bob=owner(app);other=add_device(app,alice)
    kinds=['resource_available','task_updated','server_storage_paused','download_failed','download_failed']
    ids=[event(app,alice,kind,payload={'resource_id':'resource','raw':'secret/path?token=abc'}) for kind in kinds]
    event(app,bob,'download_failed');private=event(app,alice,'download_failed',device=other['id'])
    url='/api/v1/device/alerts'
    first=client.get(url+'?limit=1',headers=alice['headers']).json()
    assert [row['id'] for row in first['items']]==[ids[3]] and first['has_more']
    assert 'secret' not in str(first) and 'token' not in str(first)
    assert client.post(url+'/ack',json={'event_id':ids[3]},headers=alice['headers']).status_code==200
    assert [row['id'] for row in client.get(url,headers=alice['headers']).json()['items']]==[ids[4]]
    # A separate device has its own durable cursor and addressed alerts.
    assert [row['id'] for row in client.get(url,headers=other['headers']).json()['items']]==ids[3:]+[private]
    assert client.post(url+'/ack',json={'event_id':private},headers=alice['headers']).status_code==409
    assert client.post(url+'/ack',json={'event_id':ids[4]},headers=bob['headers']).status_code==409
    assert client.post(url+'/ack',json={'event_id':ids[3]},headers=alice['headers']).json()['cursor']==ids[3]
    assert client.get(url,headers=alice['headers']).json()['cursor']==ids[3]


def test_admin_only_events_rechecked_after_role_change(service):
    app,client=service;admin=owner(app,'admin');user=owner(app)
    with app.state.db.transaction() as conn:
        assert notify_admins(conn,'registration_pending',{'user_id':user['id'],'token':'never'},'verified:'+user['id'])
        assert not notify_admins(conn,'registration_pending',{'user_id':user['id']},'verified:'+user['id'])
        notify_admins(conn,'admin_maintenance',{'operation':'restart','status':'failed','signed_url':'never'},'restart:1')
    url='/api/v1/device/alerts'
    items=client.get(url,headers=admin['headers']).json()['items']
    assert {x['kind'] for x in items}=={'registration_pending','admin_maintenance'}
    assert all(x['administrative'] for x in items) and 'never' not in str(items)
    assert client.get(url,headers=user['headers']).json()['items']==[]
    with app.state.db.transaction() as conn:conn.execute("UPDATE users SET role='user' WHERE id=?",(admin['id'],))
    assert client.get(url,headers=admin['headers']).json()['items']==[]
    assert client.post(url+'/ack',json={'event_id':items[-1]['id']},headers=admin['headers']).status_code==409


def test_email_verification_notifies_approved_admins_once(service):
    app,client=service;admin=owner(app,'admin');blocked=owner(app,'admin',status='blocked');user=owner(app,status='pending_verification',verified=False)
    token=uuid.uuid4().hex;now=utcnow()
    with app.state.db.transaction() as conn:
        conn.execute('INSERT INTO verification_tokens(token_hash,user_id,expires_at,created_at) VALUES(?,?,?,?)',(token_hash(token),user['id'],'2099-01-01T00:00:00+00:00',now))
    assert client.post('/api/v1/auth/verify',json={'token':token}).status_code==200
    assert client.post('/api/v1/auth/verify',json={'token':token}).status_code==400
    with app.state.db.transaction() as conn:
        rows=conn.execute("SELECT user_id FROM events WHERE kind='registration_pending'").fetchall()
        assert [row['user_id'] for row in rows]==[admin['id']]


@pytest.mark.parametrize('change',["UPDATE sessions SET revoked_at='now'","UPDATE devices SET revoked_at='now'","UPDATE users SET status='blocked'","UPDATE users SET email_verified_at=NULL"])
def test_revoked_identity_cannot_read_or_ack_alerts(service,change):
    app,client=service;user=owner(app);key=event(app,user,'download_failed')
    with app.state.db.transaction() as conn:conn.execute(change)
    assert client.get('/api/v1/device/alerts',headers=user['headers']).status_code in (401,403)
    assert client.post('/api/v1/device/alerts/ack',json={'event_id':key},headers=user['headers']).status_code in (401,403)
