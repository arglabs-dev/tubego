"""Explicit server re-request and device-specific deliberate-deletion approval."""
import json
import uuid
from uuid import UUID
from fastapi import APIRouter,Depends,HTTPException,Request
from pydantic import BaseModel
from tubego_server.auth import require_approved,utcnow
from tubego_server.delivery import device_scope,read_setting,write_setting
from tubego_server.tasks import task_value
from tubego_server.resource_cleanup import pending

router=APIRouter(tags=['resource recovery'])
class Recovery(BaseModel):
    request_id:UUID
    approve_redownload:bool=False

@router.post('/resources/{resource_id}/request-again')
def recover(resource_id:str,body:Recovery,request:Request,principal=Depends(require_approved)):
    now=utcnow();payload={'resource_id':resource_id,'approve_redownload':body.approve_redownload}
    with request.app.state.db.transaction() as conn:
        scope,did=device_scope(conn,principal);resource=scope.resource(resource_id)
        prior=conn.execute('SELECT * FROM commands WHERE id=?',(str(body.request_id),)).fetchone()
        if prior:
            if prior['user_id']!=scope.user_id:raise HTTPException(404,'Not found')
            if prior['device_id']!=did or prior['kind']!='resource.request_again' or json.loads(prior['payload_json'])!=payload:
                raise HTTPException(409,'Request identifier was already used for another command')
            return json.loads(prior['result_json'])
        if pending(conn,resource_id):raise HTTPException(409,{'code':'cleanup_pending','message':'Wait for server cleanup before requesting this file again'})
        own=conn.execute('SELECT * FROM deliveries WHERE resource_id=? AND device_id=?',(resource_id,did)).fetchone()
        tombstone=own['deleted_at'] if own else read_setting(conn,'resource',resource_id,'deliberately_deleted')
        if (tombstone or (own and own['status']=='approval_required')) and not body.approve_redownload:
            raise HTTPException(409,{'code':'approval_required','message':'Confirm downloading a deliberately deleted resource'})
        available=bool(resource['ready_at'] and not resource['server_deleted_at'] and resource['server_path'])
        devices=conn.execute('SELECT id FROM devices WHERE user_id=? AND revoked_at IS NULL',(scope.user_id,)).fetchall()
        for device in devices:
            target=device['id'];delivery=conn.execute('SELECT * FROM deliveries WHERE resource_id=? AND device_id=?',(resource_id,target)).fetchone()
            # Fully confirmed copies remain complete. Publication checks their
            # checksum before scheduling a changed generation of the variant.
            if delivery and delivery['status']=='complete' and not delivery['deleted_at']:continue
            deleted=delivery['deleted_at'] if delivery else read_setting(conn,'resource',resource_id,'deliberately_deleted')
            approval=deleted or (delivery and delivery['status']=='approval_required')
            if target==did and body.approve_redownload:approval=False;deleted=None
            status='approval_required' if approval else 'pending'
            transferred=delivery['downloaded_bytes'] if delivery and not approval and not delivery['deleted_at'] else 0
            conn.execute("""INSERT INTO deliveries(resource_id,device_id,status,downloaded_bytes,deleted_at,updated_at)
                VALUES(?,?,?,?,?,?) ON CONFLICT(resource_id,device_id) DO UPDATE SET
                status=excluded.status,downloaded_bytes=excluded.downloaded_bytes,
                deleted_at=excluded.deleted_at,confirmed_at=NULL,updated_at=excluded.updated_at""",(resource_id,target,status,transferred,deleted,now))
            conn.execute("INSERT INTO events(user_id,device_id,kind,payload_json,created_at) VALUES(?,?,'resource_requested',?,?)",(scope.user_id,target,json.dumps({'resource_id':resource_id,'delivery_status':status,'silent':True}),now))
        task=conn.execute("SELECT * FROM tasks WHERE resource_id=? AND user_id=? AND status IN ('queued','running','paused') ORDER BY created_at DESC LIMIT 1",(resource_id,scope.user_id)).fetchone()
        if not available and task is None:
            tid=str(uuid.uuid4())
            conn.execute('INSERT INTO tasks(id,user_id,resource_id,created_at,updated_at) VALUES(?,?,?,?,?)',(tid,scope.user_id,resource_id,now,now))
            write_setting(conn,'task',tid,'phase','pending')
            task=conn.execute('SELECT * FROM tasks WHERE id=?',(tid,)).fetchone()
        own=conn.execute('SELECT status FROM deliveries WHERE resource_id=? AND device_id=?',(resource_id,did)).fetchone()
        result={'resource_id':resource_id,'server_available':available,'task_id':task['id'] if task else None,
                'task':task_value(conn,task) if task else None,'delivery_status':own['status'],
                'status':'available' if available else 'queued','approved_redownload':body.approve_redownload}
        conn.execute("INSERT INTO commands(id,user_id,device_id,kind,payload_json,status,result_json,created_at,updated_at) VALUES(?,?,?,'resource.request_again',?,'complete',?,?,?)",(str(body.request_id),scope.user_id,did,json.dumps(payload),json.dumps(result),now,now))
        conn.execute("INSERT INTO audit(actor_user_id,action,target_id,detail_json,created_at) VALUES(?,'resource.requested_again',?,?,?)",(scope.user_id,resource_id,json.dumps({'device_id':did,'approved':body.approve_redownload}),now))
    return result
