"""Private metadata and authenticated media transport (no public file URLs)."""
import base64
from contextlib import closing
import json
from typing import Literal
from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import Response
from tubego_server.auth import require_approved
from tubego_server.ownership import LibraryScope

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
              status: Literal['ready','processing','failed','deleted'] | None=None):
    clauses=['r.user_id=?']; parameters=[principal['id']]
    if cursor:
        created, key=decode_cursor(cursor)
        clauses.append('(r.created_at>? OR (r.created_at=? AND r.id>?))')
        parameters.extend((created,created,key))
    if search:
        clauses.append("COALESCE(r.title,'') LIKE ? ESCAPE '\\'")
        parameters.append('%'+search.replace('\\','\\\\').replace('%','\\%').replace('_','\\_')+'%')
    filters={
        'ready': 'r.ready_at IS NOT NULL AND r.server_deleted_at IS NULL',
        'deleted': 'r.server_deleted_at IS NOT NULL',
        'processing': "EXISTS(SELECT 1 FROM tasks t WHERE t.resource_id=r.id AND t.user_id=r.user_id AND t.status IN ('queued','running','paused'))",
        'failed': "EXISTS(SELECT 1 FROM tasks t WHERE t.resource_id=r.id AND t.user_id=r.user_id AND t.status='failed')",
    }
    if status:
        clauses.append(filters[status])
    with closing(request.app.state.db.connect()) as conn:
        rows=conn.execute('SELECT r.* FROM resources r WHERE '+' AND '.join(clauses)+' ORDER BY r.created_at,r.id LIMIT ?',(*parameters,limit+1)).fetchall()
    items=[public_resource(row) for row in rows[:limit]]
    next_cursor=base64.urlsafe_b64encode(json.dumps([items[-1]['created_at'],items[-1]['id']]).encode()).decode() if len(rows)>limit else None
    return Response(json.dumps({'items':items,'next_cursor':next_cursor}),media_type='application/json',headers={'Cache-Control':'no-store'})


@router.get('/{resource_id}')
def resource(resource_id: str, request: Request, principal=Depends(require_approved)):
    with closing(request.app.state.db.connect()) as conn:
        item=public_resource(LibraryScope(conn,principal).resource(resource_id))
    return Response(json.dumps(item),media_type='application/json',headers={'Cache-Control':'no-store'})


