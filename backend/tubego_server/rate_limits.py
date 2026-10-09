from datetime import datetime, timezone, timedelta
from fastapi import HTTPException
from tubego_server.auth import token_hash

def rate_limit(request, scope, email, maximum, minutes):
    now = datetime.now(timezone.utc)
    # Do not trust client-supplied X-Forwarded-For. Proxy trust configured by uvicorn.
    ip = request.client.host if request.client else 'unknown'
    limits = [(scope+'.ip', ip, maximum*5), (scope+'.email', email, maximum)]
    blocked = False
    with request.app.state.db.transaction() as conn:
        for name,key,limit in limits:
            key = token_hash(key)
            row=conn.execute('SELECT * FROM auth_limits WHERE scope=? AND key_hash=?',(name,key)).fetchone()
            fresh = row is None or datetime.fromisoformat(row['window_start']) <= now-timedelta(minutes=minutes)
            attempts=1 if fresh else row['attempts']+1
            start=now.isoformat() if fresh else row['window_start']
            conn.execute('INSERT INTO auth_limits VALUES (?,?,?,?) ON CONFLICT(scope,key_hash) DO UPDATE SET window_start=excluded.window_start,attempts=excluded.attempts',(name,key,start,attempts))
            blocked |= attempts>limit
        conn.execute('DELETE FROM auth_limits WHERE window_start<?',((now-timedelta(days=1)).isoformat(),))
    if blocked:
        raise HTTPException(429, 'Too many attempts. Try later.',headers={'Retry-After':str(minutes*60)})

