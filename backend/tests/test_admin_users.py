from datetime import datetime,timezone,timedelta
import json
from pathlib import Path
import uuid
import pytest
from fastapi.testclient import TestClient
from tubego_server.main import create_app
from tubego_server.config import Settings
from tubego_server.auth import hash_password,token_hash,utcnow,require_admin
from tubego_server.account_cleanup import process,SCOPE

@pytest.fixture
def service(tmp_path):
    app=create_app(Settings(tmp_path,account_cleanup_interval=0))
    with TestClient(app) as client:
        accounts={};sessions={}
        for name,role in [('admin','admin'),('first','user'),('second','user')]:
            uid=str(uuid.uuid4());accounts[name]=uid;now=utcnow()
            with app.state.db.transaction() as conn:
                conn.execute('INSERT INTO users VALUES (?,?,?,?,\'approved\',?,?,?)',(uid,name+'@example.com',hash_password('correct horse battery'),role,now,now,now))
            response=client.post('/api/v1/auth/login',json={'email':name+'@example.com','password':'correct horse battery','device_name':name+' phone'})
            assert response.status_code==200;sessions[name]=response.json()
        yield app,client,accounts,sessions

def headers(session):return {'Authorization':'Bearer '+session['token']}

def media(app,user):
    rid=str(uuid.uuid4());path=app.state.settings.data_dir/'media'/user/rid/'media.mp4';path.parent.mkdir(parents=True);path.write_bytes(b'content')
    tid=str(uuid.uuid4());now=utcnow()
    with app.state.db.transaction() as conn:
        conn.execute('INSERT INTO resources(id,user_id,source_url,title,server_path,ready_at,created_at,updated_at) VALUES (?,?,\'https://example.com/private\',\'Private video\',?,?,?,?)',(rid,user,str(path),now,now,now))
        conn.execute('INSERT INTO tasks(id,user_id,resource_id,status,created_at,updated_at) VALUES (?,?,?,\'queued\',?,?)',(tid,user,rid,now,now))
    return rid,tid,path

def test_block_isolates_files_revokes_devices_and_preserves_history(service):
    app,client,accounts,sessions=service
    rid,tid,path=media(app,accounts['first']);_,_,foreign=media(app,accounts['second'])
    bot=app.state.settings.data_dir/'bot-video.mp4';bot.write_bytes(b'bot')
    response=client.post('/api/v1/admin/users/'+accounts['first']+'/block',headers=headers(sessions['admin']))
    assert response.json()=={'status':'blocked','cleanup_pending':False,'cleanup_error':None}
    assert not path.exists() and foreign.exists() and bot.exists()
    denied=client.get('/api/v1/account/status',headers=headers(sessions['first']))
    assert denied.status_code==403 and denied.json()['detail']['wipe_local'] is True
    assert client.get('/api/v1/account/status',headers=headers(sessions['second'])).status_code==200
    with app.state.db.transaction() as conn:
        assert conn.execute('SELECT status FROM tasks WHERE id=?',(tid,)).fetchone()[0]=='cancelled'
        resource=conn.execute('SELECT * FROM resources WHERE id=?',(rid,)).fetchone()
        assert resource['title']=='Private video' and resource['server_deleted_at'] and resource['server_path'] is None
        assert conn.execute("SELECT count(*) FROM devices WHERE user_id=? AND revoked_at IS NULL",(accounts['first'],)).fetchone()[0]==0
        assert conn.execute("SELECT count(*) FROM events WHERE user_id=? AND kind='device.wipe'",(accounts['first'],)).fetchone()[0]==1
    assert client.post('/api/v1/admin/users/'+accounts['first']+'/unblock',headers=headers(sessions['admin'])).json()['status']=='approved'
    assert client.get('/api/v1/account/status',headers=headers(sessions['first'])).status_code==401 # no session resurrection

def test_delete_scrubs_personal_data_and_history_but_offline_token_can_wipe(service):
    app,client,accounts,sessions=service;rid,tid,path=media(app,accounts['first'])
    uid=accounts['first'];now=utcnow()
    with app.state.db.transaction() as conn:
        conn.execute("INSERT INTO settings VALUES ('user',?,'personal','\\\"email@example.com\\\"',?)",(uid,now))
        conn.execute('INSERT INTO audit(actor_user_id,action,target_id,detail_json,created_at) VALUES (?,\'old\',?,\'{"email":"first@example.com"}\',?)',(uid,uid,now))
    response=client.delete('/api/v1/admin/users/'+uid,headers=headers(sessions['admin']))
    assert response.json()['status']=='deleted' and not path.exists()
    assert client.get('/api/v1/account/status',headers=headers(sessions['first'])).json()['detail']=={'code':'account_unavailable','wipe_local':True}
    with app.state.db.transaction() as conn:
        user=conn.execute('SELECT * FROM users WHERE id=?',(uid,)).fetchone()
        assert user['email'].endswith('@deleted.invalid') and user['password_hash']=='disabled' and user['email_verified_at'] is None
        assert conn.execute('SELECT count(*) FROM resources WHERE user_id=?',(uid,)).fetchone()[0]==0
        assert conn.execute('SELECT count(*) FROM tasks WHERE user_id=?',(uid,)).fetchone()[0]==0
        assert conn.execute("SELECT count(*) FROM settings WHERE scope='user' AND owner_id=?",(uid,)).fetchone()[0]==0
        assert conn.execute('SELECT name FROM devices WHERE user_id=?',(uid,)).fetchone()[0]=='Deleted device'
        assert all(row[0]=='{}' for row in conn.execute('SELECT detail_json FROM audit WHERE actor_user_id=?',(uid,)))
    assert client.post('/api/v1/admin/users/'+uid+'/unblock',headers=headers(sessions['admin'])).status_code==409
    assert client.delete('/api/v1/admin/users/'+uid,headers=headers(sessions['admin'])).status_code==200

def test_cleanup_failure_is_durable_and_retry_blocks_unblock(service,monkeypatch):
    app,client,accounts,sessions=service;_,_,path=media(app,accounts['first'])
    import tubego_server.account_cleanup as cleanup
    original=cleanup.remove_owned_directory
    def failure(*args,**kwargs):raise PermissionError('disk temporarily inaccessible')
    monkeypatch.setattr(cleanup,'remove_owned_directory',failure)
    response=client.post('/api/v1/admin/users/'+accounts['first']+'/block',headers=headers(sessions['admin']))
    assert response.json()['cleanup_pending'] and path.exists()
    assert client.post('/api/v1/admin/users/'+accounts['first']+'/unblock',headers=headers(sessions['admin'])).status_code==409
    with app.state.db.transaction() as conn:assert conn.execute('SELECT value_json FROM settings WHERE scope=?',(SCOPE,)).fetchone()
    monkeypatch.setattr(cleanup,'remove_owned_directory',original)
    response=client.post('/api/v1/admin/users/'+accounts['first']+'/cleanup/retry',headers=headers(sessions['admin']))
    assert not response.json()['cleanup_pending'] and not path.exists()

@pytest.mark.parametrize("worker_status",["running","paused"])
def test_running_worker_lease_delays_cleanup_until_cancel_acknowledged(service,worker_status):
    app,client,accounts,sessions=service;_,tid,path=media(app,accounts['first'])
    with app.state.db.transaction() as conn:
        conn.execute("UPDATE tasks SET status=? WHERE id=?",(worker_status,tid))
        conn.execute("INSERT INTO settings VALUES ('global','','scheduler_lease',?,?)",(json.dumps({'task_id':tid,'token':'claim'}),utcnow()))
    response=client.post('/api/v1/admin/users/'+accounts['first']+'/block',headers=headers(sessions['admin']))
    assert response.json()['cleanup_pending'] and path.exists()
    with app.state.db.transaction() as conn:conn.execute("DELETE FROM settings WHERE scope='global' AND key='scheduler_lease'")
    process(app.state.db,app.state.settings.data_dir/'media')
    assert not path.exists()

def test_symlinks_and_foreign_references_never_delete_other_files(service):
    app,client,accounts,sessions=service;_,_,foreign=media(app,accounts['second'])
    directory=app.state.settings.data_dir/'media'/accounts['first'];directory.symlink_to(foreign.parent,target_is_directory=True)
    response=client.post('/api/v1/admin/users/'+accounts['first']+'/block',headers=headers(sessions['admin']))
    assert not response.json()['cleanup_pending'] and foreign.exists() and not directory.exists()

def test_roles_self_and_unverified_reactivation(service):
    app,client,accounts,sessions=service
    assert client.get('/api/v1/admin/users',headers=headers(sessions['first'])).status_code==403
    assert client.post('/api/v1/admin/users/'+accounts['admin']+'/block',headers=headers(sessions['admin'])).status_code==409
    assert client.delete('/api/v1/admin/users/'+accounts['admin'],headers=headers(sessions['admin'])).status_code==409
    assert client.delete('/api/v1/admin/users/missing',headers=headers(sessions['admin'])).status_code==404
    with app.state.db.transaction() as conn:conn.execute('UPDATE users SET email_verified_at=NULL WHERE id=?',(accounts['first'],))
    client.post('/api/v1/admin/users/'+accounts['first']+'/block',headers=headers(sessions['admin']))
    assert client.post('/api/v1/admin/users/'+accounts['first']+'/unblock',headers=headers(sessions['admin'])).json()['status']=='pending_verification'

def test_stale_administrator_principal_cannot_mutate_after_session_revocation(service):
    app,client,accounts,sessions=service
    with app.state.db.transaction() as conn:
        user=dict(conn.execute('SELECT * FROM users WHERE id=?',(accounts['admin'],)).fetchone())
        user['session_id']=conn.execute('SELECT id FROM sessions WHERE token_hash=?',(token_hash(sessions['admin']['token']),)).fetchone()[0]
        conn.execute('UPDATE sessions SET revoked_at=? WHERE id=?',(utcnow(),user['session_id']))
    app.dependency_overrides[require_admin]=lambda:user
    assert client.post('/api/v1/admin/users/'+accounts['first']+'/block').status_code==403

def test_foreign_database_path_is_reported_not_deleted(service):
    app,client,accounts,sessions=service
    rid,_,owned=media(app,accounts['first']);_,_,foreign=media(app,accounts['second'])
    with app.state.db.transaction() as conn:conn.execute('UPDATE resources SET server_path=? WHERE id=?',(str(foreign),rid))
    response=client.post('/api/v1/admin/users/'+accounts['first']+'/block',headers=headers(sessions['admin']))
    assert response.json()['cleanup_pending'] and response.json()['cleanup_error']=='cleanup_failed'
    assert foreign.exists() and not owned.exists()

def test_missing_media_is_already_clean_and_block_is_idempotent(service):
    app,client,accounts,sessions=service;_,_,path=media(app,accounts['first']);path.unlink()
    for _ in range(2):assert client.post('/api/v1/admin/users/'+accounts['first']+'/block',headers=headers(sessions['admin'])).json()['cleanup_pending'] is False
    with app.state.db.transaction() as conn:assert conn.execute("SELECT count(*) FROM audit WHERE action='account.blocked' AND target_id=?",(accounts['first'],)).fetchone()[0]==1

def test_cleanup_serializes_unblock_across_database_connections(service,monkeypatch):
    import sqlite3
    import tubego_server.account_cleanup as cleanup
    app,client,accounts,sessions=service;_,_,path=media(app,accounts['first'])
    original=cleanup.remove_owned_directory
    observed=[]
    def removal(root,owner,stop=None):
        other=app.state.db.connect();other.execute('PRAGMA busy_timeout=0')
        try:
            with pytest.raises(sqlite3.OperationalError,match='locked'):
                other.execute("UPDATE users SET status='approved' WHERE id=?",(owner,))
            observed.append(owner)
        finally:other.close()
        original(root,owner,stop)
    monkeypatch.setattr(cleanup,'remove_owned_directory',removal)
    response=client.post('/api/v1/admin/users/'+accounts['first']+'/block',headers=headers(sessions['admin']))
    assert response.status_code==200 and observed==[accounts['first']] and not path.exists()
    assert client.post('/api/v1/admin/users/'+accounts['first']+'/unblock',headers=headers(sessions['admin'])).status_code==200
