import json
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
import pytest
from fastapi import HTTPException
from test_private_library import service,owner
from test_device_delivery import add_device
from test_resource_deletion import ready,erase
from tubego_server.auth import utcnow
from tubego_server.delivery import publish_ready
from tubego_server.tasks import submit
from tubego_server.resource_identity import remember

def resolver(*args,**kwargs):return [(None,None,None,None,('93.184.216.34',443))]

def principal(user):return {'id':user['id'],'session_id':user['session']}

def ask(client,user,rid,approved=False,command=None):
    return client.post(f'/api/v1/resources/{rid}/request-again',headers=user['headers'],json={'request_id':command or str(uuid.uuid4()),'approve_redownload':approved})


def test_youtube_variants_same_quality_dedup_but_variants_and_users_private(service):
    app,_=service;alice=owner(app);bob=owner(app)
    urls=['https://youtu.be/dQw4w9WgXcQ?si=tracking','https://www.youtube.com/watch?v=dQw4w9WgXcQ&utm_source=share&t=123','https://m.youtube.com/shorts/dQw4w9WgXcQ','https://www.youtube.com/v/dQw4w9WgXcQ']
    first=submit(app.state.db,principal(alice),urls[0],'720',resolver=resolver)
    for url in urls[1:]:assert submit(app.state.db,principal(alice),url,'720',resolver=resolver)['resource_id']==first['resource_id']
    assert submit(app.state.db,principal(alice),urls[0],'1080',resolver=resolver)['resource_id']!=first['resource_id']
    assert submit(app.state.db,principal(alice),urls[0],'audio',resolver=resolver)['resource_id']!=first['resource_id']
    assert submit(app.state.db,principal(bob),urls[0],'720',resolver=resolver)['resource_id']!=first['resource_id']


def test_unknown_signed_queries_are_preserved_and_extractor_known_identity_is_private(service):
    app,_=service;alice=owner(app);bob=owner(app)
    url='https://example.org/video?token=one';other='https://example.org/video?token=two'
    first=submit(app.state.db,principal(alice),url,'720',resolver=resolver)
    different=submit(app.state.db,principal(alice),other,'720',resolver=resolver)
    assert first['resource_id']!=different['resource_id']
    with app.state.db.transaction() as conn:
        remember(conn,alice['id'],url,{'url':'https://example.org/video/123','source_id':'123','extractor':'TrustedPortal'},first['resource_id'])
        remember(conn,alice['id'],'https://example.org/alias?id=123',{'url':'https://example.org/video/123','source_id':'123','extractor':'TrustedPortal'})
    assert submit(app.state.db,principal(alice),'https://example.org/alias?id=123','720',resolver=resolver)['resource_id']==first['resource_id']
    assert submit(app.state.db,principal(bob),'https://example.org/alias?id=123','720',resolver=resolver)['resource_id']!=first['resource_id']


def test_ready_existing_shown_and_server_gone_rerequest_at_most_one_job(service):
    app,client=service;user=owner(app);other=add_device(app,user);rid,path,_=ready(app,user)
    assert ask(client,user,rid).json()['server_available']
    with app.state.db.transaction() as conn:
        assert conn.execute('SELECT count(*) FROM tasks WHERE resource_id=?',(rid,)).fetchone()[0]==0
        conn.execute('UPDATE resources SET server_deleted_at=?,server_path=NULL WHERE id=?',(utcnow(),rid))
    command=str(uuid.uuid4())
    with ThreadPoolExecutor(4) as executor:
        results=list(executor.map(lambda _:ask(client,user,rid,command=command).json(),range(4)))
    assert all(row['task_id']==results[0]['task_id'] for row in results)
    assert ask(client,user,rid).json()['task_id']==results[0]['task_id']
    with closing(app.state.db.connect()) as conn:
        assert conn.execute("SELECT count(*) FROM tasks WHERE resource_id=? AND status='queued'",(rid,)).fetchone()[0]==1
        assert conn.execute('SELECT title FROM resources WHERE id=?',(rid,)).fetchone()[0]=='Saved title'
    assert client.get('/api/v1/device/sync',headers=other['headers']).json()['deliveries'][0]['delivery_status']=='pending'


def test_complete_copy_not_downloaded_again_and_deliberate_approval_per_device(service):
    app,client=service;user=owner(app);other=add_device(app,user);rid,path,published=ready(app,user)
    assert client.post(f'/api/v1/resources/{rid}/deliveries/confirm',headers=other['headers'],json={'size_bytes':11,'sha256':published['sha256']}).status_code==200
    with app.state.db.transaction() as conn:
        conn.execute('UPDATE resources SET server_deleted_at=?,server_path=NULL WHERE id=?',(utcnow(),rid))
        conn.execute("UPDATE deliveries SET status='deleted',deleted_at=?,downloaded_bytes=0 WHERE resource_id=? AND device_id=?",(utcnow(),rid,user['device']))
    assert ask(client,user,rid).status_code==409
    assert ask(client,user,rid,True).status_code==200
    publish_ready(app.state.db,app.state.settings.data_dir/'media',rid,str(path))
    assert client.get('/api/v1/device/sync',headers=other['headers']).json()['deliveries'][0]['delivery_status']=='complete'
    # A global deliberate deletion leaves all OTHER devices waiting for approval.
    assert erase(client,user,rid).status_code==200
    assert ask(client,user,rid,True).status_code==200
    snapshots=[client.get('/api/v1/device/sync',headers=u['headers']).json()['deliveries'][0] for u in (user,other)]
    assert snapshots[0]['delivery_status']=='pending' and snapshots[0]['local_deleted_at'] is None
    assert snapshots[1]['delivery_status']=='approval_required' and snapshots[1]['local_deleted_at']


def test_new_device_explicit_history_request_and_normal_submit_do_not_restore_tombstones(service):
    app,client=service;user=owner(app);rid,path,_=ready(app,user);new=add_device(app,user)
    assert erase(client,user,rid).status_code==200
    assert ask(client,new,rid).status_code==409
    assert ask(client,new,rid,True).status_code==200
    assert client.get('/api/v1/device/sync',headers=user['headers']).json()['deliveries'][0]['local_deleted_at']
    # An ordinary duplicated link returns the history, never enqueues re-download.
    first=submit(app.state.db,principal(user),'https://example.org/new','720',resolver=resolver)
    with app.state.db.transaction() as conn:conn.execute('UPDATE resources SET server_deleted_at=? WHERE id=?',(utcnow(),first['resource_id']))
    duplicate=submit(app.state.db,principal(user),'https://example.org/new','720',resolver=resolver)
    assert duplicate['existing'] and duplicate['task_id']==first['task_id']


def test_pending_cleanup_wrong_ownership_and_request_reuse_are_rejected(service):
    app,client=service;user=owner(app);bob=owner(app);rid,path,_=ready(app,user);command=str(uuid.uuid4())
    assert ask(client,bob,rid).status_code==404
    with app.state.db.transaction() as conn:
        conn.execute("INSERT INTO settings VALUES('resource',?,'cleanup_job','{}',?)",(rid,utcnow()))
    assert ask(client,user,rid).status_code==409
    with app.state.db.transaction() as conn:
        assert conn.execute('SELECT count(*) FROM tasks WHERE resource_id=?',(rid,)).fetchone()[0]==0
        conn.execute("DELETE FROM settings WHERE owner_id=? AND key='cleanup_job'",(rid,))
    assert ask(client,user,rid,command=command).status_code==200
    assert ask(client,user,rid,True,command).status_code==409
    other=add_device(app,user);assert ask(client,other,rid,command=command).status_code==409


def test_revoked_principal_cannot_submit_or_restore(service):
    app,client=service;user=owner(app);rid,path,_=ready(app,user)
    with app.state.db.transaction() as conn:conn.execute('UPDATE sessions SET revoked_at=? WHERE id=?',(utcnow(),user['session']))
    assert ask(client,user,rid).status_code==401
    with pytest.raises(HTTPException):submit(app.state.db,principal(user),'https://example.org/video','720',resolver=resolver)


def test_legacy_youtube_history_and_old_submission_uuid_survive_identity_upgrade(service):
    import hashlib
    app,_=service;user=owner(app);old_url='https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10';rid=str(uuid.uuid4());tid=str(uuid.uuid4());command=str(uuid.uuid4())
    legacy=hashlib.sha256((old_url+'\nvideo\n720').encode()).hexdigest()
    with app.state.db.transaction() as conn:
        conn.execute('INSERT INTO resources(id,user_id,source_url,source_key,created_at,updated_at) VALUES(?,?,?,?,?,?)',(rid,user['id'],old_url,legacy,utcnow(),utcnow()))
        conn.execute('INSERT INTO tasks(id,user_id,resource_id,created_at,updated_at) VALUES(?,?,?,?,?)',(tid,user['id'],rid,utcnow(),utcnow()))
        conn.execute("INSERT INTO settings VALUES('user',?,?,?,?)",(user['id'],'submission:'+command,json.dumps({'source_key':legacy,'resource_id':rid}),utcnow()))
    assert submit(app.state.db,principal(user),'https://youtu.be/dQw4w9WgXcQ','720',resolver=resolver)['resource_id']==rid
    assert submit(app.state.db,principal(user),old_url,'720',request_id=command,resolver=resolver)['resource_id']==rid
