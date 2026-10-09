import errno
import json
from pathlib import Path
import types
import threading
import time
import pytest
from src.disk_guard import DiskGuard,DiskState,StoragePaused,notify_admin_storage
from tubego_server.worker import Worker
from tubego_server.tasks import submit
from test_worker import service,seed
from test_egress_proxy import answers

UNIT=1024*1024

def usage(percent):return types.SimpleNamespace(total=100*UNIT,used=percent*UNIT,free=(100-percent)*UNIT)


def test_exact_threshold_and_remaining_space(tmp_path):
    guard=DiskGuard(tmp_path,usage=lambda path:usage(89))
    assert not guard.sample().paused
    assert guard.sample(12*UNIT).reason=='insufficient_space'
    guard.usage=lambda path:usage(90)
    with pytest.raises(StoragePaused):guard.check()
    guard.usage=lambda path:usage(91)
    assert guard.sample().paused
    guard.usage=lambda path:usage(89)
    assert not guard.sample().paused


def test_admin_transition_notifications_durable_and_private(service):
    app,client=service;admin,_,_=seed(app);ordinary,_,_=seed(app)
    with app.state.db.transaction() as conn:conn.execute("UPDATE users SET role='admin' WHERE id=?",(admin['id'],))
    guard=DiskGuard(app.state.settings.data_dir,usage=lambda path:usage(90),notify=lambda state:notify_admin_storage(state,app.state.settings.database_path))
    guard.sample();guard.sample()
    # Another process/restart sees persisted state and cannot send duplicates.
    restarted=DiskGuard(app.state.settings.data_dir,usage=lambda path:usage(90),notify=guard.notify)
    restarted.sample()
    with app.state.db.transaction() as conn:
        events=conn.execute("SELECT user_id FROM events WHERE kind='server_storage_paused'").fetchall()
        assert [row[0] for row in events]==[admin['id']]
    guard.usage=lambda path:usage(89);guard.sample()
    guard.usage=lambda path:usage(90);guard.sample()
    with app.state.db.transaction() as conn:
        assert conn.execute("SELECT count(*) FROM events WHERE kind='server_storage_paused'").fetchone()[0]==2


def test_low_disk_does_not_claim_or_consume_attempt(service):
    app,client=service;principal,headers,_=seed(app)
    result=submit(app.state.db,principal,'https://video.example/v','720',resolver=answers)
    with Worker(app.state.settings) as worker:
        worker.disk.usage=lambda path:usage(90)
        assert worker.run_once() is False
    task=client.get('/api/v1/tasks/'+result['task_id'],headers=headers).json()
    assert task['status']=='queued' and task['attempts']==0
    assert task['phase']=='paused' and task['pause_reason']=='server_storage'


def test_active_pause_partial_resume_without_attempt(service):
    app,client=service;principal,headers,_=seed(app)
    result=submit(app.state.db,principal,'https://video.example/v','720',resolver=answers);tid=result['task_id']
    current={'percent':20};entered=threading.Event()
    class Fake:
        rounds=0
        def __init__(self,options):self.options=options
        def __enter__(self):return self
        def __exit__(self,*args):pass
        def urlopen(self,request):return None
        def extract_info(self,*args,**kwargs):return {'title':'Lecture','height':720}
        def process_ie_result(self,info,download):
            type(self).rounds+=1
            filename=Path(self.options['outtmpl'].replace('%(ext)s','mp4'))
            if type(self).rounds==1:
                filename.with_suffix('.mp4.part').write_bytes(b'partial')
                current['percent']=90;entered.set()
                self.options['progress_hooks'][0]({'downloaded_bytes':7,'total_bytes':10})
            assert filename.with_suffix('.mp4.part').read_bytes()==b'partial'
            filename.write_bytes(b'completed')
            return {'filepath':str(filename)}
    with Worker(app.state.settings,Fake) as worker:
        worker.disk.usage=lambda path:usage(current['percent'])
        thread=threading.Thread(target=worker.run_once);thread.start()
        assert entered.wait(2)
        deadline=time.monotonic()+2
        while time.monotonic()<deadline:
            task=client.get('/api/v1/tasks/'+tid,headers=headers).json()
            if task['status']=='paused':break
            time.sleep(.01)
        assert task['status']=='paused' and task['pause_reason']=='server_storage'
        assert worker.scheduler.preview() is None
        assert task['attempts']==1
        current['percent']=20
        thread.join(5);assert not thread.is_alive()
    task=client.get('/api/v1/tasks/'+tid,headers=headers).json()
    assert task['status']=='completed' and task['attempts']==1 and Fake.rounds==2


def test_enospc_is_pause_not_failed(service):
    app,client=service;principal,headers,_=seed(app)
    result=submit(app.state.db,principal,'https://video.example/v','720',resolver=answers)
    class Fake:
        rounds=0
        def __init__(self,options):self.options=options
        def __enter__(self):return self
        def __exit__(self,*args):pass
        def urlopen(self,request):return None
        def extract_info(self,*args,**kwargs):
            type(self).rounds+=1
            if self.rounds==1:raise OSError(errno.ENOSPC,'No space left on device')
            return {'title':'Lecture','height':720}
        def process_ie_result(self,info,download):
            filename=Path(self.options['outtmpl'].replace('%(ext)s','mp4'));filename.write_bytes(b'final')
            return {'filepath':str(filename)}
    with Worker(app.state.settings,Fake) as worker:assert worker.run_once()
    task=client.get('/api/v1/tasks/'+result['task_id'],headers=headers).json()
    assert task['status']=='completed' and task['attempts']==1


def test_bot_start_and_progress_respect_same_guard(tmp_path,monkeypatch):
    from src.core import Downloader
    current={'percent':90};calls=[];states=[]
    downloader=Downloader(str(tmp_path));downloader.disk.usage=lambda path:usage(current['percent'])
    import src.disk_guard
    def free_space(interval):
        calls.append('wait');current['percent']=20
    monkeypatch.setattr(src.disk_guard.time,'sleep',free_space)
    import yt_dlp
    class FakeYDL:
        def __init__(self,options):self.options=options
        def __enter__(self):return self
        def __exit__(self,*args):pass
        def urlopen(self,request):return None
        def extract_info(self,*args,**kwargs):
            assert current['percent']==20
            current['percent']=90
            self.options['progress_hooks'][0]({'status':'downloading','downloaded_bytes':1,'total_bytes':2})
            return {'title':'test'}
        def prepare_filename(self,info):return str(tmp_path/'test.mp4')
    monkeypatch.setattr(yt_dlp,'YoutubeDL',FakeYDL)
    result=downloader.download('https://video.example',progress_hook=lambda state:states.append(state['status']))
    assert result['status']=='success' and len(calls)==2
    assert states.count('paused')==2 and states.count('resuming')==2


def test_cancel_while_paused_releases_lease(service):
    app,client=service;principal,headers,_=seed(app)
    result=submit(app.state.db,principal,'https://video.example/v','720',resolver=answers);tid=result['task_id']
    from tubego_server.tasks import action
    class Fake:
        def __init__(self,options):self.options=options
        def __enter__(self):return self
        def __exit__(self,*args):pass
        def urlopen(self,request):return None
        def extract_info(self,*args,**kwargs):
            self.options['progress_hooks'][0]({'downloaded_bytes':1,'total_bytes':2})
    with Worker(app.state.settings,Fake) as worker:
        probes={'n':0}
        def usage_once(path):
            probes['n']+=1
            return usage(20 if probes['n']==1 else 90)
        worker.disk.usage=usage_once
        thread=threading.Thread(target=worker.run_once);thread.start()
        deadline=time.monotonic()+3
        while time.monotonic()<deadline:
            task=client.get('/api/v1/tasks/'+tid,headers=headers).json()
            if task['status']=='paused':break
            time.sleep(.01)
        assert task['status']=='paused'
        action(app.state.db,principal,tid,'cancel')
        thread.join(5);assert not thread.is_alive()
    assert client.get('/api/v1/tasks/'+tid,headers=headers).json()['status']=='cancelled'


def test_paused_restart_preserves_attempt(service):
    app,client=service;principal,headers,_=seed(app)
    result=submit(app.state.db,principal,'https://video.example/v','720',resolver=answers);tid=result['task_id']
    from tubego_server.scheduler import put_setting
    with app.state.db.transaction() as conn:
        conn.execute("UPDATE tasks SET status='paused',attempts=1 WHERE id=?",(tid,))
        put_setting(conn,'task',tid,'attempt_active',True)
    class Fake:
        def __init__(self,options):self.options=options
        def __enter__(self):return self
        def __exit__(self,*args):pass
        def urlopen(self,request):return None
        def extract_info(self,*args,**kwargs):return {'title':'Lecture','height':720}
        def process_ie_result(self,info,download):
            output=Path(self.options['outtmpl'].replace('%(ext)s','mp4'));output.write_bytes(b'final')
            return {'filepath':str(output)}
    with Worker(app.state.settings,Fake) as worker:assert worker.run_once()
    assert client.get('/api/v1/tasks/'+tid,headers=headers).json()['attempts']==1
