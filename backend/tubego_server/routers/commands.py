"""Durable, ordered device intentions. Effect receipts survive a lost HTTP reply.

Reservation and completion transactions contain no network operations. Existing
resource handlers retain their atomic UUID receipts; wrapper receipts are stored
separately so an interrupted reservation can safely repeat the same effect.
"""
from typing import Literal
from uuid import UUID
from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from tubego_server.auth import require_approved, utcnow
from tubego_server.delivery import device_scope, read_setting, write_setting
from tubego_server.preferences import MediaPreferences

router=APIRouter(tags=['offline commands'])

class Command(BaseModel):
    model_config=ConfigDict(extra='forbid')
    id:UUID
    sequence:int=Field(gt=0,strict=True)
    kind:Literal['submit','recover','delete','server_cleanup','preferences','language','resource_priority','task_cancel','task_retry','task_priority','noop']
    payload:dict=Field(default_factory=dict)


def execute(body, request, principal):
    # Imports are intentionally local: channels integrated independently retain
    # their original endpoints and reuse their original request UUIDs.
    from tubego_server.routers.tasks import create_resource, Submission
    from tubego_server.routers.resource_deletion import delete, Deletion
    from tubego_server.routers.resource_recovery import recover, Recovery
    from tubego_server.tasks import action
    payload=dict(body.payload);rid=str(body.id)
    if body.kind=='noop':return {'superseded':True}
    if body.kind=='submit':return create_resource(Submission.model_validate(payload|{'request_id':rid}),request,principal)
    if body.kind=='recover':
        resource=payload.pop('resource_id')
        return recover(resource,Recovery.model_validate(payload|{'request_id':rid}),request,principal)
    if body.kind=='delete':
        resource=payload.pop('resource_id')
        return delete(resource,Deletion.model_validate(payload|{'request_id':rid}),request,principal)
    if body.kind=='server_cleanup':
        from tubego_server.routers.server_cleanup import clean, Cleanup
        return clean(Cleanup.model_validate(payload|{'request_id':rid}),request,principal)
    if body.kind=='resource_priority':
        from tubego_server.routers.library import resource_priority, PriorityRequest
        resource=payload.pop('resource_id')
        return resource_priority(resource,PriorityRequest.model_validate(payload|{'request_id':rid}),request,principal)
    if body.kind.startswith('task_'):
        if set(payload)!={'task_id'}:raise ValueError('Invalid task command')
        return action(request.app.state.db,principal,payload['task_id'],body.kind[5:],body.id)
    if body.kind=='language':
        if set(payload)!={'language','revision'} or payload['language'] not in ('es','en') or type(payload['revision']) is not int or payload['revision']<1:raise ValueError('Invalid language intention')
        with request.app.state.db.transaction() as conn:
            scope,did=device_scope(conn,principal)
            key='language_command:'+rid
            previous=read_setting(conn,'device',did,key)
            if previous is not None:return previous
            write_setting(conn,'user',scope.user_id,'language',payload['language'])
            write_setting(conn,'device',did,key,payload)
        return payload
    if body.kind=='preferences':
        value=MediaPreferences.model_validate(payload)
        # Save + effect receipt in one transaction. A replay must not overwrite
        # preferences changed by a newer device command after a lost response.
        with request.app.state.db.transaction() as conn:
            scope,did=device_scope(conn,principal)
            key='preferences_command:'+rid
            previous=read_setting(conn,'device',did,key)
            if previous is not None:return previous
            result=value.model_dump()
            write_setting(conn,'user',scope.user_id,'media_preferences',result)
            write_setting(conn,'device',did,key,result)
        return result
    raise ValueError('Unknown command')


@router.post('/device/commands')
def command(body:Command,request:Request,principal=Depends(require_approved)):
    database=request.app.state.db
    identity={'sequence':body.sequence,'kind':body.kind,'payload':body.payload}
    key='command_receipt:'+str(body.id)
    with database.transaction() as conn:
        scope,did=device_scope(conn,principal)
        previous=read_setting(conn,'device',did,key)
        if previous is not None:
            if previous['identity']!=identity:raise HTTPException(409,{'code':'command_identity_conflict'})
            if previous['status']!='pending':return previous['response']
        else:
            cursor=read_setting(conn,'device',did,'command_sequence') or 0
            inflight=read_setting(conn,'device',did,'command_inflight')
            if body.sequence!=cursor+1 or (inflight and inflight!=str(body.id)):
                raise HTTPException(409,{'code':'command_out_of_order','expected_sequence':cursor+1})
            write_setting(conn,'device',did,key,{'identity':identity,'status':'pending'})
            write_setting(conn,'device',did,'command_inflight',str(body.id))
    status='complete'
    try:
        result=execute(body,request,principal)
    except HTTPException as error:
        detail=error.detail if isinstance(error.detail,dict) else {}
        # An expired session is not revocation. Preserve the intention until
        # reauthentication. Cleanup leases and network failures also wait.
        if error.status_code==401 or error.status_code in (408,429) or error.status_code>=500 or detail.get('code')=='cleanup_pending':raise
        with database.transaction() as conn:device_scope(conn,principal)
        status='rejected';result={'code':detail.get('code','command_rejected'),'http_status':error.status_code}
    except (ValidationError,ValueError,KeyError,TypeError):
        status='rejected';result={'code':'invalid_command','http_status':422}
    response={'id':str(body.id),'sequence':body.sequence,'status':status,'result':result}
    with database.transaction() as conn:
        scope,did=device_scope(conn,principal)
        previous=read_setting(conn,'device',did,key)
        if previous and previous['status']!='pending':return previous['response']
        write_setting(conn,'device',did,key,{'identity':identity,'status':status,'response':response,'completed_at':utcnow()})
        write_setting(conn,'device',did,'command_sequence',body.sequence)
        write_setting(conn,'device',did,'command_inflight',None)
    return response
