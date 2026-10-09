"""Shared administrator audit recorder for future maintenance operations.

Call inside the mutation's transaction so the action and audit are atomic.
Never record passwords, session tokens, download URLs with credentials or media.
"""
import json
from fastapi import HTTPException
from tubego_server.auth import utcnow


def audit_admin(connection, principal, action, target_id=None, detail=None):
    if (principal['role'] != 'admin' or principal['status'] != 'approved'
            or not principal['email_verified_at']):
        raise HTTPException(403, 'Administrator required')
    connection.execute('''INSERT INTO audit(actor_user_id,action,target_id,detail_json,created_at)
        VALUES(?,?,?,?,?)''', (principal['id'],action,target_id,json.dumps(detail or {}),utcnow()))
