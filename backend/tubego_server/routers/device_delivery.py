"""Authenticated media transfer and durable device sync; no completion alerts."""
import json
from fastapi import APIRouter,Depends,HTTPException,Query,Request
from fastapi.responses import Response,StreamingResponse
from pydantic import BaseModel,Field
from starlette.background import BackgroundTask
from tubego_server.auth import require_approved,utcnow
from tubego_server.delivery import device_scope,read_setting,write_setting,transfer_allowed
from tubego_server.private_media import open_private_media,byte_range
from tubego_server.routers.library import public_resource
from tubego_server.history import priority

router=APIRouter(tags=['device delivery'])


def available(scope, resource_id):
    row=scope.resource(resource_id)
    if not row['ready_at'] or row['server_deleted_at'] or not row['server_path']:
        raise HTTPException(404,'Media unavailable')
    digest=read_setting(scope.connection,'resource',resource_id,'media_sha256')
    if not digest:
        raise HTTPException(409,'Media not finalized')
    return row,digest


@router.get('/resources/{resource_id}/download')
@router.head('/resources/{resource_id}/download',include_in_schema=False)
def download(resource_id: str,request: Request,principal=Depends(require_approved)):
    with request.app.state.db.transaction() as conn:
        scope,did=device_scope(conn,principal)
        resource,digest=available(scope,resource_id)
        delivery=scope.delivery(resource_id,did)
        if delivery['deleted_at'] or delivery['status']=='approval_required':
            raise HTTPException(409,'Confirm a new request before downloading a deliberately deleted file')
        stream,info=open_private_media(request.app.state.settings.data_dir/'media',resource['server_path'])
        if info.st_size!=resource['size_bytes']:
            stream.close();raise HTTPException(409,'Media changed; request again')
    etag='"'+digest+'"'
    requested=request.headers.get('range')
    if request.headers.get('if-range') not in (None,etag):requested=None
    try:start,end=byte_range(requested,info.st_size)
    except BaseException:
        stream.close();raise
    length=max(0,end-start+1)
    headers={'Cache-Control':'no-store','X-Content-Type-Options':'nosniff',
             'Content-Disposition':'attachment; filename="tubego-media"',
             'Content-Length':str(length),'Accept-Ranges':'bytes','ETag':etag,'X-Content-SHA256':digest}
    if requested:headers['Content-Range']=f'bytes {start}-{end}/{info.st_size}'
    code=206 if requested else 200
    if request.method=='HEAD':
        stream.close();return Response(status_code=code,media_type='application/octet-stream',headers=headers)
    def chunks():
        try:
            stream.seek(start);remaining=length
            while remaining:
                if not transfer_allowed(request.app.state.db,principal,resource_id):break
                chunk=stream.read(min(65536,remaining))
                if not chunk:break
                remaining-=len(chunk);yield chunk
        finally:stream.close()
    return StreamingResponse(chunks(),status_code=code,media_type='application/octet-stream',
                             headers=headers,background=BackgroundTask(stream.close))


class Confirmation(BaseModel):
    size_bytes:int=Field(ge=0)
    sha256:str=Field(pattern=r'^[0-9a-f]{64}$')


@router.post('/resources/{resource_id}/deliveries/confirm')
def confirm(resource_id: str,body: Confirmation,request: Request,principal=Depends(require_approved)):
    with request.app.state.db.transaction() as conn:
        scope,did=device_scope(conn,principal)
        resource=scope.resource(resource_id)
        digest=read_setting(conn,'resource',resource_id,'media_sha256')
        delivery=scope.delivery(resource_id,did)
        if delivery['deleted_at'] or delivery['status']=='approval_required':
            raise HTTPException(409,'New download approval required')
        if resource['server_deleted_at'] or not resource['server_path']:
            # An acknowledged completion remains retryable after automatic cleanup.
            # This capability cannot confirm a new transfer or manually deleted copy.
            if (read_setting(conn,'resource',resource_id,'retention_removed') is True
                and delivery['confirmed_at'] and delivery['status']=='complete'
                and body.size_bytes==resource['size_bytes']==delivery['downloaded_bytes']
                and body.sha256==digest==read_setting(conn,'delivery',did+':'+resource_id,'confirmed_sha256')):
                return {'resource_id':resource_id,'device_id':did,'status':'complete','server_copy_removed':True}
            raise HTTPException(404,'Media unavailable')
        if not resource['ready_at'] or not digest:raise HTTPException(409,'Media not finalized')
        if body.size_bytes!=resource['size_bytes'] or body.sha256!=digest:
            raise HTTPException(409,'Incomplete or mismatched media')
        timestamp=utcnow()
        conn.execute("UPDATE deliveries SET status='complete',downloaded_bytes=?,confirmed_at=COALESCE(confirmed_at,?),updated_at=? WHERE resource_id=? AND device_id=?",(body.size_bytes,timestamp,timestamp,resource_id,did))
        write_setting(conn,'delivery',did+':'+resource_id,'confirmed_sha256',digest)
        conn.execute('UPDATE resources SET first_delivered_at=COALESCE(first_delivered_at,?),updated_at=? WHERE id=?',(timestamp,timestamp,resource_id))
    from tubego_server.retention import delete_if_eligible
    removed=delete_if_eligible(request.app.state.db,request.app.state.settings.data_dir/'media',resource_id)
    if not removed:
        with request.app.state.db.transaction() as conn:
            removed=read_setting(conn,'resource',resource_id,'retention_removed') is True
    return {'resource_id':resource_id,'device_id':did,'status':'complete','server_copy_removed':removed}


class DeliveryRequest(BaseModel):
    approve_redownload:bool=False
    restore_missing:bool=False


@router.post('/resources/{resource_id}/deliveries/request')
def request_delivery(resource_id:str,body:DeliveryRequest,request:Request,principal=Depends(require_approved)):
    with request.app.state.db.transaction() as conn:
        scope,did=device_scope(conn,principal)
        resource,digest=available(scope,resource_id)
        prior=conn.execute('SELECT * FROM deliveries WHERE resource_id=? AND device_id=?',(resource_id,did)).fetchone()
        tombstone=prior['deleted_at'] if prior else read_setting(conn,'resource',resource_id,'deliberately_deleted')
        if (tombstone or (prior and prior['status']=='approval_required')) and not body.approve_redownload:
            raise HTTPException(409,'Approval required to download a deliberately deleted file')
        if prior and prior['status']=='complete' and not prior['deleted_at'] and not body.restore_missing and read_setting(conn,'delivery',did+':'+resource_id,'confirmed_sha256')==digest:
            return {'resource_id':resource_id,'status':'complete'}
        timestamp=utcnow()
        conn.execute("""INSERT INTO deliveries(resource_id,device_id,status,updated_at) VALUES(?,?,'pending',?)
            ON CONFLICT(resource_id,device_id) DO UPDATE SET status='pending',deleted_at=NULL,
            confirmed_at=NULL,downloaded_bytes=CASE WHEN deliveries.deleted_at IS NOT NULL OR deliveries.status='complete' THEN 0 ELSE deliveries.downloaded_bytes END,
            updated_at=excluded.updated_at""",(resource_id,did,timestamp))
    return {'resource_id':resource_id,'status':'pending'}


@router.get('/device/sync')
def sync(request:Request,principal=Depends(require_approved),
         event_cursor:int=Query(0,ge=0),delivery_cursor:str=Query('',max_length=256),
         limit:int=Query(50,ge=1,le=100)):
    with request.app.state.db.transaction() as conn:
        scope,did=device_scope(conn,principal)
        events=conn.execute('''SELECT * FROM events WHERE user_id=? AND (device_id=? OR device_id IS NULL)
            AND id>? ORDER BY id LIMIT ?''',(scope.user_id,did,event_cursor,limit+1)).fetchall()
        deliveries=conn.execute('''SELECT r.*,d.status AS delivery_status,d.downloaded_bytes,d.deleted_at AS local_deleted_at
            FROM deliveries d JOIN resources r ON r.id=d.resource_id
            WHERE d.device_id=? AND r.user_id=? AND r.id>? ORDER BY r.id LIMIT ?''',
            (did,scope.user_id,delivery_cursor,limit+1)).fetchall()
        items=[]
        for row in deliveries[:limit]:
            item=public_resource(row)
            item.update(delivery_status=row['delivery_status'],downloaded_bytes=row['downloaded_bytes'],
                        local_deleted_at=row['local_deleted_at'],sha256=read_setting(conn,'resource',row['id'],'media_sha256'),
                        priority=priority(conn,row['id']),server_available=bool(row['ready_at'] and not row['server_deleted_at'] and row['server_path']))
            items.append(item)
    data={'device_id':did,'deliveries':items,
          'next_delivery_cursor':items[-1]['id'] if len(deliveries)>limit else None,
          'events':[{'id':e['id'],'kind':e['kind'],'payload':json.loads(e['payload_json'])} for e in events[:limit]],
          'event_cursor':events[min(len(events),limit)-1]['id'] if events else event_cursor,
          'has_more_events':len(events)>limit}
    return Response(json.dumps(data),media_type='application/json',headers={'Cache-Control':'no-store'})


@router.get('/device/queue-order')
def queue_order(request:Request,principal=Depends(require_approved),cursor:str=Query('',max_length=256),limit:int=Query(100,ge=1,le=100)):
    with request.app.state.db.transaction() as conn:
        scope,did=device_scope(conn,principal)
        rows=conn.execute("SELECT r.id,r.created_at FROM deliveries d JOIN resources r ON r.id=d.resource_id WHERE d.device_id=? AND r.user_id=? AND r.id>? AND d.deleted_at IS NULL AND d.status NOT IN ('approval_required','complete') ORDER BY r.id LIMIT ?",(did,scope.user_id,cursor,limit+1)).fetchall()
        items=[{'id':row['id'],'priority':priority(conn,row['id']),'created_at':row['created_at']} for row in rows[:limit]]
    return {'items':items,'next_cursor':items[-1]['id'] if len(rows)>limit else None}
