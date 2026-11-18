from datetime import datetime, timezone
from uuid import uuid4
import pytest
from test_private_library import service, owner, media


def snapshot(position=20000, duration=100000, stamp=1000, **changes):
    return dict(position_ms=position, duration_ms=duration, position_at_ms=stamp,
                position_id=str(uuid4()), ranges=[], ended=False) | changes


def test_latest_position_not_max_and_replay_is_idempotent(service):
    app, client=service; user=owner(app); rid,_=media(app,user)
    path=f'/api/v1/resources/{rid}/playback'
    first=snapshot(80000,stamp=1000);first['ranges']=[[0,10000]]
    assert client.put(path,headers=user['headers'],json=first).json()['status']=='in_progress'
    second=snapshot(20000,stamp=2000);second['ranges']=[[5000,15000]]
    assert client.put(path,headers=user['headers'],json=second).json()['position_ms']==20000
    replay=client.put(path,headers=user['headers'],json=first).json()
    assert replay['position_ms']==20000 and replay['ranges']==[[0,15000]]
    assert client.put(path,headers=user['headers'],json=second).json()==replay
    assert client.get('/api/v1/resources/'+rid,headers=user['headers']).json()['playback']==replay


def test_seek_to_end_does_not_finish_and_manual_unseen_wins(service):
    app, client=service; user=owner(app); rid,_=media(app,user);path=f'/api/v1/resources/{rid}/playback'
    jump=snapshot(99999,ended=True)
    assert client.put(path,headers=user['headers'],json=jump).json()['status']=='in_progress'
    watched=snapshot(99999,stamp=2000,ended=True);watched['ranges']=[[0,96000]]
    assert client.put(path,headers=user['headers'],json=watched).json()['status']=='finished'
    unseen=snapshot(99999,stamp=2000,manual_seen=False,manual_at_ms=3000,manual_id=str(uuid4()))
    assert client.put(path,headers=user['headers'],json=unseen).json()['status']=='in_progress'
    assert client.put(path,headers=user['headers'],json=watched).json()['status']=='in_progress'
    manual=snapshot(0,stamp=4000,manual_seen=True,manual_at_ms=4000,manual_id=str(uuid4()))
    assert client.put(path,headers=user['headers'],json=manual).json()['status']=='finished'


def test_private_sync_between_devices_and_no_admin_bypass(service):
    app, client=service; user=owner(app); other=owner(app,role='admin');rid,_=media(app,user)
    path=f'/api/v1/resources/{rid}/playback'
    assert client.put(path,headers=other['headers'],json=snapshot()).status_code==404
    assert client.get('/api/v1/playback',headers=other['headers']).json()['items']==[]
    assert client.put(path,headers=user['headers'],json=snapshot()).status_code==200
    # The progress belongs to the user/resource, not a particular device.
    from tubego_server.auth import token_hash,utcnow
    from datetime import timedelta
    token=str(uuid4());device=str(uuid4());now=utcnow()
    with app.state.db.transaction() as conn:
        conn.execute('INSERT INTO devices(id,user_id,name,created_at) VALUES(?,?,?,?)',(device,user['id'],'second',now))
        conn.execute('INSERT INTO sessions VALUES(?,?,?,?,?,NULL,?)',(str(uuid4()),user['id'],device,token_hash(token),(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat(),now))
    response=client.get('/api/v1/playback',headers={'Authorization':'Bearer '+token})
    assert response.headers['cache-control']=='no-store'
    assert response.json()['items'][0]['position_ms']==20000
    assert client.get('/api/v1/playback').status_code==401


@pytest.mark.parametrize('patch',[
    {'position_ms':-1},{'position_ms':100001},{'duration_ms':0},
    {'ranges':[[0,True]]},{'ranges':[[0,1.5]]},{'ranges':[[200,100]]},
    {'ranges':[[0,100001]]},{'manual_seen':False},
    {'position_at_ms':9999999999999}, {'unexpected':1},
    {'position_at_ms':0},{'position_id':'00000000-0000-0000-0000-000000000000'},
])
def test_invalid_snapshots_are_rejected(service,patch):
    app,client=service;user=owner(app);rid,_=media(app,user);body=snapshot();body.update(patch)
    assert client.put(f'/api/v1/resources/{rid}/playback',headers=user['headers'],json=body).status_code==422


def test_manual_seen_without_opening_and_paginated_sync(service):
    app,client=service;user=owner(app);ids=[media(app,user)[0] for _ in range(3)]
    for rid in ids:
        body={'manual_seen':True,'manual_at_ms':2000,'manual_id':str(uuid4())}
        response=client.put(f'/api/v1/resources/{rid}/playback',headers=user['headers'],json=body)
        assert response.status_code==200 and response.json()['status']=='finished'
        assert response.json()['position_ms']==0
    first=client.get('/api/v1/playback?limit=2',headers=user['headers']).json()
    second=client.get('/api/v1/playback',params={'limit':2,'after':first['next_cursor']},headers=user['headers']).json()
    assert len(first['items'])==2 and len(second['items'])==1 and second['next_cursor'] is None
    assert {i['resource_id'] for i in first['items']+second['items']}==set(ids)
