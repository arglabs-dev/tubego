"""Operator-only real Docker rehearsal. Fixed isolated project, no production data."""
import argparse,json,os,socket,time,uuid
from pathlib import Path
from datetime import datetime,timezone,timedelta
import requests
from tubego_server.db import Database
from tubego_server.auth import utcnow,token_hash
from tubego_server.delivery import read_setting
from tubego_server.maintenance_supervisor import ComposeDriver,Supervisor
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--repo',required=True);p.add_argument('--version',default='2026.8.19');a=p.parse_args()
project='tubego-mvp-review'
root=Path('/tmp')/('tubego-supervisor-e2e-'+str(uuid.uuid4()));root.mkdir(mode=0o700)
data=root/'data';data.mkdir(mode=0o777);data.chmod(0o777)
# Cross-UID container/host SQLite: outer directory stays 0700; only the isolated
# bind child is writable by both. No raw credential is written to disk.
old_umask=os.umask(0)
report={'project':project,'data_dir':str(data),'stages':[]};started=False
net=requests.Session();net.trust_env=False

def stage(name,**details):
    report['stages'].append({'stage':name,**details});(root/'report.json').write_text(json.dumps(report,indent=2));print(json.dumps(report['stages'][-1]),flush=True)

def healthy():
    for _ in range(60):
        try:
            r=net.get('http://127.0.0.1:8000/api/v1/health',timeout=3)
            if r.status_code==200:return
        except requests.RequestException:pass
        time.sleep(2)
    raise RuntimeError('isolated API did not become healthy')

try:
    os.environ['TUBEGO_MAINTENANCE_MUTATIONS']='true'
    driver=ComposeDriver(a.repo,project,data,[a.version]);report['revision']=driver.revision
    existing=driver.run(['docker','ps','-aq','--filter','label=com.docker.compose.project='+project]).strip()
    if existing:raise RuntimeError('review project already exists; refusing to touch it')
    for kind in ('network','volume'):
        artifacts=driver.run(['docker',kind,'ls','-q','--filter','label=com.docker.compose.project='+project]).strip()
        if artifacts:raise RuntimeError('review project artifacts already exist; refusing to reuse them')
    with socket.socket() as probe:probe.bind(('127.0.0.1',8000))
    db=Database(data/'tubego.sqlite3');db.initialize()
    uid,did,sid=[str(uuid.uuid4()) for _ in range(3)];token=uuid.uuid4().hex;now=utcnow()
    with db.transaction() as conn:
        conn.execute('INSERT INTO users VALUES(?,?,?,?,?,?,?,?)',(uid,uid+'@example.test','unused','admin','approved',now,now,now))
        conn.execute('INSERT INTO devices(id,user_id,name,created_at) VALUES(?,?,?,?)',(did,uid,'Isolated supervisor review',now))
        conn.execute('INSERT INTO sessions VALUES(?,?,?,?,?,NULL,?)',(sid,uid,did,token_hash(token),(datetime.now(timezone.utc)+timedelta(hours=2)).isoformat(),now))
    (data/'tubego.sqlite3').chmod(0o666)
    driver.compose(['build','api','worker','retention','egress'],a.version)
    started=True
    driver.compose(['up','-d','api','worker','retention','egress'],a.version);healthy()
    supervisor=Supervisor(db,driver);supervisor.once()
    stage('isolated_services_healthy',revision=driver.revision,yt_dlp=a.version)
    headers={'Authorization':'Bearer '+token}
    for action,extra in [('restart',{}),('update_ytdlp',{'version':a.version}),('update_backend',{'revision':driver.revision})]:
        supervisor.once() # refresh durable capability heartbeat
        request_id=str(uuid.uuid4());payload={'action':action,'request_id':request_id,'confirmed':True,**extra}
        url='http://127.0.0.1:8000/api/v1/admin/maintenance/jobs'
        response=net.post(url,headers=headers,json=payload,timeout=10);assert response.status_code==200,(action,response.status_code)
        assert response.json()['status']=='queued'
        assert supervisor.once(),'no actual deployment job was selected'
        healthy()
        done=net.get(url+'/'+request_id,headers=headers,timeout=10);assert done.status_code==200
        job=done.json();assert job['status']=='succeeded',job
        assert job['result']['service_healthy'] is True
        assert job['result']['installed']['yt_dlp']==a.version
        assert job['result']['installed']['backend_revision']==driver.revision
        replay=net.post(url,headers=headers,json=payload,timeout=10);assert replay.status_code==200 and replay.json()['id']==request_id
        with db.transaction() as conn:
            assert read_setting(conn,'global','','maintenance_gate') is None
            assert conn.execute("SELECT count(*) FROM audit WHERE target_id=? AND action='maintenance.finished'",(request_id,)).fetchone()[0]==1
            assert conn.execute('SELECT count(*) FROM users WHERE id=?',(uid,)).fetchone()[0]==1
        stage('real_supervisor_operation',action=action,status=job['status'],result=job['result'],uuid_replay_same_job=True,gate_cleared=True,audit_finished_once=True)
    report['result']='PASS';stage('finished',result='PASS')
except Exception as exc:
    report['result']='FAIL';stage('failed',failure_type=type(exc).__name__,failure=str(exc));raise
finally:
    if started:
        # Only containers created by this run, never images, external volumes or
        # another Compose project. Retain temporary database and report evidence.
        driver.compose(['down','--remove-orphans'],a.version)
    os.umask(old_umask);net.close();(root/'report.json').write_text(json.dumps(report,indent=2))
