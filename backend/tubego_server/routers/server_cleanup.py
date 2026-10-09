"""Explicit owner-only or administrator-global cleanup of backend copies."""
import json
from typing import Literal
from uuid import UUID
from fastapi import APIRouter,Depends,HTTPException,Query,Request
from pydantic import BaseModel,ConfigDict,StrictBool
from tubego_server.auth import require_approved,utcnow
from tubego_server.delivery import device_scope,read_setting,write_setting
from tubego_server.resource_cleanup import schedule,process
from tubego_server.routers.retention import current_admin

router=APIRouter(tags=['server cleanup'])
Scope=Literal['own','global']


def authorize(conn,principal,scope):
    library,did=device_scope(conn,principal)
    if scope=='global':current_admin(conn,principal)
    return library.user_id,did


def resources(conn,user_id,scope):
    return conn.execute('SELECT * FROM resources'+(' WHERE user_id=?' if scope=='own' else '')+' ORDER BY id',(user_id,) if scope=='own' else ()).fetchall()


def preview_data(conn,rows):
    known=[row for row in rows if row['server_path'] and not row['server_deleted_at']]
    ids={row['id'] for row in rows}
    active=sum(row['resource_id'] in ids for row in conn.execute("SELECT resource_id FROM tasks WHERE status IN ('queued','running','paused')"))
    jobs=sum(row['owner_id'] in ids for row in conn.execute("SELECT owner_id FROM settings WHERE scope='resource' AND key='cleanup_job'"))
    return {'resources_in_scope':len(rows),'files_known':len(known),
            'bytes_known':sum(row['size_bytes'] or 0 for row in known),
            'sizes_unknown':sum(row['size_bytes'] is None for row in known),
            'in_progress':active,'cleanup_pending':jobs}


@router.get('/server-cleanup/preview')
def preview(request:Request,scope:Scope=Query('own'),principal=Depends(require_approved)):
    with request.app.state.db.transaction() as conn:
        user,_=authorize(conn,principal,scope)
        return {'scope':scope,**preview_data(conn,resources(conn,user,scope))}


class Cleanup(BaseModel):
    model_config=ConfigDict(extra='forbid')
    scope:Scope
    request_id:UUID
    confirmed:StrictBool


@router.post('/server-cleanup')
def clean(body:Cleanup,request:Request,principal=Depends(require_approved)):
    if not body.confirmed:raise HTTPException(400,'Confirm the cleanup scope first')
    db=request.app.state.db;root=request.app.state.settings.data_dir/'media';now=utcnow()
    with db.transaction() as conn:
        user,did=authorize(conn,principal,body.scope)
        prior=conn.execute('SELECT * FROM commands WHERE id=?',(str(body.request_id),)).fetchone()
        payload={'scope':body.scope}
        if prior:
            if prior['user_id']!=user:raise HTTPException(404,'Not found')
            if prior['kind']!='server.cleanup' or json.loads(prior['payload_json'])!=payload:raise HTTPException(409,'Request identifier already used for another action')
            return json.loads(prior['result_json'])
        rows=resources(conn,user,body.scope);summary=preview_data(conn,rows)
        lease=read_setting(conn,'global','','scheduler_lease')
        for resource in rows:
            rid=resource['id'];owner=resource['user_id']
            tasks=conn.execute('SELECT id FROM tasks WHERE resource_id=? AND user_id=?',(rid,owner)).fetchall()
            wait=lease.get('task_id') if lease and any(row['id']==lease.get('task_id') for row in tasks) else None
            schedule(conn,root,owner,rid,resource['server_path'],wait)
            conn.execute("UPDATE tasks SET status='cancelled',error_code='server_cleaned',error_message='Server copy removed by explicit cleanup',updated_at=? WHERE resource_id=? AND user_id=? AND status IN ('queued','running','paused')",(now,rid,owner))
            for task in tasks:write_setting(conn,'task',task['id'],'cancel_requested',True)
            conn.execute('UPDATE resources SET server_path=NULL,server_deleted_at=COALESCE(server_deleted_at,?),updated_at=? WHERE id=?',(now,now,rid))
            # No local tombstone, device deletion or mobile wipe is emitted.
            conn.execute("INSERT INTO events(user_id,kind,payload_json,created_at) VALUES(?,'server_copy_removed',?,?)",(owner,json.dumps({'resource_id':rid,'reason':'explicit_cleanup','silent':True}),now))
        result={'status':'accepted','scope':body.scope,'request_id':str(body.request_id),**summary,'scheduled_resources':len(rows),'cleanup_pending':len(rows)}
        conn.execute("INSERT INTO commands(id,user_id,device_id,kind,payload_json,status,result_json,created_at,updated_at) VALUES(?,?,?,'server.cleanup',?,'complete',?,?,?)",(str(body.request_id),user,did,json.dumps(payload),json.dumps(result),now,now))
        conn.execute("INSERT INTO audit(actor_user_id,action,target_id,detail_json,created_at) VALUES(?,'server.cleaned',?,?,?)",(user,user if body.scope=='own' else 'mobile-library',json.dumps({'scope':body.scope,**summary}),now))
    # Keep large administrative sweeps bounded; the existing durable runner
    # resumes every remaining resource without holding this HTTP response.
    for resource in rows[:64]:process(db,root,resource['id'])
    return result
