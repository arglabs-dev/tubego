import json
import uuid
from contextlib import closing
import pytest
from fastapi import HTTPException
from test_private_library import service,owner
from test_device_delivery import add_device
from tubego_server.auth import utcnow
from tubego_server.delivery import publish_ready,transfer_allowed
from tubego_server.resource_cleanup import process


def ready(app,user):
    rid=uuid.uuid4().hex;root=app.state.settings.data_dir/'media';relative=user['id']+'/'+rid+'/media.mp4'
    path=root/relative;path.parent.mkdir(parents=True);path.write_bytes(b'video bytes')
    with app.state.db.transaction() as conn:
        conn.execute("INSERT INTO resources(id,user_id,source_url,title,created_at,updated_at) VALUES(?,?,'https://example.org/video','Saved title',?,?)",(rid,user['id'],utcnow(),utcnow()))
    published=publish_ready(app.state.db,root,rid,relative)
    return rid,path,published


def erase(client,user,rid,scope='devices',command=None):
    return client.post(f'/api/v1/resources/{rid}/delete',headers=user['headers'],json={'scope':scope,'request_id':command or str(uuid.uuid4())})


def test_delete_devices_keeps_server_history_and_offline_tombstones(service):
    app,client=service;alice=owner(app);other=add_device(app,alice);bob=owner(app);rid,path,published=ready(app,alice)
    with app.state.db.transaction() as conn:conn.execute("UPDATE deliveries SET status='complete',confirmed_at=?,downloaded_bytes=11 WHERE resource_id=?",(utcnow(),rid))
    response=erase(client,alice,rid);assert response.status_code==200 and path.exists()
    assert not transfer_allowed(app.state.db,{'id':alice['id'],'session_id':alice['session']},rid)
    for user in (alice,other):
        snapshot=client.get('/api/v1/device/sync',headers=user['headers']).json()
        assert snapshot['deliveries'][0]['delivery_status']=='deleted'
        assert snapshot['deliveries'][0]['local_deleted_at']
        assert snapshot['deliveries'][0]['server_available']
        assert any(e['kind']=='resource.deleted' for e in snapshot['events'])
        assert client.post(f'/api/v1/resources/{rid}/deliveries/confirm',headers=user['headers'],json={'size_bytes':11,'sha256':published['sha256']}).status_code==409
    assert client.get(f'/api/v1/resources/{rid}',headers=alice['headers']).json()['title']=='Saved title'
    assert erase(client,bob,rid).status_code==404 and path.exists()


def test_idempotent_replay_cannot_delete_again_after_explicit_approval(service):
    app,client=service;user=owner(app);rid,path,_=ready(app,user);command=str(uuid.uuid4())
    first=erase(client,user,rid,command=command).json()
    assert client.post(f'/api/v1/resources/{rid}/deliveries/request',headers=user['headers'],json={'approve_redownload':True}).status_code==200
    assert erase(client,user,rid,command=command).json()==first
    assert client.get('/api/v1/device/sync',headers=user['headers']).json()['deliveries'][0]['delivery_status']=='pending'
    assert erase(client,user,rid,'devices_and_server',command).status_code==409
    with closing(app.state.db.connect()) as conn:
        assert conn.execute("SELECT count(*) FROM audit WHERE action='resource.deleted'").fetchone()[0]==1


def test_server_deletion_is_owner_scoped_cancels_task_and_keeps_metadata(service):
    app,client=service;user=owner(app);other=owner(app);rid,path,_=ready(app,user);other_rid,other_path,_=ready(app,other)
    partial=path.parent/'partial';partial.write_bytes(b'partial')
    tid=uuid.uuid4().hex
    with app.state.db.transaction() as conn:conn.execute("INSERT INTO tasks(id,user_id,resource_id,status,created_at,updated_at) VALUES(?,?,?,'running',?,?)",(tid,user['id'],rid,utcnow(),utcnow()))
    assert erase(client,user,rid,'devices_and_server').status_code==200
    assert not path.parent.exists() and other_path.exists()
    with closing(app.state.db.connect()) as conn:
        assert conn.execute('SELECT status FROM tasks WHERE id=?',(tid,)).fetchone()[0]=='cancelled'
        resource=conn.execute('SELECT * FROM resources WHERE id=?',(rid,)).fetchone()
        assert resource['title']=='Saved title' and resource['server_deleted_at'] and resource['server_path'] is None
        assert json.loads(conn.execute("SELECT value_json FROM settings WHERE scope='task' AND owner_id=? AND key='cancel_requested'",(tid,)).fetchone()[0]) is True
    assert client.get(f'/api/v1/resources/{rid}/download',headers=user['headers']).status_code==404


def test_worker_lease_waits_and_publication_is_fenced(service):
    app,client=service;user=owner(app);rid,path,_=ready(app,user);tid=uuid.uuid4().hex
    with app.state.db.transaction() as conn:
        conn.execute("INSERT INTO tasks(id,user_id,resource_id,status,created_at,updated_at) VALUES(?,?,?,'running',?,?)",(tid,user['id'],rid,utcnow(),utcnow()))
        conn.execute("INSERT INTO settings VALUES('global','','scheduler_lease',?,?)",(json.dumps({'task_id':tid}),utcnow()))
    assert erase(client,user,rid,'devices_and_server').status_code==200 and path.exists()
    with pytest.raises(HTTPException):publish_ready(app.state.db,app.state.settings.data_dir/'media',rid,str(path))
    with app.state.db.transaction() as conn:conn.execute("DELETE FROM settings WHERE scope='global' AND key='scheduler_lease'")
    process(app.state.db,app.state.settings.data_dir/'media');assert not path.exists()


def test_republication_and_new_devices_require_deliberate_approval(service):
    app,client=service;user=owner(app);rid,path,_=ready(app,user)
    assert erase(client,user,rid).status_code==200
    new=add_device(app,user)
    publish_ready(app.state.db,app.state.settings.data_dir/'media',rid,str(path))
    # Newly linked device is not automatically backfilled, but explicit request
    # must still recognize the all-device deletion, even without a delivery row.
    assert client.post(f'/api/v1/resources/{rid}/deliveries/request',headers=new['headers'],json={}).status_code==409
    assert client.post(f'/api/v1/resources/{rid}/deliveries/request',headers=new['headers'],json={'approve_redownload':True}).status_code==200
    assert client.get('/api/v1/device/sync',headers=user['headers']).json()['deliveries'][0]['delivery_status']=='approval_required'


def test_retry_cleanup_survives_filesystem_failure_and_never_follows_symlink(service,monkeypatch,tmp_path):
    from tubego_server import resource_cleanup
    app,client=service;user=owner(app);rid,path,_=ready(app,user)
    foreign=tmp_path/'foreign';foreign.mkdir();(foreign/'safe').write_bytes(b'keep')
    (path.parent/'link').symlink_to(foreign,target_is_directory=True)
    original=resource_cleanup.remove
    def denied(*args):raise PermissionError()
    monkeypatch.setattr(resource_cleanup,'remove',denied)
    assert erase(client,user,rid,'devices_and_server').status_code==200 and path.exists()
    with closing(app.state.db.connect()) as conn:
        assert json.loads(conn.execute("SELECT value_json FROM settings WHERE owner_id=? AND key='cleanup_job'",(rid,)).fetchone()[0])['error']=='cleanup_failed'
    monkeypatch.setattr(resource_cleanup,'remove',original)
    process(app.state.db,app.state.settings.data_dir/'media')
    assert not path.exists() and (foreign/'safe').exists()


def test_logout_does_not_propagate_resource_deletion(service):
    app,client=service;user=owner(app);other=add_device(app,user);rid,path,_=ready(app,user)
    assert client.post('/api/v1/account/session/logout',headers=user['headers']).status_code==200
    snapshot=client.get('/api/v1/device/sync',headers=other['headers']).json()
    assert snapshot['deliveries'][0]['delivery_status']=='pending' and path.exists()
