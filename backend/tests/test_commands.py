import uuid
from contextlib import closing
from test_private_library import service,owner
from test_device_delivery import add_device
from tubego_server.delivery import write_setting
from tubego_server.auth import utcnow


def send(client,user,sequence,kind='preferences',payload=None,identifier=None):
    return client.post('/api/v1/device/commands',headers=user['headers'],json={
        'id':identifier or str(uuid.uuid4()),'sequence':sequence,'kind':kind,
        'payload':payload if payload is not None else {'selection':'480','rewind_seconds':10,'ask_every_time':False}})


def test_order_receipt_replay_cannot_overwrite_newer_preferences(service):
    app,client=service;user=owner(app);identifier=str(uuid.uuid4())
    assert send(client,user,2).status_code==409
    first=send(client,user,1,identifier=identifier)
    assert first.status_code==200 and first.json()['status']=='complete'
    newer={'selection':'1080','rewind_seconds':20,'ask_every_time':True}
    assert send(client,user,2,payload=newer).status_code==200
    assert send(client,user,1,identifier=identifier).json()==first.json()
    assert client.get('/api/v1/account/preferences',headers=user['headers']).json()==newer
    assert send(client,user,1,payload=newer,identifier=identifier).status_code==409


def test_pending_effect_receipt_after_crash_is_safe(service):
    app,client=service;user=owner(app);identifier=str(uuid.uuid4())
    payload={'selection':'480','rewind_seconds':10,'ask_every_time':False}
    with app.state.db.transaction() as conn:
        write_setting(conn,'device',user['device'],'command_receipt:'+identifier,{'identity':{'sequence':1,'kind':'preferences','payload':payload},'status':'pending'})
        write_setting(conn,'device',user['device'],'command_inflight',identifier)
        write_setting(conn,'device',user['device'],'preferences_command:'+identifier,payload)
        write_setting(conn,'user',user['id'],'media_preferences',{'selection':'1080','rewind_seconds':20,'ask_every_time':True})
    assert send(client,user,1,identifier=identifier).status_code==200
    assert client.get('/api/v1/account/preferences',headers=user['headers']).json()['selection']=='1080'


def test_rejected_intention_advances_and_device_cursors_are_private(service):
    app,client=service;user=owner(app);other=add_device(app,user)
    rejection=send(client,user,1,kind='task_cancel',payload={'task_id':str(uuid.uuid4())})
    assert rejection.status_code==200 and rejection.json()['status']=='rejected'
    assert send(client,user,2,kind='noop',payload={}).status_code==200
    assert send(client,other,1,kind='noop',payload={}).status_code==200
    assert send(client,other,3,kind='noop',payload={}).status_code==409


def test_expired_session_does_not_advance_or_mutate_preferences(service):
    app,client=service;user=owner(app)
    with app.state.db.transaction() as conn:conn.execute('UPDATE sessions SET expires_at=? WHERE id=?',('2000-01-01T00:00:00+00:00',user['session']))
    assert send(client,user,1).status_code==401
    with closing(app.state.db.connect()) as conn:
        assert not conn.execute("SELECT 1 FROM settings WHERE scope='device' AND owner_id=? AND key='command_sequence'",(user['device'],)).fetchone()


def test_cached_old_recovery_cannot_undo_newer_delete(service):
    from test_resource_deletion import ready
    app,client=service;user=owner(app);rid,_,_=ready(app,user)
    recovery=str(uuid.uuid4())
    first=send(client,user,1,'recover',{'resource_id':rid,'approve_redownload':True},recovery)
    assert first.status_code==200 and first.json()['status']=='complete'
    deletion=send(client,user,2,'delete',{'resource_id':rid,'scope':'devices'})
    assert deletion.status_code==200 and deletion.json()['status']=='complete'
    assert send(client,user,1,'recover',{'resource_id':rid,'approve_redownload':True},recovery).json()==first.json()
    with closing(app.state.db.connect()) as conn:
        rows=conn.execute('SELECT status,deleted_at FROM deliveries WHERE resource_id=?',(rid,)).fetchall()
        assert rows and all(row['status']=='deleted' and row['deleted_at'] for row in rows)


def test_task_priority_receipt_avoids_duplicate_increment_after_lost_reply(service):
    from tubego_server.tasks import action,submit
    from test_resource_recovery import resolver,principal
    app,_=service;user=owner(app)
    task=submit(app.state.db,principal(user),'https://example.org/video','720',resolver=resolver)
    identifier=str(uuid.uuid4())
    first=action(app.state.db,principal(user),task['task_id'],'priority',identifier)
    second=action(app.state.db,principal(user),task['task_id'],'priority',str(uuid.uuid4()))
    assert second['priority']>first['priority']
    assert action(app.state.db,principal(user),task['task_id'],'priority',identifier)==first
    with closing(app.state.db.connect()) as conn:assert conn.execute('SELECT priority FROM tasks WHERE id=?',(task['task_id'],)).fetchone()[0]==second['priority']
