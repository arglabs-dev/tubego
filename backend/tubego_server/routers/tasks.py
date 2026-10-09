from contextlib import closing
from typing import Literal
from uuid import UUID
from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import BaseModel, ConfigDict, Field
from tubego_server.auth import require_approved
from tubego_server.ownership import LibraryScope
from tubego_server.media import MediaError
from tubego_server.preferences import Selection
from tubego_server.tasks import action, submit, task_value

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
