"""Private metadata and authenticated media transport (no public file URLs)."""
import base64
from contextlib import closing
import json
from typing import Literal
from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import Response
from pydantic import BaseModel
from datetime import datetime,timezone,timedelta
from uuid import UUID
from tubego_server.history import enrich,prioritize,value
from tubego_server.tasks import live_scope
from tubego_server.scheduler import put_setting
from tubego_server.auth import require_approved
from tubego_server.ownership import LibraryScope
from tubego_server.audio_tracks import AudioChoice
from tubego_server.categories import SQL as CATEGORY_SQL, available as available_categories

router = APIRouter(prefix='/resources', tags=['private library'])
# Filesystem paths, user IDs and internal error payloads are deliberately omitted.
FIELDS = ('id','source_url','title','duration_seconds','media_format','quality',
          'size_bytes','ready_at','server_deleted_at','created_at','updated_at')


def public_resource(row):
    return {key: row[key] for key in FIELDS}


def decode_cursor(value):
    try:
        items = json.loads(base64.urlsafe_b64decode(value.encode()).decode())
        if not isinstance(items,list) or len(items)!=2 or not all(isinstance(i,str) for i in items):
            raise ValueError()
        return items
    except (ValueError, UnicodeError, TypeError):
        raise HTTPException(400, 'Invalid cursor') from None


@router.get('')
def resources(request: Request, principal=Depends(require_approved),
              limit: int=Query(50,ge=1,le=100), cursor: str=Query('',max_length=1024),
              search: str=Query('',max_length=200),
              category: str | None=Query(None,max_length=80),
              status: Literal['ready','processing','failed','deleted','downloaded','pending','error','unavailable'] | None=None,
              order: Literal['oldest','newest']='oldest'):
    clauses=['r.user_id=?']; parameters=[principal['id']]
    if category is not None:
        clauses.append(CATEGORY_SQL+'=?')
        parameters.append(category)
    if cursor:
        created, key=decode_cursor(cursor)
        operator='>' if order=='oldest' else '<'
        clauses.append(f'(r.created_at{operator}? OR (r.created_at=? AND r.id{operator}?))')
        parameters.extend((created,created,key))
    if search:
        clauses.append("COALESCE(r.title,'') LIKE ? ESCAPE '\\'")
        parameters.append('%'+search.replace('\\','\\\\').replace('%','\\%').replace('_','\\_')+'%')
    latest="(SELECT t.status FROM tasks t WHERE t.resource_id=r.id AND t.user_id=r.user_id ORDER BY t.created_at DESC,t.id DESC LIMIT 1)"
    filters={
        'ready': 'r.ready_at IS NOT NULL AND r.server_deleted_at IS NULL',
        'deleted': 'r.server_deleted_at IS NOT NULL',
        'processing': "EXISTS(SELECT 1 FROM tasks t WHERE t.resource_id=r.id AND t.user_id=r.user_id AND t.status IN ('queued','running','paused'))",
        'failed': latest+"='failed'",
        'error': latest+"='failed'",
        'pending': latest+" IN ('queued','running','paused')",
        'unavailable': "r.server_deleted_at IS NOT NULL OR EXISTS(SELECT 1 FROM deliveries d WHERE d.resource_id=r.id AND d.device_id=? AND (d.deleted_at IS NOT NULL OR d.status='approval_required'))",
        'downloaded': "EXISTS(SELECT 1 FROM deliveries d WHERE d.resource_id=r.id AND d.device_id=? AND d.status='complete' AND d.deleted_at IS NULL)",
    }
    if status:
        clauses.append('('+filters[status]+')')
        if status in ('unavailable','downloaded'):parameters.append(principal.get('device_id'))
    with closing(request.app.state.db.connect()) as conn:
        direction='ASC' if order=='oldest' else 'DESC'
        rows=conn.execute('SELECT r.* FROM resources r WHERE '+' AND '.join(clauses)+f' ORDER BY r.created_at {direction},r.id {direction} LIMIT ?',(*parameters,limit+1)).fetchall()
        items=[public_resource(row) | enrich(conn,row,principal.get('device_id')) for row in rows[:limit]]
        categories=available_categories(conn,principal['id'])
    next_cursor=base64.urlsafe_b64encode(json.dumps([items[-1]['created_at'],items[-1]['id']]).encode()).decode() if len(rows)>limit else None
    return Response(json.dumps({'items':items,'next_cursor':next_cursor,'categories':categories}),media_type='application/json',headers={'Cache-Control':'no-store'})


@router.get('/{resource_id}')
def resource(resource_id: str, request: Request, principal=Depends(require_approved)):
    with closing(request.app.state.db.connect()) as conn:
        row=LibraryScope(conn,principal).resource(resource_id)
        item=public_resource(row) | enrich(conn,row,principal.get('device_id'))
    return Response(json.dumps(item),media_type='application/json',headers={'Cache-Control':'no-store'})



class PriorityRequest(BaseModel):
    request_id:UUID|None=None


@router.post('/{resource_id}/priority')
def resource_priority(resource_id:str,body:PriorityRequest,request:Request,principal=Depends(require_approved)):
    with request.app.state.db.transaction() as conn:
        return prioritize(conn,live_scope(conn,principal),resource_id,body.request_id)


class OpenedRequest(BaseModel):
    opened_at:datetime


@router.post('/{resource_id}/opened')
def opened(resource_id:str,body:OpenedRequest,request:Request,principal=Depends(require_approved)):
    timestamp=body.opened_at
    if timestamp.tzinfo is None or timestamp>datetime.now(timezone.utc)+timedelta(minutes=5):
        raise HTTPException(422,'Invalid opening time')
    timestamp=timestamp.astimezone(timezone.utc).isoformat()
    with request.app.state.db.transaction() as conn:
        scope=live_scope(conn,principal);scope.resource(resource_id)
        previous=value(conn,resource_id,'last_opened_at')
        if previous is None or timestamp>previous:
            put_setting(conn,'resource',resource_id,'last_opened_at',timestamp)
    return {'resource_id':resource_id,'last_opened_at':max(previous or timestamp,timestamp)}


@router.get('/{resource_id}/thumbnail')
def thumbnail(resource_id: str, request: Request, principal=Depends(require_approved)):
    with request.app.state.db.transaction() as conn:
        scope = live_scope(conn, principal)
        scope.resource(resource_id)
        encoded = value(conn, resource_id, 'thumbnail_base64')
    if not encoded:
        raise HTTPException(404, 'Preview unavailable')
    return Response(json.dumps({'thumbnail_base64': encoded}), media_type='application/json', headers={
        'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'})


class AudioTrackRequest(BaseModel):
    audio_track: AudioChoice
    request_id: UUID


@router.post('/{resource_id}/audio-track')
def change_audio_track(resource_id:str, body:AudioTrackRequest, request:Request, principal=Depends(require_approved)):
    from tubego_server.delivery import read_setting, write_setting
    from tubego_server.tasks import resource_audio
    from tubego_server.resource_identity import known, key as identity_key
    from tubego_server.auth import utcnow
    with request.app.state.db.transaction() as conn:
        scope=live_scope(conn,principal); resource=scope.resource(resource_id)
        receipt_key='audio_choice:'+str(body.request_id)
        previous=read_setting(conn,'user',scope.user_id,receipt_key)
        if previous:
            if previous['resource_id']!=resource_id or previous['audio_track']!=body.audio_track:
                raise HTTPException(409,'Request identifier already used')
            return previous
        task=conn.execute('SELECT * FROM tasks WHERE resource_id=? ORDER BY created_at DESC,id DESC LIMIT 1',(resource_id,)).fetchone()
        if not task or task['status']!='queued' or task['attempts']!=0 or resource['ready_at']:
            raise HTTPException(409,{'code':'audio_choice_locked','message':'Processing already started. Add a new request to choose another audio track.'})
        identity=known(conn,scope.user_id,resource['source_url'])
        for other in conn.execute('SELECT * FROM resources WHERE user_id=? AND media_format=? AND quality=? AND id<>?',
                (scope.user_id,resource['media_format'],resource['quality'],resource_id)):
            if resource_audio(conn,other['id'])==body.audio_track and known(conn,scope.user_id,other['source_url'])==identity:
                raise HTTPException(409,{'code':'audio_variant_exists','message':'This audio variant is already in your library.'})
        fields=dict(resource)|{'audio_track':body.audio_track}
        conn.execute('UPDATE resources SET source_key=?,updated_at=? WHERE id=?',(identity_key(identity,fields),utcnow(),resource_id))
        write_setting(conn,'resource',resource_id,'audio_track',body.audio_track)
        result={'resource_id':resource_id,'audio_track':body.audio_track}
        write_setting(conn,'user',scope.user_id,receipt_key,result)
        conn.execute("INSERT INTO events(user_id,kind,payload_json,created_at) VALUES(?,'resource.audio_changed',?,?)",(scope.user_id,json.dumps(result),utcnow()))
        return result
