"""Permanent private metadata and per-resource queue ordering without schema changes."""
import json
from tubego_server.auth import utcnow
from tubego_server.scheduler import put_setting


def value(conn,resource_id,key,default=None):
    row=conn.execute("SELECT value_json FROM settings WHERE scope='resource' AND owner_id=? AND key=?",(resource_id,key)).fetchone()
    return json.loads(row[0]) if row else default


def priority(conn,resource_id):
    return value(conn,resource_id,'queue_priority',0)


def prioritize(conn,scope,resource_id,request_id=None):
    scope.resource(resource_id)
    if request_id:
        row=conn.execute("SELECT value_json FROM settings WHERE scope='user' AND owner_id=? AND key=?",(scope.user_id,'resource_priority:'+str(request_id))).fetchone()
        if row:
            previous=json.loads(row[0])
            if previous['resource_id']!=resource_id:
                from fastapi import HTTPException
                raise HTTPException(409,'Request ID already used for another resource')
            return previous
    # SQLite write transaction serializes this sequence across one user's devices.
    rows=conn.execute("SELECT s.value_json FROM settings s JOIN resources r ON r.id=s.owner_id WHERE s.scope='resource' AND s.key='queue_priority' AND r.user_id=?",(scope.user_id,)).fetchall()
    task_max=conn.execute('SELECT COALESCE(MAX(priority),0) FROM tasks WHERE user_id=?',(scope.user_id,)).fetchone()[0]
    next_priority=max([int(json.loads(row[0])) for row in rows]+[task_max,0])+1
    put_setting(conn,'resource',resource_id,'queue_priority',next_priority)
    task=conn.execute("SELECT * FROM tasks WHERE resource_id=? AND user_id=? ORDER BY created_at DESC,id DESC LIMIT 1",(resource_id,scope.user_id)).fetchone()
    if task and task['status']=='queued':
        conn.execute('UPDATE tasks SET priority=?,updated_at=? WHERE id=?',(next_priority,utcnow(),task['id']))
    result={'resource_id':resource_id,'priority':next_priority}
    if request_id:put_setting(conn,'user',scope.user_id,'resource_priority:'+str(request_id),result)
    conn.execute("INSERT INTO events(user_id,kind,payload_json,created_at) VALUES(?,'resource.priority',?,?)",(scope.user_id,json.dumps(result),utcnow()))
    return result


def enrich(conn,row,device_id=None):
    from tubego_server.tasks import task_value
    task=conn.execute('SELECT * FROM tasks WHERE resource_id=? AND user_id=? ORDER BY created_at DESC,id DESC LIMIT 1',(row['id'],row['user_id'])).fetchone()
    delivery=conn.execute('SELECT d.* FROM deliveries d JOIN devices v ON v.id=d.device_id WHERE d.resource_id=? AND d.device_id=? AND v.user_id=? AND v.revoked_at IS NULL',(row['id'],device_id,row['user_id'])).fetchone() if device_id else None
    from tubego_server.resource_revision import current
    return {'revision':current(conn,row['id']),'latest_task':task_value(conn,task) if task else None,
            'device_delivery':{key:delivery[key] for key in ('status','downloaded_bytes','confirmed_at','deleted_at')} if delivery else None,
            'last_opened_at':value(conn,row['id'],'last_opened_at'),
            'priority':priority(conn,row['id']),
            'server_available':bool(row['ready_at'] and row['server_path'] and not row['server_deleted_at'])}
