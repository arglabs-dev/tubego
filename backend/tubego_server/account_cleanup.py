"""Durable owned-directory cleanup. Never traverse symlinks or foreign paths."""
from contextlib import closing
import json
import os
from pathlib import Path
import stat
import threading
from tubego_server.auth import utcnow

SCOPE='account_cleanup'
CLEANUP_LOCK=threading.RLock()

def _owned_reference(root,user_id,value):
    try:
        path=Path(value);relative=path.relative_to(root.absolute()) if path.is_absolute() else path
        return bool(relative.parts) and relative.parts[0]==user_id and all(part not in ('.','..') for part in relative.parts)
    except ValueError:return False


def enqueue(conn,root,user_id,paths,wait_task_id=None):
    existing=conn.execute("SELECT value_json FROM settings WHERE scope=? AND owner_id=? AND key='media'",(SCOPE,user_id)).fetchone()
    record=json.loads(existing[0]) if existing else {'wait_task_id':wait_task_id,'unsafe_reference':False,'status':'pending','last_error':None}
    record['unsafe_reference'] |= any(not _owned_reference(root,user_id,value) for value in paths if value)
    conn.execute("INSERT INTO settings VALUES (?,?,'media',?,?) ON CONFLICT(scope,owner_id,key) DO UPDATE SET value_json=excluded.value_json,updated_at=excluded.updated_at",(SCOPE,user_id,json.dumps(record),utcnow()))


def _remove(parent,name,stop=None):
    if stop and stop.is_set():raise InterruptedError()
    try:info=os.stat(name,dir_fd=parent,follow_symlinks=False)
    except FileNotFoundError:return
    if stat.S_ISDIR(info.st_mode):
        child=os.open(name,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=parent)
        try:
            for item in os.listdir(child):_remove(child,item,stop)
        finally:os.close(child)
        try:os.rmdir(name,dir_fd=parent)
        except FileNotFoundError:pass
    else:
        # unlink removes the directory entry itself, including dangling symlinks.
        try:os.unlink(name,dir_fd=parent)
        except FileNotFoundError:pass


def remove_owned_directory(root,user_id,stop=None):
    if not user_id or Path(user_id).name!=user_id or user_id in ('.','..'):raise ValueError('Invalid owner')
    try:parent=os.open(root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    except FileNotFoundError:return
    try:_remove(parent,user_id,stop)
    finally:os.close(parent)


def process(database,root,user_id=None,stop=None):
    # The runner and manual retries must never keep stale snapshots after unblock.
    with CLEANUP_LOCK:
        _process(database,root,user_id,stop)

def _process(database,root,user_id=None,stop=None):
    with closing(database.connect()) as conn:
        sql="SELECT owner_id,value_json FROM settings WHERE scope=? AND key='media'";args=[SCOPE]
        if user_id is not None:sql+=' AND owner_id=?';args.append(user_id)
        jobs=conn.execute(sql,args).fetchall()
    for job in jobs:
        record=json.loads(job['value_json']);owner=job['owner_id']
        with closing(database.connect()) as conn:
            lease=conn.execute("SELECT value_json FROM settings WHERE scope='global' AND owner_id='' AND key='scheduler_lease'").fetchone()
            if lease and record.get('wait_task_id')==json.loads(lease[0]).get('task_id'):continue
        try:
            remove_owned_directory(root,owner,stop)
            if record['unsafe_reference']:raise ValueError('Foreign media reference')
        except (OSError,ValueError):
            record['status']='pending';record['last_error']='cleanup_interrupted' if stop and stop.is_set() else 'cleanup_failed'
            with database.transaction() as conn:
                conn.execute("UPDATE settings SET value_json=?,updated_at=? WHERE scope=? AND owner_id=? AND key='media' AND value_json=?",(json.dumps(record),utcnow(),SCOPE,owner,job['value_json']))
        else:
            with database.transaction() as conn:
                conn.execute("DELETE FROM settings WHERE scope=? AND owner_id=? AND key='media' AND value_json=?",(SCOPE,owner,job['value_json']))

class CleanupRunner:
    def __init__(self,database,root,interval=5):
        self.database=database;self.root=root;self.interval=interval;self.stop=threading.Event();self.thread=None
    def start(self):
        if self.interval<=0:return
        self.thread=threading.Thread(target=self.run,name='account-cleanup',daemon=True);self.thread.start()
    def run(self):
        while not self.stop.wait(self.interval):
            try:process(self.database,self.root,stop=self.stop)
            except Exception:pass # Durable job remains; no raw paths or credentials are logged.
    def close(self):
        self.stop.set()
        if self.thread:self.thread.join(timeout=5)
