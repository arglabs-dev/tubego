from fastapi import APIRouter,Depends,HTTPException,Request,Query
from pydantic import BaseModel,ConfigDict,StrictBool
from tubego_server.auth import require_admin,utcnow
from tubego_server.delivery import write_setting
from tubego_server.delivery import device_scope
from tubego_server.retention import preserves_server_files,sweep_confirmed

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
