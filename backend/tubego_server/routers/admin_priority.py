"""Administrator-only persistent scheduling policy."""
import json
from contextlib import closing
from typing import Literal
from fastapi import APIRouter, Depends, HTTPException, Request, Query
from pydantic import BaseModel
from tubego_server.auth import require_admin
from tubego_server.scheduler import now, put_setting

router = APIRouter(prefix='/admin/users', tags=['admin scheduling'])


class PriorityUpdate(BaseModel):
    level: Literal['normal', 'prioritario']


@router.put('/{user_id}/priority')
def update_priority(user_id: str, body: PriorityUpdate, request: Request,
                    actor: dict = Depends(require_admin)):
    with request.app.state.db.transaction() as conn:
        if not conn.execute('SELECT id FROM users WHERE id=?', (user_id,)).fetchone():
            raise HTTPException(404, 'User not found')
        put_setting(conn, 'user', user_id, 'priority_level', body.level)
        conn.execute('INSERT INTO audit(actor_user_id,action,target_id,detail_json,created_at) VALUES(?,?,?,?,?)',
                     (actor['id'], 'user.priority_changed', user_id, json.dumps({'level': body.level}), now()))
    return {'user_id': user_id, 'level': body.level}


@router.get('/priorities')
def list_priorities(request: Request, limit: int = Query(50, ge=1, le=100),
                    after: str = '', actor: dict = Depends(require_admin)):
    with closing(request.app.state.db.connect()) as conn:
        rows = conn.execute("""SELECT u.id,u.email,s.value_json FROM users u
            LEFT JOIN settings s ON s.scope='user' AND s.owner_id=u.id AND s.key='priority_level'
            WHERE u.status='approved' AND u.id>? ORDER BY u.id LIMIT ?""", (after, limit+1)).fetchall()
    items = [{'id': r['id'], 'email': r['email'],
              'level': 'prioritario' if r['value_json'] == json.dumps('prioritario') else 'normal'}
             for r in rows[:limit]]
    return {'items': items, 'next_cursor': items[-1]['id'] if len(rows)>limit else None}
