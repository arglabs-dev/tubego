"""Explicit, idempotent all-device deletion, preserving private resource history."""
import json
from typing import Literal
from uuid import UUID
from fastapi import APIRouter,Depends,HTTPException,Request
from pydantic import BaseModel
from tubego_server.auth import require_approved,utcnow
from tubego_server.delivery import device_scope,read_setting,write_setting
from tubego_server.resource_cleanup import schedule,process,pending,LOCK

router=APIRouter(tags=['resource deletion'])
class Deletion(BaseModel):
    scope:Literal['devices','devices_and_server']
    request_id:UUID

@router.post('/resources/{resource_id}/delete')
def delete(resource_id:str,body:Deletion,request:Request,principal=Depends(require_approved)):
    db=request.app.state.db;now=utcnow();request_id=str(body.request_id)
    payload={'resource_id':resource_id,'scope':body.scope}
    with LOCK,db.transaction() as conn:
        scope,did=device_scope(conn,principal)
        resource=scope.resource(resource_id)
        prior=conn.execute('SELECT * FROM commands WHERE id=?',(request_id,)).fetchone()
        if prior:
            if prior['user_id']!=scope.user_id:raise HTTPException(404,'Not found')
            if prior['kind']!='resource.delete' or json.loads(prior['payload_json'])!=payload:
                raise HTTPException(409,'Request identifier was already used for another command')
            return json.loads(prior['result_json'])
        write_setting(conn,'resource',resource_id,'deliberately_deleted',now)
        devices=conn.execute('SELECT id FROM devices WHERE user_id=?',(scope.user_id,)).fetchall()
        for device in devices:
            conn.execute("""INSERT INTO deliveries(resource_id,device_id,status,deleted_at,updated_at)
                VALUES(?,?,'deleted',?,?) ON CONFLICT(resource_id,device_id) DO UPDATE SET
                status='deleted',deleted_at=excluded.deleted_at,downloaded_bytes=0,confirmed_at=NULL,updated_at=excluded.updated_at""",
                (resource_id,device['id'],now,now))
            conn.execute("DELETE FROM settings WHERE scope='delivery' AND owner_id=? AND key='confirmed_sha256'",(device['id']+':'+resource_id,))
            conn.execute("INSERT INTO events(user_id,device_id,kind,payload_json,created_at) VALUES(?,?,'resource.deleted',?,?)",(scope.user_id,device['id'],json.dumps({'resource_id':resource_id,'deleted_at':now,'scope':body.scope}),now))
        if body.scope=='devices_and_server':
            tasks=conn.execute('SELECT id FROM tasks WHERE resource_id=? AND user_id=?',(resource_id,scope.user_id)).fetchall()
            lease=read_setting(conn,'global','','scheduler_lease')
            wait=lease.get('task_id') if lease and any(t['id']==lease.get('task_id') for t in tasks) else None
            schedule(conn,request.app.state.settings.data_dir/'media',scope.user_id,resource_id,resource['server_path'],wait)
            conn.execute("UPDATE tasks SET status='cancelled',error_code='resource_deleted',error_message='Resource deleted by owner',updated_at=? WHERE resource_id=? AND user_id=? AND status IN ('queued','running','paused')",(now,resource_id,scope.user_id))
            for task in tasks:write_setting(conn,'task',task['id'],'cancel_requested',True)
            conn.execute('UPDATE resources SET server_deleted_at=?,server_path=NULL,updated_at=? WHERE id=?',(now,now,resource_id))
        result={'resource_id':resource_id,'scope':body.scope,'deleted_at':now,'status':'deleted'}
        conn.execute("INSERT INTO commands(id,user_id,device_id,kind,payload_json,status,result_json,created_at,updated_at) VALUES(?,?,?,'resource.delete',?,'complete',?,?,?)",(request_id,scope.user_id,did,json.dumps(payload),json.dumps(result),now,now))
        conn.execute("INSERT INTO audit(actor_user_id,action,target_id,detail_json,created_at) VALUES(?,'resource.deleted',?,?,?)",(scope.user_id,resource_id,json.dumps({'scope':body.scope}),now))
    process(db,request.app.state.settings.data_dir/'media',resource_id)
    return result
