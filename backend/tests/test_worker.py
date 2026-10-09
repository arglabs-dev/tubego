from datetime import datetime,timezone,timedelta
import uuid
import types
import pytest
from fastapi.testclient import TestClient
from tubego_server.main import create_app
from tubego_server.config import Settings
from tubego_server.auth import token_hash,utcnow,get_principal
from tubego_server.tasks import submit,action
from tubego_server.worker import Worker
from tubego_server.scheduler import Scheduler
from test_egress_proxy import proxy_fixture,answers


def seed(app):
    user,token,device=[str(uuid.uuid4()) for _ in range(3)];now=utcnow()
    session=str(uuid.uuid4())
    with app.state.db.transaction() as conn:
        conn.execute('INSERT INTO users VALUES (?,?,?,?,?,?,?,?)',(user,user+'@example.com','hash','user','approved',now,now,now))
        conn.execute('INSERT INTO devices(id,user_id,name,created_at) VALUES(?,?,?,?)',(device,user,'phone',now))
        conn.execute('INSERT INTO sessions VALUES (?,?,?,?,?,NULL,?)',(session,user,device,token_hash(token),(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat(),now))
    principal={'id':user,'session_id':session,'status':'approved','email_verified_at':now}
    return principal,{'Authorization':'Bearer '+token},device


@pytest.fixture
def service(tmp_path):
    app=create_app(Settings(tmp_path))
    with TestClient(app) as client:yield app,client


def test_idempotent_tasks_private_actions_priority_and_retry(service,monkeypatch):
    app,client=service;one,headers,_=seed(app);other,foreign,_=seed(app)
    monkeypatch.setattr('tubego_server.tasks.normalize_url',lambda url:url)
    request={'url':'https://video.example/one','selection':'720','request_id':str(uuid.uuid4())}
    result=client.post('/api/v1/resources',headers=headers,json=request).json();tid=result['task']['id']
    assert client.post('/api/v1/resources',headers=headers,json=request).json()['task']['id']==tid
    assert client.post('/api/v1/resources',headers=headers,json={**request,'url':'https://video.example/other'}).status_code==409
    duplicate_request={**request,'request_id':str(uuid.uuid4())}
    assert client.post('/api/v1/resources',headers=headers,json=duplicate_request).json()['resource_id']==result['resource_id']
    assert client.post('/api/v1/resources',headers=headers,json={**duplicate_request,'url':'https://video.example/other'}).status_code==409
    assert client.get('/api/v1/tasks/'+tid,headers=foreign).status_code==404
    for kind in ('cancel','retry','priority'):
        assert client.post('/api/v1/tasks/'+tid+'/'+kind,headers=foreign).status_code==404
    assert client.post('/api/v1/tasks/'+tid+'/cancel',headers=headers).json()['status']=='cancelled'
    assert client.post('/api/v1/tasks/'+tid+'/retry',headers=headers).json()['status']=='queued'
    assert client.post('/api/v1/tasks/'+tid+'/retry',headers=headers).json()['id']==tid
    second=client.post('/api/v1/resources',headers=headers,json={'url':'https://video.example/two','selection':'480'}).json()['task']['id']
    third=client.post('/api/v1/resources',headers=headers,json={'url':'https://video.example/three','selection':'480'}).json()['task']['id']
    client.post('/api/v1/tasks/'+third+'/priority',headers=headers)
    client.post('/api/v1/tasks/'+second+'/priority',headers=headers)
    assert Scheduler(app.state.db).preview()['id']==second
    client.post('/api/v1/tasks/'+third+'/priority',headers=headers)
    assert Scheduler(app.state.db).preview()['id']==third
    assert client.get('/api/v1/tasks',headers=headers).json()['items']
    assert not client.get('/api/v1/tasks',headers=foreign).json()['items']


def test_exclusive_lock_and_owned_restart_resume(service):
    app,client=service;principal,headers,_=seed(app)
    task=submit(app.state.db,principal,'https://video.example/v','480',resolver=answers)['task']
    with Worker(app.state.settings) as worker:
        claimed=worker.scheduler.claim()
        assert claimed['id']==task['id']
        with pytest.raises(RuntimeError):
            with Worker(app.state.settings):pass
        assert worker.scheduler.preview() is None
        assert client.get('/api/v1/tasks/'+task['id'],headers=headers).json()['status']=='running'
    with Worker(app.state.settings) as restarted:
        assert restarted.scheduler.preview()['id']==task['id']
        assert not restarted.scheduler.finish(task['id'],claimed['claim_token'],'completed')


def test_real_worker_download_publish_and_authenticated_range(service,monkeypatch):
    app,client=service;principal,headers,device=seed(app)
    with proxy_fixture() as (proxy,contacted):
        monkeypatch.setenv('TUBEGO_MEDIA_EGRESS_PROXY',f'http://127.0.0.1:{proxy.server_address[1]}')
        # Resolve test Internet names; the actual proxy pins the simulated endpoint.
        from tubego_server.media import restricted_ytdlp
        result=submit(app.state.db,principal,'http://video.example/lecture.mp4','best',resolver=answers)
        with Worker(app.state.settings,engine_factory=lambda options:restricted_ytdlp(options,resolver=answers)) as worker:
            assert worker.run_once()
        tid=result['task']['id'];rid=result['resource_id']
        task=client.get('/api/v1/tasks/'+tid,headers=headers).json()
        assert task['status']=='completed',task
        assert task['phase']=='ready' and task['progress']==1
        assert client.post('/api/v1/tasks/'+tid+'/retry',headers=headers).status_code==409
        with app.state.db.transaction() as conn:
            resource=conn.execute('SELECT * FROM resources WHERE id=?',(rid,)).fetchone()
            assert resource['title']=='lecture' and resource['size_bytes']==4
            assert conn.execute('SELECT status FROM deliveries WHERE resource_id=? AND device_id=?',(rid,device)).fetchone()[0]=='pending'
        # Private media is served through the API, never as a public path.
        response=client.get(f'/api/v1/resources/{rid}/download',headers={**headers,'Range':'bytes=1-2'})
        assert response.status_code==206 and response.content==b'ak'
        assert client.get(f'/api/v1/resources/{rid}/download').status_code==401


def test_cancel_during_extraction_no_publication(service):
    app,client=service;principal,headers,_=seed(app)
    result=submit(app.state.db,principal,'https://video.example/v','720',resolver=answers)
    tid=result['task']['id'];rid=result['resource_id']
    class Fake:
        def __init__(self,options):self.options=options
        def __enter__(self):return self
        def __exit__(self,*args):pass
        def urlopen(self,request):return None
        def extract_info(self,*args,**kwargs):
            action(app.state.db,principal,tid,'cancel')
            self.options['progress_hooks'][0]({'downloaded_bytes':1,'total_bytes':10})
    with Worker(app.state.settings,Fake) as worker:assert worker.run_once()
    assert client.get('/api/v1/tasks/'+tid,headers=headers).json()['status']=='cancelled'
    with app.state.db.transaction() as conn:
        assert conn.execute('SELECT ready_at FROM resources WHERE id=?',(rid,)).fetchone()[0] is None
        assert conn.execute('SELECT count(*) FROM deliveries').fetchone()[0]==0


def test_failure_manual_retry_keeps_partial_and_state(service):
    app,client=service;principal,headers,_=seed(app)
    result=submit(app.state.db,principal,'https://video.example/v','720',resolver=answers);tid=result['task']['id']
    class Fake:
        def __init__(self,options):self.options=options
        def __enter__(self):return self
        def __exit__(self,*args):pass
        def urlopen(self,request):return None
        def extract_info(self,*args,**kwargs):
            assert self.options['continuedl']
            path=self.options['outtmpl'].replace('%(ext)s','mp4.part')
            from pathlib import Path
            Path(path).write_bytes(b'partial')
            raise RuntimeError('timeout secret=query /server/path')
    with Worker(app.state.settings,Fake) as worker:assert worker.run_once()
    value=client.get('/api/v1/tasks/'+tid,headers=headers).json()
    assert value['status']=='failed' and value['attempts']==1 and 'secret' not in str(value)
    assert list((app.state.settings.data_dir/'media').rglob('*.part'))
    assert client.post('/api/v1/tasks/'+tid+'/retry',headers=headers).json()['status']=='queued'
    with Worker(app.state.settings,Fake) as worker:assert worker.run_once()
    assert client.get('/api/v1/tasks/'+tid,headers=headers).json()['attempts']==2


def test_cancel_at_publication_digest_race(service,monkeypatch):
    app,client=service;principal,headers,_=seed(app)
    result=submit(app.state.db,principal,'https://video.example/v','720',resolver=answers)
    tid=result['task']['id'];rid=result['resource_id']
    from pathlib import Path
    class Fake:
        def __init__(self,options):self.options=options
        def __enter__(self):return self
        def __exit__(self,*args):pass
        def urlopen(self,request):return None
        def extract_info(self,*args,**kwargs):return {'title':'Lecture','height':720}
        def process_ie_result(self,info,download):
            path=Path(self.options['outtmpl'].replace('%(ext)s','mp4'));path.write_bytes(b'final')
            return {'filepath':str(path)}
    from tubego_server.delivery import media_digest
    def racing_digest(*args):
        digest=media_digest(*args)
        action(app.state.db,principal,tid,'cancel')
        return digest
    monkeypatch.setattr('tubego_server.delivery.media_digest',racing_digest)
    with Worker(app.state.settings,Fake) as worker:assert worker.run_once()
    assert client.get('/api/v1/tasks/'+tid,headers=headers).json()['status']=='cancelled'
    with app.state.db.transaction() as conn:
        assert conn.execute('SELECT ready_at FROM resources WHERE id=?',(rid,)).fetchone()[0] is None
        assert conn.execute('SELECT count(*) FROM deliveries').fetchone()[0]==0


def test_real_restart_resumes_existing_partial(service,monkeypatch):
    from pathlib import Path
    from test_egress_proxy import Source
    from tubego_server.media import restricted_ytdlp
    data=b'abcd'*16384;seen=[]
    def head(self):
        self.send_response(200);self.send_header('Content-Type','video/mp4')
        self.send_header('Content-Length',str(len(data)));self.end_headers()
    def get(self):
        header=self.headers.get('Range');seen.append(header)
        start=int(header.split('=')[1].split('-')[0]) if header else 0
        self.send_response(206 if header else 200)
        self.send_header('Content-Type','video/mp4');self.send_header('Content-Length',str(len(data)-start))
        if header:self.send_header('Content-Range',f'bytes {start}-{len(data)-1}/{len(data)}')
        self.end_headers();self.wfile.write(data[start:])
    monkeypatch.setattr(Source,'do_HEAD',head);monkeypatch.setattr(Source,'do_GET',get)
    app,client=service;principal,headers,_=seed(app)
    result=submit(app.state.db,principal,'http://video.example/lecture.mp4','best',resolver=answers)
    tid=result['task']['id'];rid=result['resource_id']
    with Worker(app.state.settings) as previous:
        previous.scheduler.claim()
        folder=app.state.settings.data_dir/'media'/principal['id']/rid;folder.mkdir(parents=True)
        (folder/'media.mp4.part').write_bytes(data[:10000])
    with proxy_fixture() as (proxy,contacted):
        monkeypatch.setenv('TUBEGO_MEDIA_EGRESS_PROXY',f'http://127.0.0.1:{proxy.server_address[1]}')
        with Worker(app.state.settings,engine_factory=lambda options:restricted_ytdlp(options,resolver=answers)) as restarted:
            assert restarted.run_once()
    assert 'bytes=10000-' in seen
    assert (folder/'media.mp4').read_bytes()==data
    assert client.get('/api/v1/tasks/'+tid,headers=headers).json()['status']=='completed'
