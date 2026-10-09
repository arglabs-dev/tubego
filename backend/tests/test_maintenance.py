import uuid
from contextlib import closing
import pytest
from test_private_library import owner
from tubego_server.auth import utcnow
from tubego_server.delivery import read_setting,write_setting
from tubego_server.maintenance import ReadRunner,MaintenanceError
from tubego_server.maintenance_supervisor import Supervisor,ComposeDriver

@pytest.fixture
def service(tmp_path,monkeypatch):
    from fastapi.testclient import TestClient
    from tubego_server.main import create_app
    from tubego_server.config import Settings
    monkeypatch.setattr(ReadRunner,'start',lambda self:None)
    app=create_app(Settings(tmp_path))
    with TestClient(app) as client:yield app,client

REV='a'*40
class Driver:
    revision=REV;versions=['2026.8.19'];calls=[]
    def apply(self,action,payload):self.calls.append(action);return {'service_healthy':True}

def post(client,user,action='check_versions',**kwargs):
    return client.post('/api/v1/admin/maintenance/jobs',headers=user['headers'],json={'action':action,'request_id':str(uuid.uuid4()),**kwargs})
def enable(app,monkeypatch):
    monkeypatch.setenv('TUBEGO_MAINTENANCE_MUTATIONS','true')
    with app.state.db.transaction() as conn:write_setting(conn,'global','','maintenance_supervisor',{'seen_at':utcnow(),'backend_revisions':[REV],'yt_dlp_versions':['2026.8.19']})

def test_permissions_disabled_and_fixed_choices(service,monkeypatch):
    app,client=service;admin=owner(app,role='admin');user=owner(app)
    assert client.get('/api/v1/admin/maintenance',headers=user['headers']).status_code==403
    assert post(client,admin,'restart',confirmed=True).status_code==503
    enable(app,monkeypatch)
    assert post(client,admin,'restart').status_code==400
    assert post(client,admin,'update_backend',confirmed=True,revision='main; echo secret').status_code==422
    assert post(client,admin,'update_ytdlp',confirmed=True,version='evil-package').status_code==422
    assert post(client,admin,'shell',confirmed=True).status_code==422

def test_read_results_idempotence_rate_and_sanitized_errors(service,monkeypatch):
    app,client=service;user=owner(app,role='admin')
    body={'action':'check_versions','request_id':str(uuid.uuid4())}
    response=client.post('/api/v1/admin/maintenance/jobs',headers=user['headers'],json=body)
    assert response.status_code==200 and response.headers['cache-control']=='no-store'
    assert client.post('/api/v1/admin/maintenance/jobs',headers=user['headers'],json=body).json()['id']==response.json()['id']
    assert post(client,user).status_code==429
    monkeypatch.setattr('tubego_server.maintenance.check_versions',lambda:{'available':{'yt_dlp':'2026.8.19'}})
    assert ReadRunner(app.state.db).once()
    job=client.get('/api/v1/admin/maintenance/jobs/'+response.json()['id'],headers=user['headers']).json()
    assert job['status']=='succeeded' and 'session_id' not in job
    speed=post(client,user,'speed_test').json()
    def error():raise RuntimeError('sensitive stderr password=secret')
    monkeypatch.setattr('tubego_server.maintenance.speed_test',error)
    assert ReadRunner(app.state.db).once()
    failed=client.get('/api/v1/admin/maintenance/jobs/'+speed['id'],headers=user['headers'])
    assert failed.json()['error_code']=='operation_failed' and 'secret' not in failed.text

def test_write_drains_current_worker_and_rechecks_role(service,monkeypatch):
    app,client=service;user=owner(app,role='admin');enable(app,monkeypatch)
    key=post(client,user,'restart',confirmed=True).json()['id'];driver=Driver();driver.calls=[]
    with app.state.db.transaction() as conn:write_setting(conn,'global','','scheduler_lease',{'task_id':'busy','token':'lease'})
    supervisor=Supervisor(app.state.db,driver)
    assert not supervisor.once() and not driver.calls
    with app.state.db.transaction() as conn:
        assert read_setting(conn,'global','','maintenance_gate')==key
        conn.execute("UPDATE users SET role='user' WHERE id=?",(user['id'],))
    assert not supervisor.once()
    with closing(app.state.db.connect()) as conn:
        assert read_setting(conn,'maintenance',key,'job')['error_code']=='administrator_session_unavailable'
        assert not read_setting(conn,'global','','maintenance_gate')

def test_write_verifies_adapter_failure_and_never_replays_crashed_job(service,monkeypatch):
    app,client=service;user=owner(app,role='admin');enable(app,monkeypatch)
    key=post(client,user,'restart',confirmed=True).json()['id'];driver=Driver();driver.calls=[]
    def fail(*args):raise MaintenanceError('deployment_verification_failed')
    driver.apply=fail
    assert Supervisor(app.state.db,driver).once()
    assert client.get('/api/v1/admin/maintenance/jobs/'+key,headers=user['headers']).json()['status']=='failed'
    with app.state.db.transaction() as conn:conn.execute("DELETE FROM settings WHERE scope='global' AND key='maintenance_gate'")
    key=post(client,user,'restart',confirmed=True).json()['id']
    with app.state.db.transaction() as conn:
        job=read_setting(conn,'maintenance',key,'job');job['status']='running';write_setting(conn,'maintenance',key,'job',job)
    assert not Supervisor(app.state.db,Driver()).once()

def test_driver_rejects_untrusted_operator_checkout(tmp_path,monkeypatch):
    def run(self,args,env=None):
        if args[1:]==['remote','get-url','origin']:return 'https://evil.example/repo.git'
        return ''
    monkeypatch.setattr(ComposeDriver,'run',run)
    with pytest.raises(MaintenanceError,match='untrusted_checkout'):ComposeDriver(tmp_path,'tubego',tmp_path,['2026.8.19'])
    with pytest.raises(MaintenanceError,match='invalid_project'):ComposeDriver(tmp_path,'x; reboot',tmp_path,['2026.8.19'])

def test_read_lease_recovers_after_restart_and_stale_completion_cannot_win(service,monkeypatch):
    app,client=service;user=owner(app,role='admin');key=post(client,user).json()['id']
    with app.state.db.transaction() as conn:
        job=read_setting(conn,'maintenance',key,'job');job.update(status='running',claim_token='old',lease_until='2000-01-01');write_setting(conn,'maintenance',key,'job',job)
    monkeypatch.setattr('tubego_server.maintenance.check_versions',lambda:{'available':{'yt_dlp':'2026.8.19'}})
    assert ReadRunner(app.state.db).once()
    assert client.get('/api/v1/admin/maintenance/jobs/'+key,headers=user['headers']).json()['status']=='succeeded'


def test_maintenance_gate_does_not_cancel_current_download_or_claim_next(tmp_path):
    from test_scheduler import seed
    from tubego_server.db import Database
    from tubego_server.scheduler import Scheduler
    db=Database(tmp_path/'queue.sqlite');db.initialize();seed(db,'alice',2)
    scheduler=Scheduler(db);current=scheduler.claim()
    with db.transaction() as conn:write_setting(conn,'global','','maintenance_gate','operation')
    assert scheduler.finish(current['id'],current['claim_token'])
    assert scheduler.claim() is None
    with db.transaction() as conn:conn.execute("DELETE FROM settings WHERE scope='global' AND key='maintenance_gate'")
    assert scheduler.claim()['id']!=current['id']


def test_compose_adapter_uses_fixed_argv_and_requires_observed_deployment(tmp_path,monkeypatch):
    import json
    calls=[]
    def run(self,args,env=None):
        calls.append((args,env))
        if args[:3]==['git','remote','get-url']:return 'https://github.com/arglabs-dev/tubego.git'
        if args[:2]==['git','rev-parse']:return REV
        if args[:2]==['git','status']:return ''
        if args[:2]==['git','ls-files']:return args[-1]
        if args[0]=='docker' and args[1]=='inspect':return json.dumps([{'Type':'bind','Destination':'/data','Source':str(tmp_path)}])
        if 'ps' in args:return 'b'*64
        if 'exec' in args:return json.dumps({'backend_revision':REV,'yt_dlp':'2026.8.19'})
        return ''
    monkeypatch.setattr(ComposeDriver,'run',run)
    driver=ComposeDriver(tmp_path,'tubego',tmp_path,['2026.8.19'])
    assert driver.apply('update_backend',{'revision':REV})['service_healthy']
    docker=[(args,env) for args,env in calls if args[:2]==['docker','compose']]
    assert any('build' in args for args,_ in docker)
    assert all(args[:4]==['docker','compose','--project-name','tubego'] for args,_ in docker)
    assert all(env['TUBEGO_BUILD_REVISION']==REV for _,env in docker)
    with pytest.raises(MaintenanceError,match='version_not_approved'):driver.apply('update_ytdlp',{'version':'2026.9.1'})


def test_bounded_version_response_and_fixed_server_measurement(monkeypatch):
    from tubego_server.maintenance import fixed_json,speed_test
    class Response:
        status_code=200
        def __enter__(self):return self
        def __exit__(self,*args):pass
        def iter_content(self,size):yield b'x'*(2*1024*1024+1)
    class Session:
        trust_env=True
        def __enter__(self):return self
        def __exit__(self,*args):pass
        def get(self,url,**kwargs):assert kwargs['stream'] and not kwargs['allow_redirects'];return Response()
    monkeypatch.setattr('tubego_server.maintenance.requests.Session',Session)
    with pytest.raises(MaintenanceError,match='version_check_failed'):fixed_json('https://pypi.org/pypi/yt-dlp/json')
    monkeypatch.setattr(Response,'iter_content',lambda self,size:iter([b'video'*1_000_000]))
    result=speed_test();assert result['bytes']==5_000_000 and result['location']=='server'
