from datetime import datetime,timedelta,timezone
from pathlib import Path
import pytest
from test_worker import service,seed
from test_egress_proxy import answers
from tubego_server.tasks import submit,action
from tubego_server.worker import Worker,Interrupted
from tubego_server.retries import transient,next_retry
from tubego_server.media import MediaError
import tubego_server.retries as retry_policy
import tubego_server.scheduler as scheduling

NOW=datetime(2026,10,9,12,tzinfo=timezone.utc)

class Broken:
    error=TimeoutError('secret=query /private/path')
    def __init__(self,options):self.options=options
    def __enter__(self):return self
    def __exit__(self,*args):pass
    def urlopen(self,*args):return None
    def extract_info(self,*args,**kwargs):
        Path(self.options['outtmpl'].replace('%(ext)s','mp4.part')).write_bytes(b'partial')
        raise self.error


def freeze(monkeypatch,time):
    monkeypatch.setattr(retry_policy,'clock',lambda:time[0])
    monkeypatch.setattr(scheduling,'now',lambda:time[0].isoformat())


def test_initial_plus_three_delayed_failures_persist_and_manual_budget_resets(service,monkeypatch):
    app,client=service;principal,headers,_=seed(app);time=[NOW];freeze(monkeypatch,time)
    result=submit(app.state.db,principal,'https://video.example/retry','720',resolver=answers);tid=result['task_id']
    for attempt,delay in enumerate((30,120,300,None),1):
        with Worker(app.state.settings,Broken) as worker:assert worker.run_once()
        current=client.get('/api/v1/tasks/'+tid,headers=headers).json()
        assert current['attempts']==attempt and 'secret' not in str(current)
        assert list((app.state.settings.data_dir/'media').rglob('*.part'))
        with app.state.db.transaction() as conn:
            count=conn.execute("SELECT count(*) FROM events WHERE kind='download_failed'").fetchone()[0]
        if delay:
            assert current['status']=='queued' and current['phase']=='retry_wait'
            assert current['next_retry_at']==(time[0]+timedelta(seconds=delay)).isoformat() and count==0
            with Worker(app.state.settings,Broken) as restarted:assert not restarted.run_once()
            time[0]+=timedelta(seconds=delay)
        else:assert current['status']=='failed' and current['next_retry_at'] is None and count==1
    admin,admin_headers,_=seed(app)
    with app.state.db.transaction() as conn:conn.execute("UPDATE users SET role='admin' WHERE id=?",(admin['id'],))
    assert client.get('/api/v1/admin/download-errors',headers=headers).status_code==403
    diagnostic=client.get('/api/v1/admin/download-errors',headers=admin_headers).json()['items'][0]
    assert diagnostic['diagnostic']['attempts']==4 and 'secret' not in str(diagnostic)
    assert action(app.state.db,principal,tid,'retry')['attempts']==0
    assert action(app.state.db,principal,tid,'retry')['id']==tid
    with Worker(app.state.settings,Broken) as worker:assert worker.run_once()
    assert client.get('/api/v1/tasks/'+tid,headers=headers).json()['attempts']==1


def test_future_retry_does_not_block_other_users_or_same_user_queue(service,monkeypatch):
    app,client=service;first,headers,_=seed(app);other,_,_=seed(app);time=[NOW];freeze(monkeypatch,time)
    one=submit(app.state.db,first,'https://video.example/first','720',resolver=answers)
    with Worker(app.state.settings,Broken) as worker:worker.run_once()
    same=submit(app.state.db,first,'https://video.example/same','720',resolver=answers)
    other_task=submit(app.state.db,other,'https://video.example/other','720',resolver=answers)
    with Worker(app.state.settings,Broken) as worker:
        assert worker.scheduler.preview()['id'] in (same['task_id'],other_task['task_id'])
        assert worker.run_once();assert worker.run_once();assert not worker.run_once()
    assert client.get('/api/v1/tasks/'+one['task_id'],headers=headers).json()['attempts']==1


def test_os_interruption_continues_same_attempt_after_restart(service,monkeypatch):
    app,client=service;principal,headers,_=seed(app);time=[NOW];freeze(monkeypatch,time)
    result=submit(app.state.db,principal,'https://video.example/v','720',resolver=answers)
    class Stopped(Broken):error=Interrupted()
    with Worker(app.state.settings,Stopped) as worker:worker.run_once()
    value=client.get('/api/v1/tasks/'+result['task_id'],headers=headers).json()
    assert value['attempts']==1 and value['next_retry_at'] is None
    with Worker(app.state.settings,Broken) as worker:worker.run_once()
    value=client.get('/api/v1/tasks/'+result['task_id'],headers=headers).json()
    assert value['attempts']==1 and value['next_retry_at']==(NOW+timedelta(seconds=30)).isoformat()


@pytest.mark.parametrize('error',[MediaError('unsupported'),MediaError('authentication_required'),MediaError('invalid_url'),RuntimeError('Requested format is not available'),RuntimeError('HTTP Error 404'),RuntimeError('unknown programming bug')])
def test_permanent_errors_fail_once_without_delay(service,monkeypatch,error):
    app,client=service;principal,headers,_=seed(app);time=[NOW];freeze(monkeypatch,time)
    result=submit(app.state.db,principal,'https://video.example/v','720',resolver=answers)
    class Permanent(Broken):pass
    Permanent.error=error
    with Worker(app.state.settings,Permanent) as worker:worker.run_once();assert not worker.run_once()
    value=client.get('/api/v1/tasks/'+result['task_id'],headers=headers).json()
    assert value['status']=='failed' and value['attempts']==1 and value['next_retry_at'] is None


def test_transient_only_known_transport_failures_and_delays():
    for error in (TimeoutError(),ConnectionResetError(),RuntimeError('HTTP Error 429'),RuntimeError('HTTP Error 503'),RuntimeError('Read timed out')):assert transient(error)
    for error in (ValueError('unknown'),RuntimeError('HTTP Error 403'),MediaError('source_restricted')):assert not transient(error)
    assert [next_retry(n,NOW) for n in (1,2,3)]==[(NOW+timedelta(seconds=n)).isoformat() for n in (30,120,300)]
    assert next_retry(4,NOW) is None


def test_restart_after_publication_never_downloads_success_again(service,monkeypatch):
    app,client=service;principal,headers,_=seed(app);time=[NOW];freeze(monkeypatch,time)
    result=submit(app.state.db,principal,'https://video.example/v','720',resolver=answers)
    with app.state.db.transaction() as conn:
        conn.execute('UPDATE resources SET ready_at=?,server_path=? WHERE id=?',(NOW.isoformat(),'already-finalized.mp4',result['resource_id']))
    class MustNotDownload:
        def __init__(self,*args):raise AssertionError('Successful publication repeated')
    with Worker(app.state.settings,MustNotDownload) as worker:assert worker.run_once()
    value=client.get('/api/v1/tasks/'+result['task_id'],headers=headers).json()
    assert value['status']=='completed' and value['next_retry_at'] is None
