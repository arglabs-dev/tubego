"""Opt-in host supervisor. Staged official checkout only; API never has Docker access."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import time
from tubego_server.auth import utcnow
from tubego_server.db import Database
from tubego_server.delivery import read_setting,write_setting
from tubego_server.maintenance import admin,capabilities,jobs,save,WRITE_ACTIONS,SHA,VERSION,MaintenanceError,announce
from fastapi import HTTPException

OFFICIAL='https://github.com/arglabs-dev/tubego.git'
SERVICES=('api','worker','retention','egress')

class ComposeDriver:
    """Operator pre-stages an official clean revision; no request can choose a URL or command."""
    def __init__(self,repo,project,data,versions):
        self.repo=Path(repo).resolve();self.data=Path(data).resolve()
        if not re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,62}',project):raise MaintenanceError('invalid_project')
        self.project=project;self.versions=list(versions)
        if not versions or any(not VERSION.fullmatch(v) for v in versions):raise MaintenanceError('invalid_version')
        origin=self.git('remote','get-url','origin').strip()
        if origin not in (OFFICIAL,OFFICIAL[:-4],'git@github.com:arglabs-dev/tubego.git'):raise MaintenanceError('untrusted_checkout')
        self.revision=self.git('rev-parse','HEAD').strip()
        if not SHA.fullmatch(self.revision) or self.git('status','--porcelain','--untracked-files=no').strip():raise MaintenanceError('unclean_checkout')
        # Verify the pinned staged object actually exists in the official repository.
        # This runs only in the explicitly enabled operator CLI, never the API.
        self.git('-c','http.sslVerify=true','-c','http.followRedirects=false','fetch','--no-tags',OFFICIAL,self.revision)
        if self.git('rev-parse','FETCH_HEAD').strip()!=self.revision:raise MaintenanceError('untrusted_revision')
        # Fixed deployment files must be tracked. Untracked overrides cannot supply commands.
        for name in ('compose.mobile.yaml','compose.maintenance.yaml','backend/Dockerfile'):
            if not self.git('ls-files','--error-unmatch',name).strip():raise MaintenanceError('invalid_checkout')
    def run(self,args,env=None):
        try:return subprocess.run(args,cwd=self.repo,env=env,check=True,shell=False,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,timeout=900).stdout
        except (subprocess.SubprocessError,OSError):raise MaintenanceError('supervisor_command_failed') from None
    def git(self,*args):return self.run(['git',*args])
    def compose(self,args,version):
        env=os.environ.copy();env.update(TUBEGO_HOST_DATA_DIR=str(self.data),TUBEGO_BUILD_REVISION=self.revision,TUBEGO_YTDLP_VERSION=version)
        return self.run(['docker','compose','--project-name',self.project,'-f','compose.mobile.yaml','-f','compose.maintenance.yaml',*args],env)
    def apply(self,action,payload,operator_recovery=False):
        if action not in WRITE_ACTIONS:raise MaintenanceError('unsupported_operation')
        if self.git('rev-parse','HEAD').strip()!=self.revision or self.git('status','--porcelain','--untracked-files=no').strip():raise MaintenanceError('checkout_changed')
        if action=='update_backend' and payload['revision']!=self.revision:raise MaintenanceError('revision_not_staged')
        if action=='update_ytdlp' and payload['version'] not in self.versions:raise MaintenanceError('version_not_approved')
        container=self.compose(['ps','-q','api'],self.versions[0]).strip()
        if not re.fullmatch(r'[0-9a-f]{64}',container):raise MaintenanceError('deployment_not_running')
        mounts=json.loads(self.run(['docker','inspect','--format','{{json .Mounts}}',container]))
        if not any(m.get('Destination')=='/data' and m.get('Type')=='bind' and Path(m.get('Source','')).resolve()==self.data for m in mounts):raise MaintenanceError('deployment_data_mismatch')
        before=json.loads(self.compose(['exec','-T','api','python','-c','import json;from tubego_server.maintenance import installed;print(json.dumps(installed()))'],self.versions[0]))
        if action=='update_ytdlp' and before.get('backend_revision')!=self.revision:raise MaintenanceError('revision_not_staged_for_installed_backend')
        version=self.versions[0] if operator_recovery else payload['version'] if action=='update_ytdlp' else before['yt_dlp']
        if not isinstance(version,str) or not VERSION.fullmatch(version):raise MaintenanceError('unknown_installed_version')
        self.compose(['stop','worker'],version)
        if action!='restart' or operator_recovery:self.compose(['build',*SERVICES],version)
        self.compose(['up','-d','--force-recreate',*SERVICES],version)
        observed=None
        for _ in range(30):
            try:
                observed=json.loads(self.compose(['exec','-T','api','python','-c','import json,urllib.request;from tubego_server.maintenance import installed;urllib.request.urlopen("http://127.0.0.1:8000/api/v1/health",timeout=3);print(json.dumps(installed()))'],version));break
            except (MaintenanceError,ValueError):time.sleep(2)
        if not observed or observed.get('yt_dlp')!=version:raise MaintenanceError('deployment_verification_failed')
        if (action!='restart' or operator_recovery) and observed.get('backend_revision')!=self.revision:raise MaintenanceError('deployment_verification_failed')
        return {'installed':observed,'service_healthy':True}

class Supervisor:
    def __init__(self,db,driver):self.db=db;self.driver=driver
    def once(self):
        selected=None
        with self.db.transaction() as conn:
            write_setting(conn,'global','','maintenance_supervisor',{'seen_at':utcnow(),'backend_revisions':[self.driver.revision],'yt_dlp_versions':self.driver.versions})
            for job in jobs(conn):
                if job['action'] not in WRITE_ACTIONS or job['status'] not in ('queued','waiting_worker','running'):continue
                # A previous process may have died after changing deployment. Never replay blindly.
                if job['status']=='running':return False
                gate=read_setting(conn,'global','','maintenance_gate')
                if gate and gate!=job['id']:return False
                try:admin(conn,{'id':job['user_id'],'session_id':job['session_id']})
                except HTTPException:
                    self.finish(conn,job,'failed','administrator_session_unavailable');continue
                if not capabilities(conn)['mutation_enabled']:return False
                if job['action']=='update_backend' and job['payload']['revision']!=self.driver.revision:self.finish(conn,job,'failed','revision_not_staged');continue
                if job['action']=='update_ytdlp' and job['payload']['version'] not in self.driver.versions:self.finish(conn,job,'failed','version_not_approved');continue
                write_setting(conn,'global','','maintenance_gate',job['id'])
                busy=read_setting(conn,'global','','scheduler_lease') or conn.execute("SELECT 1 FROM tasks WHERE status='running' LIMIT 1").fetchone()
                job.update(status='waiting_worker' if busy else 'running',phase='waiting_for_downloads' if busy else 'deploying',progress=0.2 if busy else 0.5);save(conn,job)
                if not busy:selected=job
                break
        if selected is None:return False
        try:result=self.driver.apply(selected['action'],selected['payload']);error=None
        except Exception as exc:result=None;error=exc.code if isinstance(exc,MaintenanceError) else 'operation_failed'
        with self.db.transaction() as conn:
            current=read_setting(conn,'maintenance',selected['id'],'job')
            if not current or current['status']!='running':return False
            current['result']=result;self.finish(conn,current,'failed' if error else 'succeeded',error)
        return True
    def finish(self,conn,job,status,error):
        job.update(status=status,phase='finished',progress=1,error_code=error);save(conn,job);announce(conn,job)
        if not (status=='failed' and error in ('supervisor_command_failed','deployment_verification_failed','operation_failed')) and read_setting(conn,'global','','maintenance_gate')==job['id']:
            conn.execute("DELETE FROM settings WHERE scope='global' AND owner_id='' AND key='maintenance_gate'")
        conn.execute("INSERT INTO audit(actor_user_id,action,target_id,detail_json,created_at) VALUES(?,'maintenance.finished',?,?,?)",(job['user_id'],job['id'],json.dumps({'action':job['action'],'status':status,'error_code':error}),utcnow()))

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo',required=True,help='Operator staged clean official checkout')
    parser.add_argument('--project',required=True,help='Existing Compose deployment project')
    parser.add_argument('--data-dir',required=True,help='Existing shared bind data directory')
    parser.add_argument('--recover-interrupted',action='store_true',help='Explicitly restart/verify a failed or interrupted deployment, then mark the old job failed; never replay its update')
    parser.add_argument('--yt-dlp-version',action='append',required=True,help='Explicit operator-approved version')
    args=parser.parse_args()
    if os.environ.get('TUBEGO_MAINTENANCE_MUTATIONS')!='true':parser.error('Explicit TUBEGO_MAINTENANCE_MUTATIONS=true required')
    root=Path(args.data_dir).resolve()
    if not (root/'tubego.sqlite3').is_file():parser.error('Existing deployment database required; no new database is created')
    with (root/'maintenance-supervisor.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        driver=ComposeDriver(args.repo,args.project,root,args.yt_dlp_version)
        db=Database(root/'tubego.sqlite3');supervisor=Supervisor(db,driver)
        if args.recover_interrupted:
            driver.apply('restart',{},operator_recovery=True)
            with db.transaction() as conn:
                gate=read_setting(conn,'global','','maintenance_gate')
                job=read_setting(conn,'maintenance',gate,'job') if gate else None
                if job:supervisor.finish(conn,job,'failed','operator_recovered_interrupted_operation')
            return
        while True:supervisor.once();time.sleep(2)

if __name__=='__main__':main()
