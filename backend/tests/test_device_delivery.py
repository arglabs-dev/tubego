import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from datetime import datetime,timezone,timedelta
import pytest
from fastapi import HTTPException
from test_private_library import service,owner,media
from tubego_server.delivery import publish_ready,device_scope,transfer_allowed
from tubego_server.auth import token_hash,utcnow


def add_device(app,user,revoked=False):
    did=uuid.uuid4().hex;sid=uuid.uuid4().hex;token=uuid.uuid4().hex
    with app.state.db.transaction() as conn:
        conn.execute("INSERT INTO devices(id,user_id,name,revoked_at,created_at) VALUES(?,?,'other',?,?)",(did,user['id'],utcnow() if revoked else None,utcnow()))
        conn.execute('INSERT INTO sessions VALUES(?,?,?,?,?,NULL,?)',(sid,user['id'],did,token_hash(token),(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat(),utcnow()))
    return {'id':did,'headers':{'Authorization':'Bearer '+token},'session_id':sid}


def ready(app,user):
    key,event=media(app,user)
    return key,publish_ready(app.state.db,app.state.settings.data_dir/'media',key,key+'.mp4')


def test_publish_all_active_devices_not_foreign_revoked_or_new_historical(service):
    app,client=service;alice=owner(app);bob=owner(app);other=add_device(app,alice);revoked=add_device(app,alice,True)
    key,published=ready(app,alice)
    assert set(published['recipients'])=={alice['device'],other['id']}
    newly_added=add_device(app,alice)
    with app.state.db.transaction() as conn:
        assert {r['device_id'] for r in conn.execute('SELECT * FROM deliveries WHERE resource_id=?',(key,))}=={alice['device'],other['id']}
    for device in (alice,other):
        data=client.get('/api/v1/device/sync',headers=device['headers']).json()
        assert len(data['deliveries'])==1 and data['deliveries'][0]['delivery_status']=='pending'
        assert data['deliveries'][0]['server_available']
        assert data['deliveries'][0]['sha256']==published['sha256']
        notifications=[e for e in data['events'] if e['kind']=='resource_available']
        assert len(notifications)==1 and notifications[0]['payload']['silent']
    publish_ready(app.state.db,app.state.settings.data_dir/'media',key,key+'.mp4')
    assert client.get('/api/v1/device/sync',headers=newly_added['headers']).json()['deliveries']==[]
    assert client.get('/api/v1/device/sync',headers=bob['headers']).json()['deliveries']==[]
    assert client.get('/api/v1/device/sync',headers=revoked['headers']).status_code==401
    assert client.post(f'/api/v1/resources/{key}/deliveries/request',json={},headers=newly_added['headers']).json()['status']=='pending'


def test_auth_owner_device_ranges_and_checksum_metadata(service):
    app,client=service;alice=owner(app);bob=owner(app,role='admin');key,published=ready(app,alice)
    url=f'/api/v1/resources/{key}/download'
    assert client.get(url).status_code==401
    assert client.get(url,headers=bob['headers']).status_code==404
    assert client.head(url,headers=bob['headers']).status_code==404
    new=add_device(app,alice)
    assert client.get(url,headers=new['headers']).status_code==404
    response=client.get(url,headers=alice['headers'])
    assert response.content==b'0123456789' and response.headers['x-content-sha256']==published['sha256']
    assert response.headers['cache-control']=='no-store'
    part=client.get(url,headers={**alice['headers'],'Range':'bytes=3-5','If-Range':response.headers['etag']})
    assert part.status_code==206 and part.content==b'345' and part.headers['content-range']=='bytes 3-5/10'
    assert client.get(url,headers={**alice['headers'],'Range':'bytes=-2'}).content==b'89'
    assert client.get(url,headers={**alice['headers'],'Range':'bytes=4-','If-Range':'"old"'}).content==b'0123456789'
    assert client.get(url,headers={**alice['headers'],'Range':'bytes=99-'}).status_code==416
    head=client.head(url,headers=alice['headers']);assert head.content==b'' and head.headers['content-length']=='10'


def test_confirmation_only_matching_complete_size_digest_idempotent_no_alert(service):
    app,client=service;alice=owner(app);key,published=ready(app,alice)
    url=f'/api/v1/resources/{key}/deliveries/confirm'
    for body in ({'size_bytes':9,'sha256':published['sha256']},{'size_bytes':10,'sha256':'0'*64}):
        assert client.post(url,json=body,headers=alice['headers']).status_code==409
    assert client.post(url,json={'size_bytes':10,'sha256':'not a digest'},headers=alice['headers']).status_code==422
    body={'size_bytes':10,'sha256':published['sha256']}
    first=client.post(url,json=body,headers=alice['headers']);assert first.status_code==200
    with app.state.db.transaction() as conn:
        delivered=conn.execute('SELECT first_delivered_at FROM resources WHERE id=?',(key,)).fetchone()[0]
        confirmed=conn.execute('SELECT confirmed_at FROM deliveries WHERE resource_id=?',(key,)).fetchone()[0]
        event_count=conn.execute('SELECT count(*) FROM events').fetchone()[0]
    assert client.post(url,json=body,headers=alice['headers']).json()==first.json()
    with app.state.db.transaction() as conn:
        assert conn.execute('SELECT first_delivered_at FROM resources WHERE id=?',(key,)).fetchone()[0]==delivered
        assert conn.execute('SELECT confirmed_at FROM deliveries WHERE resource_id=?',(key,)).fetchone()[0]==confirmed
        assert conn.execute('SELECT count(*) FROM events').fetchone()[0]==event_count
    # Republish the same immutable file never schedules a duplicate complete transfer.
    assert publish_ready(app.state.db,app.state.settings.data_dir/'media',key,key+'.mp4')['recipients']==[]
    assert client.post(f'/api/v1/resources/{key}/deliveries/request',json={},headers=alice['headers']).json()['status']=='complete'
    assert client.post(f'/api/v1/resources/{key}/deliveries/request',json={'restore_missing':True},headers=alice['headers']).json()['status']=='pending'
    with app.state.db.transaction() as conn:
        assert conn.execute('SELECT downloaded_bytes FROM deliveries WHERE resource_id=?',(key,)).fetchone()[0]==0

@pytest.mark.parametrize('change',["UPDATE sessions SET revoked_at='now'","UPDATE devices SET revoked_at='now'","UPDATE users SET status='blocked'","UPDATE resources SET server_deleted_at='now'","UPDATE deliveries SET deleted_at='now'"])
def test_open_transfer_checks_revocation_between_chunks(service,change):
    app,client=service;alice=owner(app);key,published=ready(app,alice)
    principal={'id':alice['id'],'session_id':alice['session']}
    assert transfer_allowed(app.state.db,principal,key)
    with app.state.db.transaction() as conn:conn.execute(change)
    assert not transfer_allowed(app.state.db,principal,key)


def test_changed_file_requires_new_checksum_and_local_tombstone_needs_approval(service):
    app,client=service;alice=owner(app);key,published=ready(app,alice)
    url=f'/api/v1/resources/{key}/deliveries/confirm';body={'size_bytes':10,'sha256':published['sha256']}
    assert client.post(url,json=body,headers=alice['headers']).status_code==200
    # Simulate atomic worker replacement after a new generation of this resource.
    root=app.state.settings.data_dir/'media';(root/(key+'.mp4')).write_bytes(b'new media content')
    changed=publish_ready(app.state.db,root,key,key+'.mp4')
    assert changed['sha256']!=published['sha256']
    assert client.post(url,json=body,headers=alice['headers']).status_code==409
    with app.state.db.transaction() as conn:
        conn.execute("UPDATE deliveries SET status='deleted',deleted_at='now' WHERE resource_id=?",(key,))
    publish_ready(app.state.db,root,key,key+'.mp4')
    assert client.get(f'/api/v1/resources/{key}/download',headers=alice['headers']).status_code==409
    request=f'/api/v1/resources/{key}/deliveries/request'
    assert client.post(request,json={},headers=alice['headers']).status_code==409
    assert client.post(request,json={'approve_redownload':True},headers=alice['headers']).status_code==200
    assert client.get(f'/api/v1/resources/{key}/download',headers=alice['headers']).content==b'new media content'

@pytest.mark.parametrize('change',["UPDATE sessions SET revoked_at='now'","UPDATE sessions SET expires_at='2000-01-01'","UPDATE devices SET revoked_at='now'","UPDATE users SET status='blocked'","UPDATE users SET email_verified_at=NULL"])
def test_revocation_prevents_every_device_operation(service,change):
    app,client=service;alice=owner(app);key,published=ready(app,alice)
    with app.state.db.transaction() as conn:conn.execute(change)
    for method,path,body in [('GET','/device/sync',None),('GET',f'/resources/{key}/download',None),('POST',f'/resources/{key}/deliveries/request',{}),('POST',f'/resources/{key}/deliveries/confirm',{'size_bytes':10,'sha256':published['sha256']})]:
        response=client.request(method,'/api/v1'+path,json=body,headers=alice['headers'])
        assert response.status_code in (401,403)
    with app.state.db.transaction() as conn:
        assert conn.execute('SELECT first_delivered_at FROM resources WHERE id=?',(key,)).fetchone()[0] is None


def test_same_transaction_revalidation_rejects_foreign_device_or_stale_principal(service):
    app,_=service;alice=owner(app);bob=owner(app)
    principal={**alice,'session_id':alice['session']}
    with app.state.db.transaction() as conn:
        assert device_scope(conn,principal)[1]==alice['device']
        conn.execute('UPDATE sessions SET device_id=? WHERE id=?',(bob['device'],alice['session']))
        with pytest.raises(HTTPException):device_scope(conn,principal)
        conn.execute('UPDATE sessions SET device_id=? WHERE id=?',(alice['device'],alice['session']))
        conn.execute("UPDATE users SET status='blocked' WHERE id=?",(alice['id'],))
        with pytest.raises(HTTPException):device_scope(conn,principal)


def test_sync_pagination_reconnect_snapshot_no_data_loss_or_cross_device_events(service):
    app,client=service;alice=owner(app);bob=owner(app)
    keys={ready(app,alice)[0] for _ in range(5)};ready(app,bob)
    event_cursor=0;delivery_cursor='';seen=set();events=[]
    while True:
        data=client.get('/api/v1/device/sync',params={'limit':2,'event_cursor':event_cursor,'delivery_cursor':delivery_cursor},headers=alice['headers']).json()
        seen.update(x['id'] for x in data['deliveries']);events.extend(data['events'])
        event_cursor=data['event_cursor'];delivery_cursor=data['next_delivery_cursor']
        if not data['has_more_events'] and delivery_cursor is None:break
        # Delivery snapshots and event stream have independent continuation cursors.
        if delivery_cursor is None:delivery_cursor=max(keys)
    assert seen==keys and len({e['id'] for e in events})==len(events)
    assert {e['payload']['resource_id'] for e in events if e['kind']=='resource_available'}==keys
    # Events may already be consumed: snapshot remains the source of truth on reconnect.
    latest=client.get('/api/v1/device/sync',params={'event_cursor':event_cursor,'limit':100},headers=alice['headers']).json()
    assert latest['events']==[] and {x['id'] for x in latest['deliveries']}==keys


def test_publish_rechecks_owner_and_rejects_missing_file(service):
    app,_=service;alice=owner(app);key,_=media(app,alice)
    with app.state.db.transaction() as conn:conn.execute("UPDATE users SET status='blocked' WHERE id=?",(alice['id'],))
    with pytest.raises(HTTPException):publish_ready(app.state.db,app.state.settings.data_dir/'media',key,key+'.mp4')
    with pytest.raises(HTTPException):publish_ready(app.state.db,app.state.settings.data_dir/'media',key,'missing')

def test_concurrent_confirmation_is_atomic_and_server_deletion_blocks_confirmation(service):
    app,client=service;alice=owner(app);key,published=ready(app,alice)
    url=f'/api/v1/resources/{key}/deliveries/confirm';body={'size_bytes':10,'sha256':published['sha256']}
    with ThreadPoolExecutor(2) as pool:
        statuses=list(pool.map(lambda _:client.post(url,json=body,headers=alice['headers']).status_code,range(2)))
    assert statuses==[200,200]
    with app.state.db.transaction() as conn:
        assert conn.execute("SELECT count(*) FROM deliveries WHERE resource_id=? AND status='complete'",(key,)).fetchone()[0]==1
        first=conn.execute('SELECT first_delivered_at FROM resources WHERE id=?',(key,)).fetchone()[0]
        conn.execute("UPDATE resources SET server_deleted_at='now',server_path=NULL WHERE id=?",(key,))
    assert client.post(url,json=body,headers=alice['headers']).status_code==404
    with app.state.db.transaction() as conn:
        assert conn.execute('SELECT first_delivered_at FROM resources WHERE id=?',(key,)).fetchone()[0]==first
