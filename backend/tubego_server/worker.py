"""Exclusive durable server downloader. API processes never start or recover workers."""
from contextlib import closing
import argparse
import errno
import fcntl
import json
import math
import os
from pathlib import Path
import signal
import threading
import time
import uuid
from tubego_server.config import Settings
from tubego_server.db import Database
from tubego_server.auth import utcnow
from tubego_server.scheduler import Scheduler, put_setting, setting
from tubego_server.media import MediaError, classify_error, restricted_ytdlp, MESSAGES
from tubego_server.preferences import download_options, quality_notice, verified_output_path
from tubego_server.delivery import publish_ready
from src.disk_guard import DiskGuard, StoragePaused, DiskState, notify_admin_storage


class Cancelled(Exception): pass
class Interrupted(Exception): pass


class Worker:
    def __init__(self,settings,engine_factory=None):
        self.settings=settings; self.database=Database(settings.database_path)
        self.scheduler=Scheduler(self.database); self.lock_fd=None
        self.engine_factory=engine_factory or restricted_ytdlp
        self.stop=threading.Event()
        self.disk=DiskGuard(settings.data_dir,notify=lambda state:notify_admin_storage(state,settings.database_path))
        self.storage_pause=None

    def __enter__(self):
        from src.storage import validate_channel_paths
        validate_channel_paths(os.getenv('TUBEGO_BOT_DOWNLOAD_DIR','downloads'),self.settings.data_dir)
        self.settings.data_dir.mkdir(parents=True,exist_ok=True)
        fd=os.open(self.settings.data_dir/'worker.lock',os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
        try: fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except OSError:
            os.close(fd); raise RuntimeError('Another server worker is active') from None
        self.lock_fd=fd
        try:
            self.database.initialize()
            self.scheduler.recover_after_worker_stopped()
        except BaseException:
            self.__exit__(None,None,None); raise
        return self

    def __exit__(self,*args):
        if self.lock_fd is not None:
            fcntl.flock(self.lock_fd,fcntl.LOCK_UN); os.close(self.lock_fd); self.lock_fd=None

    def _check(self,task,conn=None):
        if self.stop.is_set(): raise Interrupted()
        if conn is None:
            with closing(self.database.connect()) as connection:
                return self._check(task,connection)
        live=conn.execute('''SELECT t.status,u.status AS user_status,u.email_verified_at FROM tasks t
            JOIN users u ON u.id=t.user_id WHERE t.id=?''',(task['id'],)).fetchone()
        lease=setting(conn,'scheduler_lease')
        cancel=conn.execute("SELECT value_json FROM settings WHERE scope='task' AND owner_id=? AND key='cancel_requested'",(task['id'],)).fetchone()
        if (not live or live['status'] not in ('running','paused') or live['user_status']!='approved'
                or not live['email_verified_at'] or (cancel and json.loads(cancel[0]))):
            raise Cancelled()
        if lease!={'task_id':task['id'],'token':task['claim_token']}:
            raise Interrupted()

    def _phase(self,task,phase,progress=None):
        with self.database.transaction() as conn:
            self._check(task,conn)
            put_setting(conn,'task',task['id'],'phase',phase)
            if progress is not None:
                conn.execute('UPDATE tasks SET progress=?,updated_at=? WHERE id=?',(progress,utcnow(),task['id']))

    def _complete(self,task,status,code=None):
        phase={'completed':'ready','failed':'error','cancelled':'cancelled','queued':'pending'}[status]
        with self.database.transaction() as conn:
            # Terminal state, error, phase, event and lease release are atomic.
            if setting(conn,'scheduler_lease')!={'task_id':task['id'],'token':task['claim_token']}:return
            current=conn.execute('SELECT status FROM tasks WHERE id=?',(task['id'],)).fetchone()
            conn.execute("DELETE FROM settings WHERE scope='global' AND owner_id='' AND key='scheduler_lease'")
            if current is None:return
            if current['status'] not in ('running','paused'):return
            put_setting(conn,'task',task['id'],'phase',phase)
            if status!='queued':conn.execute("DELETE FROM settings WHERE scope='task' AND owner_id=? AND key='attempt_active'",(task['id'],))
            message=(MESSAGES.get(code) or {'storage_error':'Server storage operation failed.',
                'conversion_failed':'The local media conversion failed.'}.get(code,'Media processing failed.')) if code else None
            conn.execute("UPDATE tasks SET status=?,error_code=?,error_message=?,progress=CASE WHEN ?='completed' THEN 1 ELSE progress END,updated_at=? WHERE id=?",
                (status,code,message,status,utcnow(),task['id']))
            conn.execute("INSERT INTO events(user_id,kind,payload_json,created_at) VALUES(?,'task_updated',?,?)",(task['user_id'],json.dumps({'task_id':task['id'],'status':status,'phase':phase,'error_code':code}),utcnow()))

    def _storage_check(self,task,required_bytes=0):
        state=self.disk.sample(required_bytes)
        if state.paused:
            self.storage_pause=state
            raise StoragePaused(state)

    def _wait_after_pause(self,task,state):
        self.storage_pause=state
        if not state.paused:
            state=DiskState(state.volume,state.total,state.used,state.free,True,'storage_unavailable',state.required_bytes)
            notify_admin_storage(state,self.settings.database_path)
            self.disk.last_reported=None
        try:
            with self.database.transaction() as conn:
                self._check(task,conn)
                conn.execute("UPDATE tasks SET status='paused',updated_at=? WHERE id=?",(utcnow(),task['id']))
                put_setting(conn,'task',task['id'],'phase','paused')
                put_setting(conn,'task',task['id'],'pause_reason','server_storage')
            # Requests/FFmpeg have unwound; no network stream or DB lock is held.
            self.stop.wait(2)
            self.disk.wait(check_cancel=lambda:self._check(task),required_bytes=state.required_bytes)
            with self.database.transaction() as conn:
                self._check(task,conn)
                conn.execute("UPDATE tasks SET status='running',updated_at=? WHERE id=?",(utcnow(),task['id']))
                put_setting(conn,'task',task['id'],'phase','analyzing')
                conn.execute("DELETE FROM settings WHERE scope='task' AND owner_id=? AND key='pause_reason'",(task['id'],))
            self.storage_pause=None
            return True
        except Cancelled:self._complete(task,'cancelled')
        except Interrupted:self._complete(task,'queued')
        return False

    @staticmethod
    def _no_space(error):
        if isinstance(error,OSError) and error.errno==errno.ENOSPC:return True
        cause=getattr(error,'__cause__',None)
        details=getattr(error,'exc_info',None)
        if details and len(details)>1:cause=details[1]
        return bool(cause is not error and cause and Worker._no_space(cause)) or 'No space left on device' in str(error)

    def _queued_storage_phase(self,paused):
        with self.database.transaction() as conn:
            rows=conn.execute("SELECT t.id,s.value_json,p.value_json AS phase FROM tasks t LEFT JOIN settings s ON s.scope='task' AND s.owner_id=t.id AND s.key='pause_reason' LEFT JOIN settings p ON p.scope='task' AND p.owner_id=t.id AND p.key='phase' WHERE t.status='queued'").fetchall()
            for row in rows:
                reason=json.loads(row['value_json']) if row['value_json'] else None
                if paused and (reason!='server_storage' or row['phase']!=json.dumps('paused')):
                    put_setting(conn,'task',row['id'],'phase','paused')
                    put_setting(conn,'task',row['id'],'pause_reason','server_storage')
                elif not paused and reason=='server_storage':
                    put_setting(conn,'task',row['id'],'phase','pending')
                    conn.execute("DELETE FROM settings WHERE scope='task' AND owner_id=? AND key='pause_reason'",(row['id'],))

    def run_once(self):
        if self.lock_fd is None:raise RuntimeError('Acquire exclusive worker lock first')
        state=self.disk.sample()
        self._queued_storage_phase(state.paused)
        if state.paused:return False
        task=self.scheduler.claim()
        if task is None:return False
        while True:
            try:
                self._execute(task)
                self._complete(task,'completed')
            except StoragePaused as error:
                if self._wait_after_pause(task,error.state):continue
                break
            except Cancelled:
                self._complete(task,'cancelled')
            except Interrupted:
                self._complete(task,'queued')
            except MediaError as error:
                self._complete(task,'failed',error.code)
            except OSError as error:
                if error.errno==errno.ENOSPC:
                    if self._wait_after_pause(task,self.disk.sample()):continue
                    break
                self._complete(task,'failed','storage_error')
            except Exception as error:
                if self.storage_pause is not None or self._no_space(error):
                    if self._wait_after_pause(task,self.storage_pause or self.disk.sample()):continue
                    break
                try:self._check(task)
                except Cancelled:
                    self._complete(task,'cancelled');break
                except Interrupted:
                    self._complete(task,'queued');break
                # No trace or raw diagnostic can expose a signed URL or local path.
                code='conversion_failed' if type(error).__name__=='PostProcessingError' else classify_error(error)
                self._complete(task,'failed',code)
            break
        return True

    def _execute(self,task):
        with self.database.transaction() as conn:
            self._check(task,conn)
            active=conn.execute("SELECT value_json FROM settings WHERE scope='task' AND owner_id=? AND key='attempt_active'",(task['id'],)).fetchone()
            if not active:
                conn.execute('UPDATE tasks SET attempts=attempts+1,updated_at=? WHERE id=?',(utcnow(),task['id']))
                put_setting(conn,'task',task['id'],'attempt_active',True)
            resource=dict(conn.execute('SELECT * FROM resources WHERE id=? AND user_id=?',(task['resource_id'],task['user_id'])).fetchone())
        user_id=str(uuid.UUID(task['user_id'])); rid=str(uuid.UUID(task['resource_id']))
        root=(self.settings.data_dir/'media').resolve()
        directory=root/user_id/rid
        root.mkdir(parents=True,exist_ok=True)
        parent=os.open(root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        try:
            for component in (user_id,rid):
                try:os.mkdir(component,mode=0o700,dir_fd=parent)
                except FileExistsError:pass
                child=os.open(component,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=parent)
                os.close(parent);parent=child
        finally:os.close(parent)
        selection='audio' if resource['media_format']=='audio' else resource['quality']
        self._phase(task,'analyzing')
        last_update=0
        def progress(info):
            nonlocal last_update
            self._check(task)
            total=info.get('total_bytes') or info.get('total_bytes_estimate') or 0
            self._storage_check(task,max(0,total-info.get('downloaded_bytes',0)))
            if time.monotonic()-last_update<0.25 and info.get('status')!='finished': return
            total=info.get('total_bytes') or info.get('total_bytes_estimate')
            done=info.get('downloaded_bytes',0)
            fraction=min(0.99,max(0,float(done)/float(total))) if total else 0
            self._phase(task,'downloading',fraction)
            last_update=time.monotonic()
        def postprocess(info):
            self._check(task)
            # Reserve space for local conversion before invoking an opaque ffmpeg process.
            required=sum(f.stat().st_size for f in directory.iterdir() if f.is_file())*2
            self._storage_check(task,required)
            self._phase(task,'postprocessing')
        options={**download_options(selection),'outtmpl':str(directory/'media.%(ext)s'),
            'noplaylist':True,'quiet':True,'noprogress':True,'no_warnings':True,
            'socket_timeout':15,'retries':0,'fragment_retries':0,'extractor_retries':0,
            'continuedl':True,'overwrites':False,'progress_hooks':[progress],
            'postprocessor_hooks':[postprocess]}
        with self.engine_factory(options) as engine:
            original=engine.urlopen
            def guarded_request(request):
                self._check(task)
                self._storage_check(task)
                return original(request)
            engine.urlopen=guarded_request
            info=engine.extract_info(resource['source_url'],download=False)
            self._check(task)
            if not isinstance(info,dict) or 'entries' in info or info.get('_type') in ('playlist','multi_video'):
                raise MediaError('unsupported')
            # Finalized metadata and local file remain separate from publication.
            with self.database.transaction() as conn:
                self._check(task,conn)
                title=info.get('title'); duration=info.get('duration')
                conn.execute('UPDATE resources SET title=?,duration_seconds=?,updated_at=? WHERE id=?',
                    (title[:512] if isinstance(title,str) else None,duration if isinstance(duration,(int,float)) and not isinstance(duration,bool) and math.isfinite(duration) and duration>=0 else None,utcnow(),rid))
                from tubego_server.resource_identity import remember
                remember(conn,task['user_id'],resource['source_url'],info,rid)
                put_setting(conn,'resource',rid,'quality_notice',quality_notice(selection,info.get('height')))
            self._phase(task,'downloading')
            # Process this already extracted result instead of repeating source analysis.
            final_info=engine.process_ie_result(info,download=True)
        self._check(task)
        self._storage_check(task)
        output=verified_output_path(final_info,directory)
        # File has been finalized by yt-dlp/FFmpeg. Publish only after a transaction
        # check of both cancellation and lease, including the expensive digest race.
        publish_ready(self.database,root,rid,str(output),
            precondition=lambda conn,resource:self._check(task,conn))


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--once',action='store_true');args=parser.parse_args()
    with Worker(Settings.from_env()) as worker:
        def shutdown(*args): worker.stop.set()
        signal.signal(signal.SIGTERM,shutdown);signal.signal(signal.SIGINT,shutdown)
        while not worker.stop.is_set():
            worked=worker.run_once()
            if args.once: break
            if not worked: worker.stop.wait(2)


if __name__=='__main__':main()
