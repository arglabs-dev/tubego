from contextlib import closing
from datetime import datetime,timezone,timedelta
import uuid
import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from tubego_server.main import create_app
from tubego_server.config import Settings
from tubego_server.auth import token_hash,utcnow
from tubego_server.ownership import LibraryScope

@pytest.fixture
def service(tmp_path):
    app=create_app(Settings(tmp_path))
    with TestClient(app) as client:
        yield app,client


def owner(app,role='user',status='approved',verified=True):
    uid=uuid.uuid4().hex; device=uuid.uuid4().hex; session=uuid.uuid4().hex; token=uuid.uuid4().hex; now=utcnow()
    with app.state.db.transaction() as conn:
        conn.execute('INSERT INTO users VALUES(?,?,?,?,?,?,?,?)',(uid,uid+'@example.com','hash',role,status,now if verified else None,now,now))
        conn.execute("INSERT INTO devices(id,user_id,name,created_at) VALUES(?,?,?,?)",(device,uid,'phone',now))
        conn.execute('INSERT INTO sessions VALUES(?,?,?,?,?,NULL,?)',(session,uid,device,token_hash(token),(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat(),now))
    return {'id':uid,'role':role,'status':status,'email_verified_at':now if verified else None,'device':device,'session':session,'headers':{'Authorization':'Bearer '+token}}


def media(app,user,title='Lesson',created='2026-01-01'):
    key=uuid.uuid4().hex; root=app.state.settings.data_dir / 'media';root.mkdir(exist_ok=True)
    filename=key+'.mp4'; (root / filename).write_bytes(b'0123456789')
    with app.state.db.transaction() as conn:
        conn.execute('INSERT INTO resources(id,user_id,source_url,title,server_path,size_bytes,ready_at,created_at,updated_at) VALUES(?,?,?,?,?,10,?,?,?)',(key,user['id'],'https://example.com/'+key,title,filename,created,created,created))
        conn.execute("INSERT INTO tasks(id,user_id,resource_id,created_at,updated_at) VALUES(?,?,?,?,'now')",(key,user['id'],key,created))
        conn.execute("INSERT INTO deliveries(resource_id,device_id,updated_at) VALUES(?,?,'now')",(key,user['device']))
        event=conn.execute("INSERT INTO events(user_id,device_id,kind,payload_json,created_at) VALUES(?,?,'ready','{}','now')",(user['id'],user['device'])).lastrowid
        conn.execute("INSERT INTO commands(id,user_id,device_id,kind,payload_json,created_at,updated_at) VALUES(?,?,?,'test','{}','now','now')",(key,user['id'],user['device']))
    return key,event


def test_private_resources_no_admin_bypass_or_path_disclosure(service):
    app,client=service; alice=owner(app);bob=owner(app,role='admin')
    aid,_=media(app,alice,'Private Alice');bid,_=media(app,bob,'Private Bob')
    for user, own, foreign in [(alice,aid,bid),(bob,bid,aid)]:
        listing=client.get('/api/v1/resources',headers=user['headers'])
        assert listing.status_code==200
        assert [x['id'] for x in listing.json()['items']]==[own]
        assert 'server_path' not in listing.text and 'user_id' not in listing.text
        assert client.get('/api/v1/resources/'+own,headers=user['headers']).status_code==200
        for suffix in ('',):
            stranger=client.get('/api/v1/resources/'+foreign+suffix,headers=user['headers'])
            unknown=client.get('/api/v1/resources/absent'+suffix,headers=user['headers'])
            assert stranger.status_code==unknown.status_code==404 and stranger.json()==unknown.json()
    assert client.get('/api/v1/resources').status_code==401


@pytest.mark.parametrize('change',["UPDATE sessions SET revoked_at='now'","UPDATE sessions SET expires_at='2000-01-01'","UPDATE devices SET revoked_at='now'","UPDATE users SET status='blocked'","UPDATE users SET email_verified_at=NULL","UPDATE users SET status='pending_approval'"])
def test_each_call_rechecks_session_and_approval(service,change):
    app,client=service;alice=owner(app);key,_=media(app,alice)
    assert client.get('/api/v1/resources/'+key,headers=alice['headers']).status_code==200
    with app.state.db.transaction() as conn:conn.execute(change)
    for path in ('/api/v1/resources','/api/v1/resources/'+key):
        assert client.get(path,headers=alice['headers']).status_code in (401,403)


def test_pagination_literal_search_and_status_filters_do_not_leak(service):
    app,client=service;alice=owner(app);bob=owner(app)
    keys=[media(app,alice,title,created='same')[0] for title in ('Lesson 100%','Lesson B','Lesson C')]
    media(app,bob,'Lesson 100%')
    first=client.get('/api/v1/resources',params={'limit':2},headers=alice['headers']).json()
    second=client.get('/api/v1/resources',params={'limit':2,'cursor':first['next_cursor']},headers=alice['headers']).json()
    assert len(first['items'])==2 and len(second['items'])==1 and second['next_cursor'] is None
    assert {x['id'] for x in first['items']+second['items']}==set(keys)
    assert len(client.get('/api/v1/resources',params={'search':'100%'},headers=alice['headers']).json()['items'])==1
    assert client.get('/api/v1/resources',params={'cursor':'invalid'},headers=alice['headers']).status_code==400
    with app.state.db.transaction() as conn:
        conn.execute("UPDATE resources SET server_deleted_at='now' WHERE id=?",(keys[0],))
        conn.execute("UPDATE tasks SET status='failed' WHERE id=?",(keys[1],))
    assert [x['id'] for x in client.get('/api/v1/resources?status=deleted',headers=alice['headers']).json()['items']]==[keys[0]]
    assert [x['id'] for x in client.get('/api/v1/resources?status=failed',headers=alice['headers']).json()['items']]==[keys[1]]


def test_owner_helpers_cover_all_private_rows_and_mismatched_joins(service):
    app,_=service;alice=owner(app);bob=owner(app,role='admin');akey,ae=media(app,alice);bkey,be=media(app,bob)
    with app.state.db.transaction() as conn:
        scope=LibraryScope(conn,alice)
        assert scope.task(akey)['id']==akey
        assert scope.device(alice['device'])['id']==alice['device']
        assert scope.session(alice['session'])['id']==alice['session']
        assert scope.event(ae)['id']==ae
        assert scope.command(akey)['id']==akey
        assert scope.delivery(akey,alice['device'])['resource_id']==akey
        foreign=[lambda:scope.resource(bkey),lambda:scope.task(bkey),lambda:scope.device(bob['device']),lambda:scope.session(bob['session']),lambda:scope.event(be),lambda:scope.command(bkey),lambda:scope.delivery(bkey,bob['device']),lambda:scope.delivery(akey,bob['device'])]
        for call in foreign:
            with pytest.raises(HTTPException) as error:call()
            assert error.value.status_code==404
        conn.execute('UPDATE tasks SET resource_id=? WHERE id=?',(bkey,akey))
        conn.execute('UPDATE sessions SET device_id=? WHERE id=?',(bob['device'],alice['session']))
        conn.execute('UPDATE events SET device_id=? WHERE id=?',(bob['device'],ae))
        conn.execute('UPDATE commands SET device_id=? WHERE id=?',(bob['device'],akey))
        for call in (lambda:scope.task(akey),lambda:scope.session(alice['session']),lambda:scope.event(ae),lambda:scope.command(akey)):
            with pytest.raises(HTTPException):call()
    with closing(app.state.db.connect()) as conn:
        with pytest.raises(HTTPException):LibraryScope(conn,{**alice,'status':'blocked'})


def test_administrator_audit_helper_and_registration_role_gate(service):
    from tubego_server.auditing import audit_admin
    app,client=service;alice=owner(app);admin=owner(app,role='admin')
    with app.state.db.transaction() as conn:
        with pytest.raises(HTTPException):audit_admin(conn,alice,'global.delete')
        audit_admin(conn,admin,'configuration.changed',alice['id'],{'setting':'retention'})
    with closing(app.state.db.connect()) as conn:
        rows=conn.execute('SELECT * FROM audit').fetchall()
        assert len(rows)==1 and rows[0]['actor_user_id']==admin['id']
    class Mail:
        def send_verification(self,*args):pass
    app.state.mailer=Mail()
    assert client.post('/api/v1/auth/register',json={'email':'public@example.com','password':'correct horse battery','role':'admin','status':'approved'}).status_code==202
    with closing(app.state.db.connect()) as conn:
        row=conn.execute("SELECT role,status FROM users WHERE email='public@example.com'").fetchone()
        assert row['role']=='user' and row['status']=='pending_verification'

def test_local_bootstrap_creates_only_first_admin(service,monkeypatch):
    from tubego_server import bootstrap_admin
    import sys
    app,_=service
    monkeypatch.setenv('TUBEGO_DATA_DIR',str(app.state.settings.data_dir))
    monkeypatch.setenv('TUBEGO_BOOTSTRAP_PASSWORD','correct horse battery')
    monkeypatch.setattr(sys,'argv',['bootstrap_admin','admin@example.com'])
    bootstrap_admin.main()
    with pytest.raises(SystemExit):bootstrap_admin.main()
    with closing(app.state.db.connect()) as conn:
        admins=conn.execute("SELECT * FROM users WHERE role='admin'").fetchall()
        assert len(admins)==1 and admins[0]['status']=='approved' and admins[0]['email_verified_at']
        assert conn.execute("SELECT count(*) FROM audit WHERE action='admin.bootstrap'").fetchone()[0]==1
