"""Durable administrative jobs. Only fixed-host reads run inside the API process."""
from contextlib import closing
from datetime import datetime,timezone,timedelta
import importlib.metadata
import json
import os
import re
import threading
import time
import uuid
import requests
from fastapi import HTTPException
from tubego_server import __version__
from tubego_server.alerts import notify_admins
from tubego_server.auth import utcnow
from tubego_server.delivery import read_setting,write_setting,device_scope

READ_ACTIONS={'check_versions','speed_test'}
WRITE_ACTIONS={'update_backend','update_ytdlp','restart'}
SHA=re.compile(r'^[0-9a-f]{40}$')
VERSION=re.compile(r'^20\d{2}\.\d{1,2}\.\d{1,2}$')

class MaintenanceError(Exception):
    def __init__(self,code):self.code=code;super().__init__(code)

def admin(conn,principal):
    scope,_=device_scope(conn,principal)
    if conn.execute('SELECT role FROM users WHERE id=?',(scope.user_id,)).fetchone()[0]!='admin':raise HTTPException(403,'Administrator required')

def installed():
    try:version=importlib.metadata.version('yt-dlp')
    except importlib.metadata.PackageNotFoundError:version=None
    revision=os.environ.get('TUBEGO_BUILD_REVISION','')
    return {'tubego':__version__,'backend_revision':revision if SHA.fullmatch(revision) else None,'yt_dlp':version}

def capabilities(conn):
    record=read_setting(conn,'global','','maintenance_supervisor') or {}
    live=False
    try:live=datetime.fromisoformat(record['seen_at'])>datetime.now(timezone.utc)-timedelta(seconds=60)
    except (KeyError,ValueError,TypeError):pass
    enabled=os.environ.get('TUBEGO_MAINTENANCE_MUTATIONS','false').lower()=='true' and live
    return {'mutation_enabled':enabled,'reason':None if enabled else 'capability_unavailable',
            'backend_revisions':record.get('backend_revisions',[]) if enabled else [],
            'yt_dlp_versions':record.get('yt_dlp_versions',[]) if enabled else []}

def public(job):return {key:job.get(key) for key in ('id','action','status','phase','progress','result','error_code','created_at','updated_at')}

def announce(conn,job):
    notify_admins(conn,'admin_maintenance',{'operation':job['action'],'status':job['status'],'error_code':job.get('error_code')},'maintenance:'+job['id']+':'+job['status'])

def save(conn,job):
    job['updated_at']=utcnow();write_setting(conn,'maintenance',job['id'],'job',job)

def jobs(conn):
    return [json.loads(row[0]) for row in conn.execute("SELECT value_json FROM settings WHERE scope='maintenance' AND key='job' ORDER BY updated_at")]

def fixed_json(url):
    with requests.Session() as session:
        session.trust_env=False
        with session.get(url,timeout=(5,15),allow_redirects=False,stream=True,headers={'Accept':'application/json'}) as response:
            if response.status_code!=200:raise MaintenanceError('version_check_failed')
            data=bytearray()
            for chunk in response.iter_content(65536):
                data.extend(chunk)
                if len(data)>2*1024*1024:raise MaintenanceError('version_check_failed')
            return json.loads(data)

def check_versions():
    package=fixed_json('https://pypi.org/pypi/yt-dlp/json').get('info',{}).get('version')
    revision=fixed_json('https://api.github.com/repos/arglabs-dev/tubego/commits/main').get('sha')
    if not isinstance(package,str) or not VERSION.fullmatch(package) or not isinstance(revision,str) or not SHA.fullmatch(revision):raise MaintenanceError('invalid_upstream_response')
    return {'installed':installed(),'available':{'yt_dlp':package,'backend_revision':revision}}

def speed_test():
    size=5_000_000;received=0;started=time.monotonic()
    with requests.Session() as session:
        session.trust_env=False
        with session.get('https://speed.cloudflare.com/__down?bytes=5000000',timeout=(5,15),allow_redirects=False,stream=True,headers={'Accept-Encoding':'identity'}) as response:
            if response.status_code!=200:raise MaintenanceError('speed_test_failed')
            for chunk in response.iter_content(65536):
                received+=len(chunk)
                if received>size:raise MaintenanceError('invalid_speed_response')
                if time.monotonic()-started>30:raise MaintenanceError('speed_test_timeout')
    elapsed=time.monotonic()-started
    if received!=size or elapsed<=0:raise MaintenanceError('invalid_speed_response')
    return {'server_download_mbps':round(received*8/elapsed/1_000_000,2),'bytes':received,'seconds':round(elapsed,2),'location':'server','direction':'download'}

class ReadRunner:
    def __init__(self,db):self.db=db;self.stop=threading.Event();self.thread=None
    def start(self):
        self.thread=threading.Thread(target=self.run,name='maintenance-reads',daemon=True);self.thread.start()
    def run(self):
        while not self.stop.wait(1):
            try:self.once()
            except Exception:pass
    def once(self):
        selected=None
        with self.db.transaction() as conn:
            for job in jobs(conn):
                if job['action'] not in READ_ACTIONS:continue
                if job['status']=='running' and job.get('lease_until','')<utcnow():job['status']='queued'
                if job['status']!='queued':continue
                try:admin(conn,{'id':job['user_id'],'session_id':job['session_id']})
                except HTTPException:
                    job.update(status='failed',phase='finished',error_code='administrator_session_unavailable');save(conn,job);announce(conn,job)
                    conn.execute("INSERT INTO audit(actor_user_id,action,target_id,detail_json,created_at) VALUES(?,'maintenance.finished',?,?,?)",(job['user_id'],job['id'],json.dumps({'action':job['action'],'status':'failed','error_code':job['error_code']}),utcnow()))
                    continue
                job.update(status='running',phase='checking' if job['action']=='check_versions' else 'measuring_server',progress=0.1,claim_token=uuid.uuid4().hex,lease_until=(datetime.now(timezone.utc)+timedelta(seconds=120)).isoformat());save(conn,job);selected=job;break
        if selected is None:return False
        try:
            result=check_versions() if selected['action']=='check_versions' else speed_test()
            selected.update(status='succeeded',phase='finished',progress=1,result=result,error_code=None)
        except Exception as exc:selected.update(status='failed',phase='finished',error_code=exc.code if isinstance(exc,MaintenanceError) else 'operation_failed')
        with self.db.transaction() as conn:
            current=read_setting(conn,'maintenance',selected['id'],'job')
            if not current or current.get('claim_token')!=selected['claim_token']:return False
            save(conn,selected);announce(conn,selected)
            if selected['action']=='check_versions' and selected['status']=='succeeded':write_setting(conn,'global','','maintenance_versions',selected['result'])
            conn.execute("INSERT INTO audit(actor_user_id,action,target_id,detail_json,created_at) VALUES(?,'maintenance.finished',?,?,?)",(selected['user_id'],selected['id'],json.dumps({'action':selected['action'],'status':selected['status'],'error_code':selected['error_code']}),utcnow()))
        return True
    def close(self):
        self.stop.set()
        if self.thread:self.thread.join(timeout=5)
