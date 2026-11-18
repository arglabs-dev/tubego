from contextlib import closing
from datetime import datetime, timezone
import json
from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import Response
from tubego_server.auth import require_approved
from tubego_server.tasks import live_scope
from tubego_server.playback import Snapshot, read, merge

router = APIRouter(tags=['playback'])


@router.put('/resources/{resource_id}/playback')
def checkpoint(resource_id: str, body: Snapshot, request: Request, principal=Depends(require_approved)):
    future = int(datetime.now(timezone.utc).timestamp()*1000) + 5*60*1000
    if max(body.position_at_ms, body.manual_at_ms) > future:
        raise HTTPException(422, 'Playback observation clock is in the future')
    with request.app.state.db.transaction() as conn:
        scope = live_scope(conn, principal)
        scope.resource(resource_id)
        result = merge(conn, resource_id, body)
    return Response(json.dumps(result), media_type='application/json', headers={'Cache-Control':'no-store'})


@router.get('/playback')
def snapshots(request: Request, after: str = Query('', max_length=128),
              limit: int = Query(100, ge=1, le=100), principal=Depends(require_approved)):
    with closing(request.app.state.db.connect()) as conn:
        rows = conn.execute("""SELECT r.id FROM resources r JOIN settings s
            ON s.owner_id=r.id AND s.scope='resource' AND s.key='playback_progress'
            WHERE r.user_id=? AND r.id>? ORDER BY r.id LIMIT ?""",
            (principal['id'], after, limit+1)).fetchall()
        items = [{'resource_id': row['id'], **read(conn, row['id'])} for row in rows[:limit]]
    return Response(json.dumps({'items':items, 'next_cursor':rows[limit-1]['id'] if len(rows)>limit else None}),
                    media_type='application/json', headers={'Cache-Control':'no-store'})
