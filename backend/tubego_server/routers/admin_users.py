from contextlib import closing
import json
import secrets
from fastapi import APIRouter,Depends,HTTPException,Request
from tubego_server.auth import require_admin,utcnow,token_hash
from tubego_server.account_cleanup import enqueue,process,SCOPE,CLEANUP_LOCK
from tubego_server.routers.devices import revoke_device

router=APIRouter(prefix='/admin/users',tags=['account administration'])

def _active_admin(conn,admin):
    active=conn.execute("""SELECT 1 FROM users u JOIN sessions s ON s.user_id=u.id
        LEFT JOIN devices d ON d.id=s.device_id WHERE u.id=? AND s.id=?
        AND u.status='approved' AND u.role='admin' AND u.email_verified_at IS NOT NULL
        AND s.revoked_at IS NULL AND s.expires_at>?
        AND (s.device_id IS NULL OR (d.user_id=u.id AND d.revoked_at IS NULL))""",
        (admin['id'],admin['session_id'],utcnow())).fetchone()
    if active is None:raise HTTPException(403,'Administrator session is not active')

def cleanup_state(db,user_id):
    with closing(db.connect()) as conn:
        row=conn.execute("SELECT value_json FROM settings WHERE scope=? AND owner_id=? AND key='media'",(SCOPE,user_id)).fetchone()
    state=json.loads(row[0]) if row else None
    return {'cleanup_pending':state is not None,'cleanup_error':state.get('last_error') if state else None}

@router.get('')
def list_users(request:Request,admin=Depends(require_admin)):
    with request.app.state.db.transaction() as conn:
        _active_admin(conn,admin)
        rows=conn.execute('SELECT id,email,role,status,email_verified_at,created_at FROM users ORDER BY created_at').fetchall()
    return [dict(row,**cleanup_state(request.app.state.db,row['id'])) for row in rows]


def _revoke(request,target,admin,delete):
    db=request.app.state.db;root=request.app.state.settings.data_dir/'media';now=utcnow()
    with db.transaction() as conn:
        _active_admin(conn,admin)
        user=conn.execute('SELECT * FROM users WHERE id=?',(target,)).fetchone()
        if user is None:raise HTTPException(404,'User not found')
        if target==admin['id']:raise HTTPException(409,'Cannot revoke your own administrator account')
        if user['role']=='admin' and user['status']=='approved':
            count=conn.execute("SELECT count(*) FROM users WHERE role='admin' AND status='approved' AND email_verified_at IS NOT NULL").fetchone()[0]
            if count<=1:raise HTTPException(409,'Cannot revoke the last active administrator')
        desired='deleted' if delete else 'blocked'
        if user['status']=='deleted' or (user['status']=='blocked' and not delete):
            if user['status']=='deleted' and not delete:raise HTTPException(409,'Account was deleted')
        else:
            paths=[row['server_path'] for row in conn.execute('SELECT server_path FROM resources WHERE user_id=?',(target,))]
            task_ids=[row['id'] for row in conn.execute('SELECT id FROM tasks WHERE user_id=?',(target,))]
            resource_ids=[row['id'] for row in conn.execute('SELECT id FROM resources WHERE user_id=?',(target,))]
            device_ids=[row['id'] for row in conn.execute('SELECT id FROM devices WHERE user_id=?',(target,))]
            lease=conn.execute("SELECT value_json FROM settings WHERE scope='global' AND owner_id='' AND key='scheduler_lease'").fetchone()
            lease_task=json.loads(lease[0]).get('task_id') if lease else None
            enqueue(conn,root,target,paths,lease_task if lease_task in task_ids else None)
            conn.execute('UPDATE users SET status=?,updated_at=? WHERE id=?',(desired,now,target))
            conn.execute('UPDATE sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL',(now,target))
            for device in device_ids:revoke_device(conn,target,device,admin['id'])
            conn.execute("UPDATE tasks SET status='cancelled',error_code='account_revoked',error_message='Account access revoked',updated_at=? WHERE user_id=? AND status IN ('queued','running')",(now,target))
            for task in task_ids:
                conn.execute("INSERT INTO settings VALUES ('task',?,'cancel_requested','true',?) ON CONFLICT(scope,owner_id,key) DO UPDATE SET value_json='true',updated_at=excluded.updated_at",(task,now))
            conn.execute('UPDATE resources SET server_deleted_at=?,server_path=NULL,updated_at=? WHERE user_id=?',(now,now,target))
            if delete:
                conn.execute('DELETE FROM events WHERE user_id=?',(target,))
                for device in device_ids:
                    conn.execute("INSERT INTO events(user_id,device_id,kind,payload_json,created_at) VALUES (?,?,'device.wipe',?,?)",(target,device,json.dumps({'device_id':device}),now))
                conn.execute('DELETE FROM commands WHERE user_id=?',(target,))
                conn.execute('DELETE FROM verification_tokens WHERE user_id=?',(target,))
                conn.execute('DELETE FROM password_reset_tokens WHERE user_id=?',(target,))
                for scope,ids in [('resource',resource_ids),('task',task_ids),('device',device_ids),('user',[target])]:
                    for identity in ids:conn.execute('DELETE FROM settings WHERE scope=? AND owner_id=?',(scope,identity))
                conn.execute('DELETE FROM resources WHERE user_id=?',(target,))
                conn.execute('DELETE FROM auth_limits WHERE key_hash=?',(token_hash(user['email']),))
                conn.execute("UPDATE users SET email=?,password_hash='disabled',role='user',email_verified_at=NULL,created_at=?,updated_at=? WHERE id=?",('deleted-'+secrets.token_hex(24)+'@deleted.invalid',now,now,target))
                conn.execute("UPDATE devices SET name='Deleted device',platform='unknown',last_seen_at=NULL,created_at=?,revoked_at=? WHERE user_id=?",(now,now,target))
                conn.execute('UPDATE sessions SET expires_at=?,created_at=?,revoked_at=? WHERE user_id=?',(now,now,now,target))
                # Retain opaque audit identities/actions, remove any old personal details.
                conn.execute("UPDATE audit SET detail_json='{}' WHERE actor_user_id=? OR target_id=?",(target,target))
            conn.execute('INSERT INTO audit(actor_user_id,action,target_id,created_at) VALUES (?,?,?,?)',(admin['id'],'account.'+desired,target,now))
    process(db,root,target)
    return {'status':desired,**cleanup_state(db,target)}

@router.post('/{user_id}/block')
def block(user_id:str,request:Request,admin=Depends(require_admin)):return _revoke(request,user_id,admin,False)

@router.delete('/{user_id}')
def delete(user_id:str,request:Request,admin=Depends(require_admin)):return _revoke(request,user_id,admin,True)

@router.post('/{user_id}/unblock')
def unblock(user_id:str,request:Request,admin=Depends(require_admin)):
    with CLEANUP_LOCK, request.app.state.db.transaction() as conn:
        _active_admin(conn,admin)
        if conn.execute("SELECT 1 FROM settings WHERE scope=? AND owner_id=? AND key='media'",(SCOPE,user_id)).fetchone():
            raise HTTPException(409,'Cleanup pending; retry before unblocking')
        user=conn.execute('SELECT * FROM users WHERE id=?',(user_id,)).fetchone()
        if user is None:raise HTTPException(404,'User not found')
        if user['status']!='blocked':raise HTTPException(409,'Only blocked accounts can be unblocked')
        status='approved' if user['email_verified_at'] else 'pending_verification'
        conn.execute('UPDATE users SET status=?,updated_at=? WHERE id=?',(status,utcnow(),user_id))
        conn.execute('INSERT INTO audit(actor_user_id,action,target_id,created_at) VALUES (?,?,?,?)',(admin['id'],'account.unblocked',user_id,utcnow()))
    return {'status':status}

@router.post('/{user_id}/cleanup/retry')
def retry(user_id:str,request:Request,admin=Depends(require_admin)):
    with request.app.state.db.transaction() as conn:
        _active_admin(conn,admin)
        if conn.execute('SELECT 1 FROM users WHERE id=?',(user_id,)).fetchone() is None:raise HTTPException(404,'User not found')
    process(request.app.state.db,request.app.state.settings.data_dir/'media',user_id)
    return cleanup_state(request.app.state.db,user_id)
