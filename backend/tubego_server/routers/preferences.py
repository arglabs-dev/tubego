from fastapi import APIRouter, Depends, Request
from tubego_server.auth import require_approved,utcnow
from pydantic import BaseModel,ConfigDict
from typing import Literal
from contextlib import closing
import json
from tubego_server.tasks import live_scope
from tubego_server.scheduler import put_setting
from tubego_server.preferences import MediaPreferences, read_preferences, save_preferences

router = APIRouter(tags=['preferences'])


@router.get('/account/preferences', response_model=MediaPreferences)
def get_preferences(request: Request, user=Depends(require_approved)):
    return read_preferences(request.app.state.db, user['id'])


@router.put('/account/preferences', response_model=MediaPreferences)
def put_preferences(body: MediaPreferences, request: Request, user=Depends(require_approved)):
    return save_preferences(request.app.state.db, user['id'], body)


class LanguageChoice(BaseModel):
    model_config=ConfigDict(extra='forbid')
    language:Literal['es','en']


@router.get('/account/preferences/language')
def get_language(request:Request,user=Depends(require_approved)):
    with closing(request.app.state.db.connect()) as conn:
        row=conn.execute("SELECT value_json,updated_at FROM settings WHERE scope='user' AND owner_id=? AND key='language'",(user['id'],)).fetchone()
    return {'language':json.loads(row['value_json']) if row else None,'updated_at':row['updated_at'] if row else None}


@router.put('/account/preferences/language')
def put_language(body:LanguageChoice,request:Request,user=Depends(require_approved)):
    with request.app.state.db.transaction() as conn:
        scope=live_scope(conn,user)
        put_setting(conn,'user',scope.user_id,'language',body.language)
    return {'language':body.language}
