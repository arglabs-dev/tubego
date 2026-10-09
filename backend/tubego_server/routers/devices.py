"""Own-device revocation preserves tombstones so offline devices learn to wipe."""
import json
from fastapi import APIRouter, Depends, HTTPException, Request
from tubego_server.auth import get_principal, token_hash, utcnow

router=APIRouter()


def revoke_device(conn,user_id,device_id,actor):
    device=conn.execute('SELECT * FROM devices WHERE id=? AND user_id=?',(device_id,user_id)).fetchone()
    if device is None: raise HTTPException(404,'Device not found')
    if device['revoked_at']: return
    now=utcnow()
    conn.execute('UPDATE devices SET revoked_at=? WHERE id=?',(now,device_id))
    conn.execute('UPDATE sessions SET revoked_at=? WHERE device_id=? AND revoked_at IS NULL',(now,device_id))
    conn.execute("UPDATE deliveries SET status='device_revoked',updated_at=? WHERE device_id=?",(now,device_id))
    # Retain device/session rows to signal cleanup to an offline device on its next request.
    conn.execute('INSERT INTO events(user_id,device_id,kind,payload_json,created_at) VALUES (?,?,?,?,?)',(user_id,device_id,'device.wipe',json.dumps({'device_id':device_id}),now))
    conn.execute('INSERT INTO audit(actor_user_id,action,target_id,created_at) VALUES (?,?,?,?)',(actor,'device.revoked',device_id,now))

@router.get('/account/devices')
def devices(request:Request,principal=Depends(get_principal)):
    with request.app.state.db.transaction() as conn:
        conn.execute('UPDATE devices SET last_seen_at=? WHERE id=? AND user_id=?',(utcnow(),principal['device_id'],principal['id']))
        rows=conn.execute('SELECT id,name,platform,last_seen_at,created_at,revoked_at FROM devices WHERE user_id=? ORDER BY created_at DESC',(principal['id'],)).fetchall()
    return [dict(row,current=row['id']==principal['device_id']) for row in rows]

@router.post('/account/devices/{device_id}/revoke')
def revoke(device_id:str,request:Request,principal=Depends(get_principal)):
    with request.app.state.db.transaction() as conn:
        revoke_device(conn,principal['id'],device_id,principal['id'])
    from tubego_server.retention import sweep_confirmed
    sweep_confirmed(request.app.state.db,request.app.state.settings.data_dir/'media',principal['id'])
    return {'status':'revoked','wipe_local':device_id==principal['device_id']}

@router.post('/account/session/logout')
def logout(request:Request):
    # An expired/revoked token remains a narrow capability to revoke its own device,
    # never to access data or revoke another device. This supports delayed offline logout.
    header=request.headers.get('Authorization','')
    if not header.startswith('Bearer ') or len(header)>512: raise HTTPException(401,'Authentication required')
    with request.app.state.db.transaction() as conn:
        principal=conn.execute('SELECT s.id,s.created_at,s.user_id,s.device_id,d.user_id AS owner FROM sessions s LEFT JOIN devices d ON d.id=s.device_id WHERE s.token_hash=?',(token_hash(header[7:]),)).fetchone()
        if principal is None or principal['device_id'] is None or principal['owner']!=principal['user_id']:
            raise HTTPException(401,'Invalid session')
        # Delayed logout is a capability for this session's device incarnation.
        # An old rotated token must never revoke the replacement session.
        replacement=conn.execute('''SELECT 1 FROM sessions WHERE device_id=?
            AND id<>? AND revoked_at IS NULL AND created_at>=? LIMIT 1''',
            (principal['device_id'],principal['id'],principal['created_at'])).fetchone()
        if replacement:
            return {'status':'logged_out'}
        revoke_device(conn,principal['user_id'],principal['device_id'],principal['user_id'])
    from tubego_server.retention import sweep_confirmed
    sweep_confirmed(request.app.state.db,request.app.state.settings.data_dir/'media',principal['user_id'])
    return {'status':'logged_out'}
