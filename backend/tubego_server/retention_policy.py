"""UTC server-clock deadlines evaluated from original publication timestamps."""
from contextlib import closing
from datetime import datetime,timedelta,timezone
from tubego_server.delivery import read_setting
from tubego_server.retention import preserves_server_files,delete_if_eligible

DEFAULT_ABSOLUTE_HOURS=72
DEFAULT_DELIVERY_HOURS=4
MAX_HOURS=8760


def clock():return datetime.now(timezone.utc)


def timestamp(value):
    if not value:return None
    try:
        result=datetime.fromisoformat(value.replace('Z','+00:00'))
        if result.tzinfo is None:return None
        return result.astimezone(timezone.utc)
    except (TypeError,ValueError):return None


def policy(conn):
    stored=read_setting(conn,'global','','retention_deadlines')
    if not isinstance(stored,dict):stored={}
    return {'absolute_hours':stored.get('absolute_hours',DEFAULT_ABSOLUTE_HOURS),
            'delivery_hours':stored.get('delivery_hours',DEFAULT_DELIVERY_HOURS)}


def deadline(resource,config):
    ready=timestamp(resource['ready_at'])
    if ready is None:return None
    absolute=ready+timedelta(hours=config['absolute_hours'])
    first=timestamp(resource['first_delivered_at'])
    return min(absolute,first+timedelta(hours=config['delivery_hours'])) if first else absolute


def is_expired(conn,resource,now=None,config=None):
    end=deadline(resource,config or policy(conn))
    return end is not None and (now or clock())>=end


def expired_candidates(conn,config,now):
    rows=conn.execute("""SELECT r.* FROM resources r JOIN users u ON u.id=r.user_id
        WHERE r.ready_at IS NOT NULL AND r.server_path IS NOT NULL
        AND r.server_deleted_at IS NULL AND u.status='approved' AND u.email_verified_at IS NOT NULL""").fetchall()
    return [row['id'] for row in rows if not preserves_server_files(conn,row['user_id']) and is_expired(conn,row,now,config)]


def sweep_expired(database,media_root):
    with closing(database.connect()) as conn:
        ids=expired_candidates(conn,policy(conn),clock())
    # Fresh policy/clock per resource, inside the unlink transaction. An older scan
    # can never override a newly extended deadline or preservation flag.
    return sum(delete_if_eligible(database,media_root,key,expired=is_expired) for key in ids)
