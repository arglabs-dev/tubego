"""Actionable alerts only; callers supply sanitized stable payloads."""
import json
import re
import uuid
from tubego_server.auth import utcnow
from tubego_server.delivery import read_setting,write_setting

USER_KINDS=('download_failed',)
ADMIN_KINDS=('server_storage_paused','registration_pending','admin_maintenance')


def notify_admins(conn,kind,payload,identity):
    if kind not in ADMIN_KINDS:raise ValueError('Unsupported administrative alert')
    if read_setting(conn,'global','','admin_alert:'+identity):return False
    # Do not allow arbitrary extractor messages, paths, URLs or token payloads.
    safe={}
    for key in ('operation','status','error_code'):
        value=payload.get(key)
        if isinstance(value,str) and re.fullmatch(r'[a-z0-9_]{1,64}',value):safe[key]=value
    try:
        uuid.UUID(payload.get('user_id',''))
        safe['user_id']=payload['user_id']
    except (ValueError,TypeError,AttributeError):pass
    now=utcnow()
    for admin in conn.execute("SELECT id FROM users WHERE role='admin' AND status='approved' AND email_verified_at IS NOT NULL"):
        conn.execute('INSERT INTO events(user_id,kind,payload_json,created_at) VALUES(?,?,?,?)',(admin['id'],kind,json.dumps(safe),now))
    write_setting(conn,'global','','admin_alert:'+identity,True)
    return True


def public_alert(row):
    data=json.loads(row['payload_json']);kind=row['kind']
    title,message,destination={
        'download_failed':('Descarga fallida','La descarga falló definitivamente. Revisa el motivo y reintenta cuando corresponda.','downloads'),
        'server_storage_paused':('Almacenamiento del servidor','Las descargas del servidor están pausadas por almacenamiento.','admin'),
        'registration_pending':('Solicitud de acceso','Hay una cuenta verificada pendiente de aprobación.','registrations'),
        'admin_maintenance':('Mantenimiento del servidor','Hay un cambio de estado de una operación de mantenimiento.','admin'),
    }[kind]
    return {'id':row['id'],'kind':kind,'title':title,'message':message,'destination':destination,
            'administrative':kind in ADMIN_KINDS,'created_at':row['created_at'],
            'resource_id':data.get('resource_id') if kind=='download_failed' else None}
