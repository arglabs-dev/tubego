"""Durable deletion of one owner's resource directory, without following symlinks."""
from contextlib import closing
import json
import os
from pathlib import Path
import stat
import threading
from tubego_server.auth import utcnow
from tubego_server.delivery import read_setting,write_setting

LOCK=threading.RLock()

def pending(conn,resource_id):
    return read_setting(conn,'resource',resource_id,'cleanup_job') is not None

def schedule(conn,root,user_id,resource_id,path,task_id):
    unsafe=False
    if path:
        try:
            relative=Path(path).relative_to(root.absolute()) if Path(path).is_absolute() else Path(path)
            unsafe=relative.parts[:2]!=(user_id,resource_id) or '..' in relative.parts
        except ValueError:unsafe=True
    write_setting(conn,'resource',resource_id,'cleanup_job',{'user_id':user_id,'wait_task_id':task_id,'unsafe_reference':unsafe,'error':None})

def _remove(parent,name):
    try:info=os.stat(name,dir_fd=parent,follow_symlinks=False)
    except FileNotFoundError:return
    if stat.S_ISDIR(info.st_mode):
        fd=os.open(name,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=parent)
        try:
            for child in os.listdir(fd):_remove(fd,child)
        finally:os.close(fd)
        try:os.rmdir(name,dir_fd=parent)
        except FileNotFoundError:pass
    else:
        try:os.unlink(name,dir_fd=parent)
        except FileNotFoundError:pass

def remove(root,user_id,resource_id):
    for name in (user_id,resource_id):
        if not name or name in ('.','..') or Path(name).name!=name:raise ValueError('Invalid private identity')
    try:root_fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    except FileNotFoundError:return
    try:
        try:owner_fd=os.open(user_id,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=root_fd)
        except FileNotFoundError:return
        try:_remove(owner_fd,resource_id)
        finally:os.close(owner_fd)
    finally:os.close(root_fd)

def process(db,root,resource_id=None):
    with LOCK:
        with closing(db.connect()) as conn:
            sql="SELECT owner_id,value_json FROM settings WHERE scope='resource' AND key='cleanup_job'";args=[]
            if resource_id:sql+=' AND owner_id=?';args.append(resource_id)
            jobs=conn.execute(sql,args).fetchall()
        for row in jobs:
            rid=row['owner_id']
            # A SQLite write transaction fences retries and explicit re-publication
            # across API processes. A stale snapshot never deletes newer media.
            with db.transaction() as conn:
                raw=conn.execute("SELECT value_json FROM settings WHERE scope='resource' AND owner_id=? AND key='cleanup_job'",(rid,)).fetchone()
                if raw is None or raw[0]!=row['value_json']:continue
                job=json.loads(raw[0]);lease=read_setting(conn,'global','','scheduler_lease')
                if lease and job['wait_task_id']==lease.get('task_id'):continue
                try:
                    remove(root,job['user_id'],rid)
                    if job['unsafe_reference']:raise ValueError('Unsafe stored media reference')
                except (OSError,ValueError):
                    job['error']='cleanup_failed'
                    write_setting(conn,'resource',rid,'cleanup_job',job)
                else:
                    conn.execute("DELETE FROM settings WHERE scope='resource' AND owner_id=? AND key='cleanup_job'",(rid,))

class Runner:
    def __init__(self,db,root):self.db=db;self.root=root;self.stop=threading.Event();self.thread=None
    def start(self):
        self.thread=threading.Thread(target=self.run,name='resource-cleanup',daemon=True);self.thread.start()
    def run(self):
        while not self.stop.wait(5):
            try:process(self.db,self.root)
            except Exception:pass # Durable jobs remain for retry; never log private paths.
    def close(self):
        self.stop.set()
        if self.thread:self.thread.join(timeout=5)
