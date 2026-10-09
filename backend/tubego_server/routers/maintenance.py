import json
from datetime import datetime,timezone,timedelta
from typing import Literal
from uuid import UUID
from fastapi import APIRouter,Depends,HTTPException,Request
from pydantic import BaseModel,ConfigDict,StrictBool
from tubego_server.auth import require_admin,utcnow
from tubego_server.delivery import read_setting,write_setting
from tubego_server.maintenance import admin,installed,capabilities,jobs,public,save,WRITE_ACTIONS,VERSION,SHA

router=APIRouter(prefix='/admin/maintenance',tags=['controlled maintenance'])
class Operation(BaseModel):
    model_config=ConfigDict(extra='forbid')
    action:Literal['check_versions','speed_test','update_backend','update_ytdlp','restart']
    request_id:UUID
    confirmed:StrictBool=False
    revision:str|None=None
    version:str|None=None

@router.get('')
def status(request:Request,principal=Depends(require_admin)):
    with request.app.state.db.transaction() as conn:
        admin(conn,principal)
        return {'installed':installed(),'capabilities':capabilities(conn),'versions':read_setting(conn,'global','','maintenance_versions'),'jobs':[public(job) for job in jobs(conn)][-30:]}

@router.get('/jobs/{job_id}')
def get_job(job_id:UUID,request:Request,principal=Depends(require_admin)):
    with request.app.state.db.transaction() as conn:
        admin(conn,principal);job=read_setting(conn,'maintenance',str(job_id),'job')
        if not job:raise HTTPException(404,'Not found')
        return public(job)

@router.post('/jobs')
def submit(body:Operation,request:Request,principal=Depends(require_admin)):
    with request.app.state.db.transaction() as conn:
        admin(conn,principal);payload=body.model_dump(mode='json',exclude={'request_id'});key=str(body.request_id)
        if (body.revision is not None and body.action!='update_backend') or (body.version is not None and body.action!='update_ytdlp'):raise HTTPException(422,'Version selection does not match operation')
        old=read_setting(conn,'maintenance',key,'job')
        if old:
            if old['user_id']!=principal['id'] or old['payload']!=payload:raise HTTPException(409,'Request identifier already used')
            return public(old)
        if body.action in WRITE_ACTIONS:
            if not body.confirmed:raise HTTPException(400,'Confirm this maintenance operation')
            caps=capabilities(conn)
            if not caps['mutation_enabled']:raise HTTPException(503,{'code':'capability_unavailable','message':'A controlled operator supervisor must be enabled for this deployment'})
            if body.action=='update_backend' and (not body.revision or not SHA.fullmatch(body.revision) or body.revision not in caps['backend_revisions']):raise HTTPException(422,'Choose an operator-approved backend revision')
            if body.action=='update_ytdlp' and (not body.version or not VERSION.fullmatch(body.version) or body.version not in caps['yt_dlp_versions']):raise HTTPException(422,'Choose an operator-approved yt-dlp version')
            if read_setting(conn,'global','','maintenance_gate'):raise HTTPException(409,'Maintenance is waiting or requires operator recovery')
            if any(job['status'] in ('queued','waiting_worker','running') and job['action'] in WRITE_ACTIONS for job in jobs(conn)):raise HTTPException(409,'Another maintenance operation is pending')
        if body.action in ('speed_test','check_versions'):
            previous=read_setting(conn,'global','','maintenance_rate_'+body.action)
            if previous and datetime.fromisoformat(previous)>datetime.now(timezone.utc)-timedelta(seconds=60):raise HTTPException(429,'Try again after one minute')
            write_setting(conn,'global','','maintenance_rate_'+body.action,utcnow())
        job={'id':key,'user_id':principal['id'],'session_id':principal['session_id'],'action':body.action,'payload':payload,'status':'queued','phase':'pending','progress':0,'result':None,'error_code':None,'created_at':utcnow()}
        save(conn,job)
        conn.execute("INSERT INTO audit(actor_user_id,action,target_id,detail_json,created_at) VALUES(?,'maintenance.requested',?,?,?)",(principal['id'],key,json.dumps({'action':body.action}),utcnow()))
    return public(job)
