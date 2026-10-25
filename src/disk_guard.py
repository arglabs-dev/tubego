"""Download-volume protection shared by Telegram and mobile; stdlib only."""
from dataclasses import dataclass
from datetime import datetime,timezone
import json
from pathlib import Path
import shutil
import sqlite3
import time


@dataclass(frozen=True)
class DiskState:
    volume:str
    total:int
    used:int
    free:int
    paused:bool
    reason:str|None
    required_bytes:int=0


class StoragePaused(Exception):
    def __init__(self,state):
        self.state=state
        super().__init__('Download paused until server storage is available')


class DiskGuard:
    def __init__(self,path,notify=None,usage=None):
        self.path=Path(path);self.notify=notify;self.usage=usage or shutil.disk_usage
        self.last_reported=None

    def sample(self,required_bytes=0):
        path=self.path
        while not path.exists() and path!=path.parent:path=path.parent
        try:
            usage=self.usage(path)
            needed=max(1024*1024,int(required_bytes or 0))
            reason='threshold_90_percent' if usage.total<=0 or usage.used*10>=usage.total*9 else ('insufficient_space' if usage.free<needed else None)
            state=DiskState(str(path.stat().st_dev),usage.total,usage.used,usage.free,bool(reason),reason,needed)
        except OSError:
            state=DiskState('unavailable',0,0,0,True,'storage_unavailable')
        transition=(state.volume,state.paused,state.reason)
        if self.notify and transition!=self.last_reported:
            if self.notify(state) is not False:self.last_reported=transition
        return state

    def check(self,required_bytes=0):
        state=self.sample(required_bytes)
        if state.paused:raise StoragePaused(state)
        return state

    def wait(self,check_cancel=None,on_pause=None,on_resume=None,required_bytes=0,interval=2):
        """Wait outside DB/network locks. Caller defines cancellation semantics."""
        paused=False
        while True:
            if check_cancel:check_cancel()
            state=self.sample(required_bytes)
            if not state.paused:
                if paused and on_resume:on_resume()
                return state
            if not paused and on_pause:on_pause(state)
            paused=True
            time.sleep(interval)


def notify_admin_storage(state,database_path):
    """Durable threshold transitions, addressed exclusively to approved admins.

    A pre-existing mobile database is required; the bot never initializes/migrates it.
    The stored state deduplicates polls and restarts for a shared physical volume.
    """
    path=Path(database_path)
    if not path.exists():return False
    try:
        with sqlite3.connect(path,timeout=15) as conn:
            conn.execute('BEGIN IMMEDIATE')
            key='disk_guard:'+state.volume
            try:
                row=conn.execute("SELECT value_json FROM settings WHERE scope='global' AND owner_id='' AND key=?",(key,)).fetchone()
                previous=json.loads(row[0]) if row else None
                timestamp=datetime.now(timezone.utc).isoformat()
                current={'paused':state.paused,'reason':state.reason}
                conn.execute("""INSERT INTO settings(scope,owner_id,key,value_json,updated_at) VALUES('global','',?,?,?)
                    ON CONFLICT(scope,owner_id,key) DO UPDATE SET value_json=excluded.value_json,updated_at=excluded.updated_at""",(key,json.dumps(current),timestamp))
                if state.paused and (not previous or not previous.get('paused')):
                    payload=json.dumps({'reason':state.reason,'used_bytes':state.used,'total_bytes':state.total,'free_bytes':state.free})
                    admins=conn.execute("SELECT id FROM users WHERE role='admin' AND status='approved' AND email_verified_at IS NOT NULL").fetchall()
                    for admin in admins:
                        conn.execute("INSERT INTO events(user_id,kind,payload_json,created_at) VALUES(?,'server_storage_paused',?,?)",(admin[0],payload,timestamp))
            except sqlite3.OperationalError:
                # Mobile initialization may not have finished; retry on the next probe.
                conn.rollback()
                return False
        return True
    except sqlite3.Error:
        return False
