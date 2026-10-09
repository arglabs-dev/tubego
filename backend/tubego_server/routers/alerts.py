from fastapi import APIRouter,Depends,HTTPException,Query,Request
from pydantic import BaseModel,Field,ConfigDict
from tubego_server.auth import require_approved
from tubego_server.delivery import device_scope,read_setting,write_setting
from tubego_server.alerts import USER_KINDS,ADMIN_KINDS,public_alert

router=APIRouter(tags=['actionable alerts'])


def kinds(conn,user):
    role=conn.execute('SELECT role FROM users WHERE id=?',(user,)).fetchone()[0]
    return USER_KINDS+(ADMIN_KINDS if role=='admin' else ()),role


@router.get('/device/alerts')
def get_alerts(request:Request,principal=Depends(require_approved),limit:int=Query(50,ge=1,le=100)):
    with request.app.state.db.transaction() as conn:
        scope,did=device_scope(conn,principal);allowed,role=kinds(conn,scope.user_id)
        cursor=read_setting(conn,'device',did,'alerts_cursor') or 0
        rows=conn.execute("SELECT * FROM events WHERE user_id=? AND (device_id=? OR device_id IS NULL) AND id>? AND kind IN ("+','.join('?' for _ in allowed)+") ORDER BY id LIMIT ?",(scope.user_id,did,cursor,*allowed,limit+1)).fetchall()
        return {'items':[public_alert(row) for row in rows[:limit]],'cursor':cursor,
                'next_cursor':rows[min(limit,len(rows))-1]['id'] if rows else cursor,
                'has_more':len(rows)>limit,'role':role}


class Ack(BaseModel):
    model_config=ConfigDict(extra='forbid')
    event_id:int=Field(strict=True,ge=0)


@router.post('/device/alerts/ack')
def acknowledge(body:Ack,request:Request,principal=Depends(require_approved)):
    with request.app.state.db.transaction() as conn:
        scope,did=device_scope(conn,principal);allowed,_=kinds(conn,scope.user_id)
        cursor=read_setting(conn,'device',did,'alerts_cursor') or 0
        if body.event_id<=cursor:return {'cursor':cursor}
        exists=conn.execute("SELECT 1 FROM events WHERE user_id=? AND (device_id=? OR device_id IS NULL) AND id=? AND kind IN ("+','.join('?' for _ in allowed)+")",(scope.user_id,did,body.event_id,*allowed)).fetchone()
        if not exists:raise HTTPException(409,'Alert cursor unavailable')
        write_setting(conn,'device',did,'alerts_cursor',body.event_id)
        return {'cursor':body.event_id}
