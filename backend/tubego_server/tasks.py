"""Private task mutations. Network validation runs before SQLite transactions."""
from contextlib import closing
import hashlib
import json
import uuid
from fastapi import HTTPException
from tubego_server.auth import utcnow
from tubego_server.ownership import LibraryScope
from tubego_server.preferences import read_preferences, resolve_selection, resource_fields
from tubego_server.media import normalize_url
from tubego_server.scheduler import put_setting


def live_scope(conn, principal):
    current=conn.execute('''SELECT u.* FROM users u JOIN sessions s ON s.user_id=u.id
        LEFT JOIN devices d ON d.id=s.device_id WHERE u.id=? AND s.id=?
        AND s.revoked_at IS NULL AND s.expires_at>?
        AND (s.device_id IS NULL OR (d.user_id=u.id AND d.revoked_at IS NULL))''',
        (principal['id'],principal['session_id'],utcnow())).fetchone()
    if current is None: raise HTTPException(401,'Session unavailable')
    return LibraryScope(conn,current)


def task_value(conn, task):
    row=conn.execute("SELECT value_json FROM settings WHERE scope='task' AND owner_id=? AND key='phase'",(task['id'],)).fetchone()
    phase=json.loads(row[0]) if row else {'queued':'pending','completed':'ready','failed':'error','cancelled':'cancelled'}.get(task['status'],task['status'])
    notice=conn.execute("SELECT value_json FROM settings WHERE scope='resource' AND owner_id=? AND key='quality_notice'",(task['resource_id'],)).fetchone()
    return {key:task[key] for key in ('id','resource_id','status','priority','progress','attempts','error_code','error_message','created_at','updated_at')} | {'phase':phase,'quality_notice':json.loads(notice[0]) if notice else None}


def submit(database, principal, url, selection=None, request_id=None, resolver=None):
    # URL validation performs DNS; never call it while holding a DB write lock.
    canonical=normalize_url(url, resolver) if resolver else normalize_url(url)
    selection=resolve_selection(read_preferences(database,principal['id']),selection)
    fields=resource_fields(selection)
    source_key=hashlib.sha256((canonical+'\n'+fields['media_format']+'\n'+fields['quality']).encode()).hexdigest()
    with database.transaction() as conn:
        scope=live_scope(conn,principal)
        existing=conn.execute('''SELECT r.id FROM resources r WHERE user_id=? AND source_key=? ORDER BY created_at LIMIT 1''',(scope.user_id,source_key)).fetchone()
        if request_id:
            key='submission:'+str(request_id)
            previous=conn.execute("SELECT value_json FROM settings WHERE scope='user' AND owner_id=? AND key=?",(scope.user_id,key)).fetchone()
            if previous:
                data=json.loads(previous[0])
                if data['source_key']!=source_key: raise HTTPException(409,'Request ID already used for another resource')
                existing={'id':data['resource_id']}
        if existing:
            task=conn.execute('SELECT * FROM tasks WHERE resource_id=? AND user_id=? ORDER BY created_at DESC LIMIT 1',(existing['id'],scope.user_id)).fetchone()
            if request_id:
                put_setting(conn,'user',scope.user_id,key,{'source_key':source_key,'resource_id':existing['id']})
            return {'resource_id':existing['id'],'task':task_value(conn,task) if task else None,'existing':True,'task_id':task['id'] if task else None,'status':task['status'] if task else 'history'}
        rid,tid=str(uuid.uuid4()),str(uuid.uuid4()); timestamp=utcnow()
        conn.execute('''INSERT INTO resources(id,user_id,source_url,source_key,media_format,quality,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?)''',(rid,scope.user_id,canonical,source_key,fields['media_format'],fields['quality'],timestamp,timestamp))
        conn.execute('INSERT INTO tasks(id,user_id,resource_id,created_at,updated_at) VALUES(?,?,?,?,?)',(tid,scope.user_id,rid,timestamp,timestamp))
        if request_id:
            put_setting(conn,'user',scope.user_id,key,{'source_key':source_key,'resource_id':rid})
        task=conn.execute('SELECT * FROM tasks WHERE id=?',(tid,)).fetchone()
        return {'resource_id':rid,'task':task_value(conn,task),'existing':False,'task_id':tid,'status':'queued'}


def action(database, principal, task_id, kind):
    with database.transaction() as conn:
        scope=live_scope(conn,principal); task=scope.task(task_id); resource=scope.resource(task['resource_id'])
        ready=resource['ready_at'] and not resource['server_deleted_at']
        if kind=='cancel':
            if task['status'] in ('completed','failed') or ready: raise HTTPException(409,'Task is no longer cancellable')
            if task['status']=='queued':
                conn.execute("UPDATE tasks SET status='cancelled',updated_at=? WHERE id=?",(utcnow(),task_id))
                put_setting(conn,'task',task_id,'phase','cancelled')
            elif task['status']=='running':
                put_setting(conn,'task',task_id,'cancel_requested',True)
                put_setting(conn,'task',task_id,'phase','cancelling')
        elif kind=='retry':
            if ready or task['status']=='completed': raise HTTPException(409,'Resource is already ready')
            if task['status'] in ('failed','cancelled'):
                conn.execute("UPDATE tasks SET status='queued',error_code=NULL,error_message=NULL,updated_at=? WHERE id=?",(utcnow(),task_id))
                conn.execute("DELETE FROM settings WHERE scope='task' AND owner_id=? AND key='cancel_requested'",(task_id,))
                put_setting(conn,'task',task_id,'phase','pending')
            # Repeated retry while queued/running returns the same task.
        elif kind=='priority':
            if task['status']!='queued': raise HTTPException(409,'Only queued tasks can be prioritized')
            previous=conn.execute("SELECT COALESCE(MAX(priority),0) FROM tasks WHERE user_id=? AND status='queued'",(scope.user_id,)).fetchone()[0]
            # The explicitly selected video becomes next within this user's queue.
            conn.execute('UPDATE tasks SET priority=?,updated_at=? WHERE id=?',(previous+1,utcnow(),task_id))
        else: raise ValueError('Invalid task action')
        current=conn.execute('SELECT * FROM tasks WHERE id=?',(task_id,)).fetchone()
        payload=task_value(conn,current)
        conn.execute("INSERT INTO events(user_id,kind,payload_json,created_at) VALUES(?,'task_updated',?,?)",(scope.user_id,json.dumps(payload),utcnow()))
        return payload
