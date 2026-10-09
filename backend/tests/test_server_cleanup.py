import json
import uuid
from contextlib import closing
import pytest
from test_private_library import service,owner
from test_resource_deletion import ready
from tubego_server.auth import utcnow
from tubego_server.resource_cleanup import process,schedule

def clean(client,user,scope='own',command=None,confirmed=True):
    return client.post('/api/v1/server-cleanup',headers=user['headers'],json={'scope':scope,'request_id':command or str(uuid.uuid4()),'confirmed':confirmed})


def test_own_cleanup_preview_preserves_other_users_and_mobile_copies_history(service):
    app,client=service;alice=owner(app);bob=owner(app);rid,path,published=ready(app,alice);bid,bpath,_=ready(app,bob)
    with app.state.db.transaction() as conn:conn.execute("INSERT INTO settings VALUES('user',?,'preserve_server_files','true',?)",(alice['id'],utcnow()))
    assert client.post(f'/api/v1/resources/{rid}/deliveries/confirm',headers=alice['headers'],json={'size_bytes':11,'sha256':published['sha256']}).status_code==200
    bot=app.state.settings.data_dir.parent/'bot-files';bot.mkdir();(bot/'safe.mp4').write_bytes(b'bot')
    response=client.get('/api/v1/server-cleanup/preview',headers=alice['headers']).json()
    assert response['scope']=='own' and response['files_known']==1 and response['bytes_known']==11
    assert clean(client,alice).status_code==200 and not path.exists() and bpath.exists() and (bot/'safe.mp4').exists()
    snapshot=client.get('/api/v1/device/sync',headers=alice['headers']).json()
    row=snapshot['deliveries'][0];assert row['delivery_status']=='complete' and row['local_deleted_at'] is None and not row['server_available']
    assert not any(e['kind']=='resource.deleted' for e in snapshot['events'])
    assert client.get(f'/api/v1/resources/{rid}',headers=alice['headers']).json()['title']=='Saved title'


def test_global_cleanup_requires_current_admin_even_if_request_queued_before_demotion(service):
    app,client=service;admin=owner(app,role='admin');user=owner(app);rid,path,_=ready(app,user)
    assert client.get('/api/v1/server-cleanup/preview?scope=global',headers=user['headers']).status_code==403
    assert clean(client,user,'global').status_code==403 and path.exists()
    assert client.get('/api/v1/server-cleanup/preview?scope=global',headers=admin['headers']).json()['files_known']==1
    with app.state.db.transaction() as conn:conn.execute("UPDATE users SET role='user' WHERE id=?",(admin['id'],))
    assert clean(client,admin,'global').status_code==403 and path.exists()
    with app.state.db.transaction() as conn:conn.execute("UPDATE users SET role='admin' WHERE id=?",(admin['id'],))
    assert clean(client,admin,'global').status_code==200 and not path.exists()
    with closing(app.state.db.connect()) as conn:
        detail=json.loads(conn.execute("SELECT detail_json FROM audit WHERE action='server.cleaned'").fetchone()[0]);assert detail['scope']=='global'


def test_idempotent_cleanup_does_not_remove_new_files_or_change_scope_on_replay(service):
    app,client=service;admin=owner(app,role='admin');rid,path,_=ready(app,admin);command=str(uuid.uuid4())
    first=clean(client,admin,command=command).json();new,other,_=ready(app,admin)
    assert clean(client,admin,command=command).json()==first and other.exists()
    assert clean(client,admin,'global',command).status_code==409 and other.exists()
    with closing(app.state.db.connect()) as conn:assert conn.execute("SELECT count(*) FROM audit WHERE action='server.cleaned'").fetchone()[0]==1


@pytest.mark.parametrize('status',['queued','running','paused'])
def test_cleanup_cancels_all_active_statuses_and_waits_for_worker_release(service,status):
    app,client=service;user=owner(app);rid,path,_=ready(app,user);tid=str(uuid.uuid4())
    with app.state.db.transaction() as conn:
        conn.execute('INSERT INTO tasks(id,user_id,resource_id,status,created_at,updated_at) VALUES(?,?,?,?,?,?)',(tid,user['id'],rid,status,utcnow(),utcnow()))
        conn.execute("INSERT INTO settings VALUES('global','','scheduler_lease',?,?)",(json.dumps({'task_id':tid,'token':'lease'}),utcnow()))
    assert clean(client,user).status_code==200 and path.exists()
    with app.state.db.transaction() as conn:
        assert conn.execute('SELECT status FROM tasks WHERE id=?',(tid,)).fetchone()[0]=='cancelled'
        assert conn.execute('SELECT server_path FROM resources WHERE id=?',(rid,)).fetchone()[0] is None
        conn.execute("DELETE FROM settings WHERE scope='global' AND key='scheduler_lease'")
    process(app.state.db,app.state.settings.data_dir/'media');assert not path.exists()


def test_cleanup_failure_is_durable_and_removes_partials_without_following_links(service,monkeypatch,tmp_path):
    from tubego_server import resource_cleanup
    app,client=service;user=owner(app);rid,path,_=ready(app,user);partial=path.parent/'part';partial.write_bytes(b'partial')
    foreign=tmp_path/'foreign';foreign.mkdir();safe=foreign/'safe';safe.write_bytes(b'keep');(path.parent/'link').symlink_to(foreign,target_is_directory=True)
    original=resource_cleanup.remove
    monkeypatch.setattr(resource_cleanup,'remove',lambda *args:(_ for _ in ()).throw(PermissionError()))
    assert clean(client,user).status_code==200 and partial.exists()
    with closing(app.state.db.connect()) as conn:assert json.loads(conn.execute("SELECT value_json FROM settings WHERE owner_id=? AND key='cleanup_job'",(rid,)).fetchone()[0])['error']=='cleanup_failed'
    monkeypatch.setattr(resource_cleanup,'remove',original);process(app.state.db,app.state.settings.data_dir/'media')
    assert not path.parent.exists() and safe.exists()


def test_confirm_revoked_session_and_existing_unsafe_reference_remain_protected(service):
    app,client=service;user=owner(app);rid,path,_=ready(app,user)
    assert clean(client,user,confirmed=False).status_code==400 and path.exists()
    with app.state.db.transaction() as conn:
        schedule(conn,app.state.settings.data_dir/'media',user['id'],rid,'/foreign/video',None)
        conn.execute('UPDATE resources SET server_path=NULL WHERE id=?',(rid,))
    assert clean(client,user).status_code==200
    with closing(app.state.db.connect()) as conn:
        job=json.loads(conn.execute("SELECT value_json FROM settings WHERE owner_id=? AND key='cleanup_job'",(rid,)).fetchone()[0]);assert job['unsafe_reference'] and job['error']=='cleanup_failed'
    with app.state.db.transaction() as conn:conn.execute('UPDATE sessions SET revoked_at=? WHERE id=?',(utcnow(),user['session']))
    assert clean(client,user).status_code==401
