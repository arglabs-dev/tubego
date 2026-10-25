from contextlib import closing
from datetime import datetime,timezone
import uuid
from test_private_library import service,owner,media


def test_history_pagination_latest_task_and_no_false_old_failure(service):
    app,client=service;alice=owner(app);bob=owner(app)
    resources=[media(app,alice,f'Lesson {index:03}',f'2026-01-{index+1:02}')[0] for index in range(65)]
    media(app,bob,'Secret')
    with app.state.db.transaction() as conn:
        rid=resources[0];conn.execute("UPDATE tasks SET status='failed' WHERE id=?",(rid,))
        conn.execute("INSERT INTO tasks(id,user_id,resource_id,status,created_at,updated_at) VALUES(?,?,?,'queued','2027','2027')",(str(uuid.uuid4()),alice['id'],rid))
        conn.execute("UPDATE resources SET server_deleted_at='gone' WHERE id=?",(resources[1],))
    first=client.get('/api/v1/resources?order=newest&limit=40',headers=alice['headers']).json()
    second=client.get('/api/v1/resources',params={'order':'newest','limit':40,'cursor':first['next_cursor']},headers=alice['headers']).json()
    assert len(first['items'])==40 and len(second['items'])==25 and second['next_cursor'] is None
    assert {row['id'] for row in first['items']+second['items']}==set(resources)
    assert resources[0] not in [r['id'] for r in client.get('/api/v1/resources?status=error',headers=alice['headers']).json()['items']]
    detail=client.get('/api/v1/resources/'+resources[0],headers=alice['headers']).json()
    assert detail['latest_task']['status']=='queued' and detail['device_delivery']['status']=='pending'
    assert 'device_id' not in str(detail) and 'server_path' not in str(detail)
    assert client.get('/api/v1/resources/'+resources[1],headers=alice['headers']).json()['server_available'] is False


def test_private_device_downloaded_filter_and_priority_across_devices(service):
    app,client=service;alice=owner(app);bob=owner(app,role='admin');rid,_=media(app,alice);foreign,_=media(app,bob)
    with app.state.db.transaction() as conn:conn.execute("UPDATE deliveries SET status='complete' WHERE resource_id=?",(rid,))
    assert [r['id'] for r in client.get('/api/v1/resources?status=downloaded',headers=alice['headers']).json()['items']]==[rid]
    assert client.post('/api/v1/resources/'+rid+'/priority',headers=bob['headers'],json={}).status_code==404
    assert client.post('/api/v1/resources/'+foreign+'/priority',headers=alice['headers'],json={}).status_code==404
    request=str(uuid.uuid4());first=client.post('/api/v1/resources/'+rid+'/priority',headers=alice['headers'],json={'request_id':request}).json()
    assert client.post('/api/v1/resources/'+rid+'/priority',headers=alice['headers'],json={'request_id':request}).json()==first
    second_rid,_=media(app,alice)
    assert client.post('/api/v1/resources/'+second_rid+'/priority',headers=alice['headers'],json={'request_id':request}).status_code==409
    # A second active session of the same owner receives the same priority in sync.
    from tubego_server.auth import token_hash
    from datetime import timedelta
    device=str(uuid.uuid4());token=str(uuid.uuid4());now=datetime.now(timezone.utc).isoformat()
    with app.state.db.transaction() as conn:
        conn.execute('INSERT INTO devices(id,user_id,name,created_at) VALUES(?,?,?,?)',(device,alice['id'],'tablet',now))
        conn.execute('INSERT INTO sessions VALUES(?,?,?,?,?,NULL,?)',(str(uuid.uuid4()),alice['id'],device,token_hash(token),(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat(),now))
        conn.execute("INSERT INTO deliveries(resource_id,device_id,updated_at) VALUES(?,?,'now')",(rid,device))
    second_headers={'Authorization':'Bearer '+token}
    assert client.get('/api/v1/device/sync',headers=second_headers).json()['deliveries'][0]['priority']==first['priority']
    assert client.get('/api/v1/device/queue-order',headers=second_headers).json()['items'][0]['priority']==first['priority']
    assert client.get('/api/v1/resources?status=downloaded',headers=second_headers).json()['items']==[]


def test_priority_is_next_but_never_mutates_running_and_queue_pages(service):
    app,client=service;alice=owner(app);a,_=media(app,alice);b,_=media(app,alice)
    with app.state.db.transaction() as conn:conn.execute("UPDATE tasks SET status='running',priority=50 WHERE id=?",(a,))
    result=client.post('/api/v1/resources/'+b+'/priority',headers=alice['headers'],json={}).json()
    assert result['priority']==51
    with closing(app.state.db.connect()) as conn:
        assert tuple(conn.execute('SELECT status,priority FROM tasks WHERE id=?',(a,)).fetchone())==('running',50)
        assert conn.execute('SELECT priority FROM tasks WHERE id=?',(b,)).fetchone()[0]==51
    first=client.get('/api/v1/device/queue-order?limit=1',headers=alice['headers']).json()
    second=client.get('/api/v1/device/queue-order',params={'limit':1,'cursor':first['next_cursor']},headers=alice['headers']).json()
    assert {row['id'] for row in first['items']+second['items']}=={a,b}
    assert second['next_cursor'] is None


def test_opened_is_private_monotonic_and_revocation_blocks_mutation(service):
    app,client=service;alice=owner(app);admin=owner(app,role='admin');rid,_=media(app,alice)
    path='/api/v1/resources/'+rid+'/opened'
    assert client.post(path,headers=admin['headers'],json={'opened_at':'2026-01-02T12:00:00Z'}).status_code==404
    assert client.post(path,headers=alice['headers'],json={'opened_at':'2026-01-02T12:00:00Z'}).status_code==200
    client.post(path,headers=alice['headers'],json={'opened_at':'2026-01-01T12:00:00Z'})
    assert client.get('/api/v1/resources/'+rid,headers=alice['headers']).json()['last_opened_at'].startswith('2026-01-02')
    with app.state.db.transaction() as conn:conn.execute("UPDATE sessions SET revoked_at='now' WHERE user_id=?",(alice['id'],))
    assert client.post(path,headers=alice['headers'],json={'opened_at':'2026-01-03T12:00:00Z'}).status_code==401
