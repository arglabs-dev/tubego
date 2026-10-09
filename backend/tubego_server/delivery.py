"""Publish immutable finalized media and synchronize device-specific delivery."""
import hashlib
import os
import json
from contextlib import closing
from fastapi import HTTPException
from tubego_server.auth import utcnow
from tubego_server.ownership import LibraryScope
from tubego_server.private_media import open_private_media


def read_setting(conn, scope, owner, key):
    row=conn.execute('SELECT value_json FROM settings WHERE scope=? AND owner_id=? AND key=?',(scope,owner,key)).fetchone()
    return json.loads(row[0]) if row else None


def write_setting(conn, scope, owner, key, value):
    conn.execute('''INSERT INTO settings(scope,owner_id,key,value_json,updated_at) VALUES(?,?,?,?,?)
        ON CONFLICT(scope,owner_id,key) DO UPDATE SET value_json=excluded.value_json,updated_at=excluded.updated_at''',
        (scope,owner,key,json.dumps(value),utcnow()))


def device_scope(conn, principal):
    """Recheck revocation/approval inside the same transaction as a device action."""
    current=conn.execute('''SELECT u.*,s.device_id FROM users u JOIN sessions s ON s.user_id=u.id
        JOIN devices d ON d.id=s.device_id AND d.user_id=u.id
        WHERE u.id=? AND s.id=? AND s.revoked_at IS NULL AND s.expires_at>?
        AND d.revoked_at IS NULL''',(principal['id'],principal['session_id'],utcnow())).fetchone()
    if current is None:
        raise HTTPException(401,'An active device session is required')
    scope=LibraryScope(conn,current)
    return scope,current['device_id']


def transfer_allowed(database, principal, resource_id):
    """Stop an open response when its account, session or delivery is revoked."""
    with closing(database.connect()) as conn:
        return conn.execute('''SELECT 1 FROM sessions s
            JOIN users u ON u.id=s.user_id
            JOIN devices d ON d.id=s.device_id AND d.user_id=u.id
            JOIN resources r ON r.user_id=u.id AND r.id=?
            JOIN deliveries l ON l.resource_id=r.id AND l.device_id=d.id
            WHERE s.id=? AND u.id=? AND s.revoked_at IS NULL AND s.expires_at>?
            AND u.status='approved' AND u.email_verified_at IS NOT NULL
            AND d.revoked_at IS NULL AND r.server_deleted_at IS NULL
            AND l.deleted_at IS NULL AND l.status!='approval_required' ''',
            (resource_id,principal['session_id'],principal['id'],utcnow())).fetchone() is not None


def media_digest(root, path):
    stream, info=open_private_media(root,path)
    digest=hashlib.sha256()
    with stream:
        while chunk:=stream.read(1024*1024):digest.update(chunk)
        final=os.fstat(stream.fileno())
        if (info.st_size,info.st_mtime_ns)!=(final.st_size,final.st_mtime_ns):
            raise ValueError('Finalized media changed while calculating checksum')
    return digest.hexdigest(),info.st_size


def publish_ready(database, media_root, resource_id, server_path, *, precondition=None):
    """Trusted worker hook after atomic media finalization, before task completion.

    The file must not be modified after publication. Re-publishing the same file
    preserves completed deliveries and deliberately deleted local copies. Devices
    linked after publication must request this historical item explicitly.
    """
    digest,size=media_digest(media_root,server_path)
    with database.transaction() as conn:
        resource=conn.execute('SELECT * FROM resources WHERE id=?',(resource_id,)).fetchone()
        if resource is None:raise HTTPException(404,'Not found')
        if precondition is not None:precondition(conn,resource)
        owner=conn.execute('SELECT * FROM users WHERE id=?',(resource['user_id'],)).fetchone()
        if owner['status']!='approved' or not owner['email_verified_at']:
            raise HTTPException(403,'Owner unavailable')
        old_digest=read_setting(conn,'resource',resource_id,'media_sha256')
        same=old_digest==digest and resource['size_bytes']==size
        timestamp=utcnow()
        # A previously removed server copy starts a new availability interval.
        ready_at=resource['ready_at'] if same and not resource['server_deleted_at'] else timestamp
        first=resource['first_delivered_at'] if same and not resource['server_deleted_at'] else None
        write_setting(conn,'resource',resource_id,'media_sha256',digest)
        conn.execute('''UPDATE resources SET server_path=?,size_bytes=?,ready_at=?,first_delivered_at=?,
            server_deleted_at=NULL,updated_at=? WHERE id=?''',(str(server_path),size,ready_at,first,timestamp,resource_id))
        devices=conn.execute('SELECT id FROM devices WHERE user_id=? AND revoked_at IS NULL ORDER BY id',(owner['id'],)).fetchall()
        if not same or not resource['ready_at'] or resource['server_deleted_at']:
            write_setting(conn,'resource',resource_id,'retention_removed',False)
            write_setting(conn,'resource',resource_id,'publication_recipients',[device['id'] for device in devices])
        recipients=[]
        for device in devices:
            did=device['id']
            existing=conn.execute('SELECT * FROM deliveries WHERE resource_id=? AND device_id=?',(resource_id,did)).fetchone()
            # Idempotent publication must not backfill a newly linked device.
            if existing is None and same and resource['ready_at'] and not resource['server_deleted_at']:
                continue
            confirmed=read_setting(conn,'delivery',did+':'+resource_id,'confirmed_sha256')
            if existing and existing['status']=='complete' and not existing['deleted_at'] and confirmed==digest and existing['downloaded_bytes']==size:
                if first is None:first=timestamp
                continue
            status='approval_required' if existing and existing['deleted_at'] else 'pending'
            # Preserve bytes for same file resumptions; a changed file starts at zero.
            transferred=existing['downloaded_bytes'] if existing and same and status=='pending' else 0
            conn.execute('''INSERT INTO deliveries(resource_id,device_id,status,downloaded_bytes,updated_at)
                VALUES(?,?,?,?,?) ON CONFLICT(resource_id,device_id) DO UPDATE SET
                status=excluded.status,downloaded_bytes=excluded.downloaded_bytes,
                confirmed_at=NULL,updated_at=excluded.updated_at''',(resource_id,did,status,transferred,timestamp))
            if not existing or not same or existing['status']!=status:
                payload={'resource_id':resource_id,'size_bytes':size,'sha256':digest,'delivery_status':status,'silent':True}
                conn.execute("INSERT INTO events(user_id,device_id,kind,payload_json,created_at) VALUES(?,?,'resource_available',?,?)",(owner['id'],did,json.dumps(payload),timestamp))
            recipients.append(did)
        if first is not None:
            conn.execute('UPDATE resources SET first_delivered_at=COALESCE(first_delivered_at,?) WHERE id=?',(first,resource_id))
    return {'resource_id':resource_id,'size_bytes':size,'sha256':digest,'recipients':recipients}
