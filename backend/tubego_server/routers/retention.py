from datetime import timedelta
import uuid
from fastapi import APIRouter,Depends,HTTPException,Request,Query
from pydantic import BaseModel,ConfigDict,StrictBool,Field
from tubego_server.auth import require_admin,utcnow
from tubego_server.delivery import write_setting,read_setting,device_scope
from tubego_server.retention import preserves_server_files,sweep_confirmed
from tubego_server.retention_policy import policy,clock,timestamp,expired_candidates,sweep_expired,MAX_HOURS

router=APIRouter(tags=['administrative retention'])


def current_admin(conn,principal):
    scope,_=device_scope(conn,principal)
    role=conn.execute('SELECT role FROM users WHERE id=?',(scope.user_id,)).fetchone()[0]
    if role!='admin':raise HTTPException(403,'Administrator required')


class RetentionChoice(BaseModel):
    model_config=ConfigDict(extra='forbid')
    preserve_server_files:StrictBool


@router.get('/admin/users/retention')
def users(request:Request,principal=Depends(require_admin),after:str=Query('',max_length=128),limit:int=Query(50,ge=1,le=100)):
    with request.app.state.db.transaction() as conn:
        current_admin(conn,principal)
        rows=conn.execute('SELECT id,email,status FROM users WHERE id>? ORDER BY id LIMIT ?',(after,limit+1)).fetchall()
        return {'next_cursor':rows[limit-1]['id'] if len(rows)>limit else None,'items':[{'id':row['id'],'email':row['email'],'status':row['status'],
            'preserve_server_files':preserves_server_files(conn,row['id'])}
            for row in rows[:limit]]}


@router.put('/admin/users/{user_id}/retention')
def update(user_id:str,body:RetentionChoice,request:Request,principal=Depends(require_admin)):
    with request.app.state.db.transaction() as conn:
        current_admin(conn,principal)
        if not conn.execute('SELECT 1 FROM users WHERE id=?',(user_id,)).fetchone():raise HTTPException(404,'User not found')
        write_setting(conn,'user',user_id,'preserve_server_files',body.preserve_server_files)
        conn.execute("INSERT INTO audit(actor_user_id,action,target_id,detail_json,created_at) VALUES(?,'user.retention_changed',?,?,?)",(principal['id'],user_id,body.model_dump_json(),utcnow()))
    # Re-read the flag under each cleanup transaction: an intervening admin enable
    # cannot be undone by this older request's sweep.
    removed=0 if body.preserve_server_files else sweep_confirmed(request.app.state.db,request.app.state.settings.data_dir/'media',user_id)
    return {'user_id':user_id,'preserve_server_files':body.preserve_server_files,'removed_server_copies':removed}



class DeadlineChoice(BaseModel):
    model_config=ConfigDict(extra='forbid')
    absolute_hours:int=Field(strict=True,ge=1,le=MAX_HOURS)
    delivery_hours:int=Field(strict=True,ge=1,le=MAX_HOURS)

class DeadlineUpdate(DeadlineChoice):
    preview_token:str=Field(min_length=32,max_length=32,pattern=r'^[0-9a-f]{32}$')
    confirm_immediate_deletion:StrictBool=False


@router.get('/admin/retention/deadlines')
def get_deadlines(request:Request,principal=Depends(require_admin)):
    with request.app.state.db.transaction() as conn:
        current_admin(conn,principal)
        return dict(policy(conn),server_time=clock().isoformat())


@router.post('/admin/retention/deadlines/preview')
def preview_deadlines(body:DeadlineChoice,request:Request,principal=Depends(require_admin)):
    with request.app.state.db.transaction() as conn:
        current_admin(conn,principal)
        now=clock();config=body.model_dump();ids=sorted(expired_candidates(conn,config,now));token=uuid.uuid4().hex
        write_setting(conn,'user',principal['id'],'retention_preview',{'token':token,'config':config,'ids':ids,'expires_at':(now+timedelta(minutes=5)).isoformat()})
        return dict(config,immediate_deletions=len(ids),preview_token=token,server_time=now.isoformat())


@router.put('/admin/retention/deadlines')
def update_deadlines(body:DeadlineUpdate,request:Request,principal=Depends(require_admin)):
    with request.app.state.db.transaction() as conn:
        current_admin(conn,principal)
        now=clock();config={'absolute_hours':body.absolute_hours,'delivery_hours':body.delivery_hours}
        preview=read_setting(conn,'user',principal['id'],'retention_preview')
        if (not preview or preview.get('token')!=body.preview_token or preview.get('config')!=config
            or not timestamp(preview.get('expires_at')) or timestamp(preview['expires_at'])<=now):
            raise HTTPException(409,'Preview the impact again before saving')
        ids=sorted(expired_candidates(conn,config,now))
        if ids!=preview.get('ids'):raise HTTPException(409,'Retention impact changed; preview again')
        if ids and not body.confirm_immediate_deletion:raise HTTPException(409,'Immediate deletion must be confirmed')
        write_setting(conn,'global','','retention_deadlines',config)
        write_setting(conn,'user',principal['id'],'retention_preview',None)
        conn.execute("INSERT INTO audit(actor_user_id,action,detail_json,created_at) VALUES(?,'retention.deadlines_changed',?,?)",(principal['id'],body.model_dump_json(),now.isoformat()))
    removed=sweep_expired(request.app.state.db,request.app.state.settings.data_dir/'media')
    return dict(config,removed_server_copies=removed)
