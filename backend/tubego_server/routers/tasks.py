from contextlib import closing
from typing import Literal
from uuid import UUID
from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import BaseModel, ConfigDict, Field
import json
from tubego_server.auth import require_approved,require_admin
from tubego_server.ownership import LibraryScope
from tubego_server.media import MediaError
from tubego_server.preferences import Selection
from tubego_server.tasks import action, submit, task_value,live_scope

router=APIRouter(tags=['tasks'])


class Submission(BaseModel):
    model_config=ConfigDict(extra='forbid')
    url:str=Field(min_length=1,max_length=4096)
    selection:Selection|None=None
    request_id:UUID|None=None


@router.post('/resources')
def create_resource(body:Submission,request:Request,principal=Depends(require_approved)):
    try:
        return submit(request.app.state.db,principal,body.url,body.selection,body.request_id)
    except MediaError as error:
        raise HTTPException(503 if error.code=='temporary_failure' else 422,{'code':error.code,'message':str(error)}) from None
    except ValueError:
        raise HTTPException(422,'Choose quality or audio before submitting') from None


@router.get('/tasks')
def list_tasks(request:Request,principal=Depends(require_approved),limit:int=Query(50,ge=1,le=100)):
    with closing(request.app.state.db.connect()) as conn:
        rows=conn.execute('SELECT * FROM tasks WHERE user_id=? ORDER BY created_at DESC,id LIMIT ?',(principal['id'],limit)).fetchall()
        return {'items':[task_value(conn,row) for row in rows]}


@router.get('/tasks/{task_id}')
def get_task(task_id:str,request:Request,principal=Depends(require_approved)):
    with closing(request.app.state.db.connect()) as conn:
        return task_value(conn,LibraryScope(conn,principal).task(task_id))


@router.post('/tasks/{task_id}/{kind}')
def task_action(task_id:str,kind:Literal['cancel','retry','priority'],request:Request,principal=Depends(require_approved)):
    return action(request.app.state.db,principal,task_id,kind)


@router.get('/admin/download-errors')
def download_errors(request:Request,principal=Depends(require_admin),after:int=Query(0,ge=0),limit:int=Query(50,ge=1,le=100)):
    with request.app.state.db.transaction() as conn:
        scope=live_scope(conn,principal)
        if conn.execute('SELECT role FROM users WHERE id=?',(scope.user_id,)).fetchone()[0]!='admin':raise HTTPException(403,'Administrator required')
        rows=conn.execute("SELECT id,target_id,detail_json,created_at FROM audit WHERE action='download.failed' AND id>? ORDER BY id LIMIT ?",(after,limit+1)).fetchall()
        return {'items':[{'id':row['id'],'task_id':row['target_id'],'created_at':row['created_at'],'diagnostic':json.loads(row['detail_json'])} for row in rows[:limit]],'next_cursor':rows[limit-1]['id'] if len(rows)>limit else None}
