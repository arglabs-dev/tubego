from datetime import datetime,timedelta,timezone
from test_private_library import service,owner
from test_device_delivery import ready,add_device
from test_retention import preserve,confirm
from tubego_server.delivery import read_setting,write_setting
from tubego_server.retention_policy import deadline,policy,sweep_expired,is_expired
import tubego_server.retention_policy as deadlines
import tubego_server.routers.retention as routes

NOW=datetime(2026,10,9,12,tzinfo=timezone.utc)


def age(app,key,hours,first_hours=None):
    with app.state.db.transaction() as conn:
        conn.execute('UPDATE resources SET ready_at=?,first_delivered_at=? WHERE id=?',((NOW-timedelta(hours=hours)).isoformat(),(NOW-timedelta(hours=first_hours)).isoformat() if first_hours is not None else None,key))


def freeze(monkeypatch):
    monkeypatch.setattr(deadlines,'clock',lambda:NOW)
    monkeypatch.setattr(routes,'clock',lambda:NOW)


def test_absolute_limit_and_first_delivery_minimum_are_server_utc():
    config={'absolute_hours':72,'delivery_hours':4}
    row={'ready_at':(NOW-timedelta(hours=71)).isoformat(),'first_delivered_at':NOW.isoformat()}
    assert deadline(row,config)==NOW+timedelta(hours=1)
    row['first_delivered_at']=(NOW-timedelta(hours=3)).isoformat()
    assert deadline(row,config)==NOW+timedelta(hours=1)
    row['ready_at']=(NOW-timedelta(hours=1)).isoformat()
    assert deadline(row,config)==NOW+timedelta(hours=1)
    assert deadline({'ready_at':'2026-01-01','first_delivered_at':None},config) is None


def test_sweeper_without_confirmation_and_first_confirmation_deadline(service,monkeypatch):
    freeze(monkeypatch);app,client=service;user=owner(app);add_device(app,user)
    absolute,pub=ready(app,user);age(app,absolute,72)
    delivered,pub2=ready(app,user);age(app,delivered,5,4)
    fresh,pub3=ready(app,user);age(app,fresh,71)
    kept,pub4=ready(app,user);other=owner(app);preserve(app,other);preserved,_=ready(app,other);age(app,preserved,1000)
    root=app.state.settings.data_dir/'media'
    assert sweep_expired(app.state.db,root)==2
    assert not (root/(absolute+'.mp4')).exists() and not (root/(delivered+'.mp4')).exists()
    assert (root/(fresh+'.mp4')).exists() and (root/(preserved+'.mp4')).exists()
    assert sweep_expired(app.state.db,root)==0
    with app.state.db.transaction() as conn:
        assert conn.execute('SELECT count(*) FROM resources').fetchone()[0]==5
        assert conn.execute('SELECT confirmed_at FROM deliveries WHERE resource_id=?',(absolute,)).fetchone()[0] is None


def test_admin_preview_required_and_immediate_impact_confirmed_retroactively(service,monkeypatch):
    freeze(monkeypatch);app,client=service;admin=owner(app,'admin');user=owner(app);key,pub=ready(app,user);age(app,key,20)
    base='/api/v1/admin/retention/deadlines';choice={'absolute_hours':12,'delivery_hours':4}
    assert client.get(base,headers=admin['headers']).json()['absolute_hours']==72
    assert client.post(base+'/preview',json=choice,headers=user['headers']).status_code==403
    assert client.put(base,json=dict(choice,preview_token='0'*32),headers=admin['headers']).status_code==409
    preview=client.post(base+'/preview',json=choice,headers=admin['headers']).json();assert preview['immediate_deletions']==1
    body=dict(choice,preview_token=preview['preview_token'])
    assert client.put(base,json=body,headers=admin['headers']).status_code==409
    result=client.put(base,json=dict(body,confirm_immediate_deletion=True),headers=admin['headers'])
    assert result.status_code==200 and result.json()['removed_server_copies']==1
    assert client.put(base,json=dict(body,confirm_immediate_deletion=True),headers=admin['headers']).status_code==409
    with app.state.db.transaction() as conn:assert policy(conn)==choice
    # Fresh connection (restart) reads persisted values; original ready date untouched.
    with app.state.db.transaction() as conn:
        assert conn.execute('SELECT ready_at FROM resources WHERE id=?',(key,)).fetchone()[0]==(NOW-timedelta(hours=20)).isoformat()


def test_preview_invalidated_by_new_impact_and_rejects_invalid_limits(service,monkeypatch):
    freeze(monkeypatch);app,client=service;admin=owner(app,'admin');user=owner(app)
    base='/api/v1/admin/retention/deadlines';choice={'absolute_hours':1,'delivery_hours':1}
    preview=client.post(base+'/preview',json=choice,headers=admin['headers']).json()
    key,pub=ready(app,user);age(app,key,2)
    assert client.put(base,json=dict(choice,preview_token=preview['preview_token'],confirm_immediate_deletion=True),headers=admin['headers']).status_code==409
    for value in (0,-1,8761,True,1.5,'4'):
        assert client.post(base+'/preview',json=dict(choice,absolute_hours=value),headers=admin['headers']).status_code==422


def test_scan_never_overrides_extended_policy(service,monkeypatch):
    freeze(monkeypatch);app,client=service;user=owner(app);key,pub=ready(app,user);age(app,key,80)
    original=deadlines.delete_if_eligible
    def intervene(*args,**kwargs):
        with app.state.db.transaction() as conn:write_setting(conn,'global','','retention_deadlines',{'absolute_hours':100,'delivery_hours':4})
        return original(*args,**kwargs)
    monkeypatch.setattr(deadlines,'delete_if_eligible',intervene)
    assert sweep_expired(app.state.db,app.state.settings.data_dir/'media')==0
    assert (app.state.settings.data_dir/'media'/(key+'.mp4')).exists()


def test_sweep_service_retries_after_failure_and_respects_shutdown(monkeypatch):
    from tubego_server import retention_service
    calls=[]
    class Stop:
        stopped=False
        def is_set(self):return self.stopped
        def wait(self,seconds):calls.append(seconds);self.stopped=True
    monkeypatch.setattr(retention_service,'sweep_expired',lambda *_:(_ for _ in ()).throw(OSError('private/path')))
    retention_service.run(object(),object(),Stop(),interval=60)
    assert calls==[60]


def test_preview_expires_and_same_policy_cannot_be_replayed(service,monkeypatch):
    freeze(monkeypatch);app,client=service;admin=owner(app,'admin')
    base='/api/v1/admin/retention/deadlines';choice={'absolute_hours':100,'delivery_hours':6}
    preview=client.post(base+'/preview',json=choice,headers=admin['headers']).json()
    monkeypatch.setattr(routes,'clock',lambda:NOW+timedelta(minutes=5))
    assert client.put(base,json=dict(choice,preview_token=preview['preview_token']),headers=admin['headers']).status_code==409
    assert client.get(base,headers=admin['headers']).json()['absolute_hours']==72


def test_deadline_normalizes_non_utc_timestamps():
    row={'ready_at':'2026-10-09T08:00:00-04:00','first_delivered_at':'2026-10-09T14:00:00+02:00'}
    assert deadline(row,{'absolute_hours':72,'delivery_hours':4})==NOW+timedelta(hours=4)
