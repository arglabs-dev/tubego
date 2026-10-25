"""Automatic server-copy cleanup; mobile deliveries/history are immutable here."""
import json
import os
from pathlib import Path
import stat
import uuid
from tubego_server.auth import utcnow
from tubego_server.delivery import read_setting,write_setting


def preserves_server_files(conn,user_id):
    return read_setting(conn,'user',user_id,'preserve_server_files') is True


def fully_delivered(conn,resource):
    """Only the publication's nonempty, still-linked recipient set can qualify."""
    snapshot=read_setting(conn,'resource',resource['id'],'publication_recipients')
    if snapshot is None:
        # Upgrade compatibility: old publication had no explicit snapshot.
        # Newly linked devices are not retroactively recipients for the old file.
        snapshot=[row[0] for row in conn.execute('''SELECT l.device_id FROM deliveries l
            JOIN devices d ON d.id=l.device_id WHERE l.resource_id=? AND d.user_id=?
            AND d.created_at<=?''',(resource['id'],resource['user_id'],resource['ready_at'] or ''))]
    if not isinstance(snapshot,list) or not snapshot:return False
    remaining=[]
    digest=read_setting(conn,'resource',resource['id'],'media_sha256')
    if not digest:return False
    for device_id in set(snapshot):
        device=conn.execute('SELECT user_id,revoked_at FROM devices WHERE id=?',(device_id,)).fetchone()
        if device is None or device['user_id']!=resource['user_id'] or device['revoked_at']:continue
        delivery=conn.execute('SELECT * FROM deliveries WHERE resource_id=? AND device_id=?',(resource['id'],device_id)).fetchone()
        complete=bool(delivery and delivery['confirmed_at'] and
            delivery['downloaded_bytes']==resource['size_bytes'] and
            read_setting(conn,'delivery',device_id+':'+resource['id'],'confirmed_sha256')==digest)
        remaining.append(complete)
    return bool(remaining) and all(remaining)


def unlink_server_copy(media_root,stored_path):
    """Remove only one regular file beneath root, without following symlinks.

    Called under the eligibility transaction. Parent directory descriptors pin the
    path. Final inode identity is checked immediately before unlinking.
    """
    root=Path(media_root).absolute();path=Path(stored_path)
    relative=path.relative_to(root) if path.is_absolute() else path
    if not relative.parts or any(part in ('.','..') for part in relative.parts):raise ValueError('Invalid media path')
    parent=os.open(root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    file_fd=None
    try:
        for component in relative.parts[:-1]:
            child=os.open(component,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=parent)
            os.close(parent);parent=child
        name=relative.parts[-1]
        try:file_fd=os.open(name,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=parent)
        except FileNotFoundError:return False
        before=os.fstat(file_fd)
        if not stat.S_ISREG(before.st_mode):raise ValueError('Not a regular media file')
        current=os.stat(name,dir_fd=parent,follow_symlinks=False)
        if (before.st_dev,before.st_ino)!=(current.st_dev,current.st_ino):raise ValueError('Media identity changed')
        os.unlink(name,dir_fd=parent)
        return True
    finally:
        if file_fd is not None:os.close(file_fd)
        os.close(parent)


def delete_if_eligible(database,media_root,resource_id,*,expired=None):
    """Recheck under BEGIN IMMEDIATE; optional expiry predicate belongs to PLA-248.

    `expired(conn,resource)` is a trusted server-side predicate, never client input.
    Preservation/owner/readiness gates apply to both delivered and expired cleanup.
    """
    with database.transaction() as conn:
        row=conn.execute('SELECT * FROM resources WHERE id=?',(resource_id,)).fetchone()
        if row is None or not row['ready_at'] or row['server_deleted_at'] or not row['server_path']:return False
        owner=conn.execute('SELECT status,email_verified_at FROM users WHERE id=?',(row['user_id'],)).fetchone()
        if not owner or owner['status']!='approved' or not owner['email_verified_at'] or preserves_server_files(conn,row['user_id']):return False
        complete=fully_delivered(conn,row)
        if not complete and not (expired and expired(conn,row)):return False
        # Nobody can update preservation, publication or recipients while this
        # transaction is held. Unlink does not follow a path into another root.
        try:
            path=Path(row['server_path'])
            relative=path.relative_to(Path(media_root).absolute()) if path.is_absolute() else path
            # Worker files are namespaced by owner. Flat legacy files remain valid,
            # but a corrupted cross-user nested reference must never be removed.
            namespaces={row['user_id']}
            try:namespaces.add(str(uuid.UUID(row['user_id'])))
            except ValueError:pass
            if len(relative.parts)>1 and relative.parts[0] not in namespaces:return False
            unlink_server_copy(media_root,row['server_path'])
        except (OSError,ValueError):return False
        timestamp=utcnow()
        write_setting(conn,'resource',resource_id,'retention_removed',True)
        conn.execute('UPDATE resources SET server_path=NULL,server_deleted_at=?,updated_at=? WHERE id=?',(timestamp,timestamp,resource_id))
        payload=json.dumps({'resource_id':resource_id,'reason':'all_recipients_confirmed' if complete else 'retention_expired','local_files_unchanged':True})
        conn.execute("INSERT INTO events(user_id,kind,payload_json,created_at) VALUES(?,'server_copy_removed',?,?)",(row['user_id'],payload,timestamp))
        conn.execute("INSERT INTO audit(action,target_id,detail_json,created_at) VALUES('resource.retention_removed',?,?,?)",(resource_id,payload,timestamp))
        return True


def sweep_confirmed(database,media_root,user_id=None):
    from contextlib import closing
    with closing(database.connect()) as conn:
        sql='SELECT id FROM resources WHERE ready_at IS NOT NULL AND server_deleted_at IS NULL'
        rows=conn.execute(sql+(' AND user_id=?' if user_id else ''),(user_id,) if user_id else ()).fetchall()
    return sum(delete_if_eligible(database,media_root,row['id']) for row in rows)
