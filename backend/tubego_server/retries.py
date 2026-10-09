"""Bounded retries with stable, secret-free diagnostic classifications."""
from datetime import datetime,timedelta,timezone
import errno
import re
import socket
from tubego_server.media import MediaError

DELAYS=(30,120,300)


def clock():return datetime.now(timezone.utc)


def next_retry(attempts,now=None):
    return ((now or clock())+timedelta(seconds=DELAYS[attempts-1])).isoformat() if 1<=attempts<=len(DELAYS) else None


def transient(error):
    if isinstance(error,MediaError):return error.code=='temporary_failure'
    if isinstance(error,(TimeoutError,ConnectionError,socket.gaierror)):return True
    if isinstance(error,OSError):return error.errno in (errno.ETIMEDOUT,errno.ECONNRESET,errno.ECONNREFUSED,errno.ENETUNREACH,errno.EHOSTUNREACH,errno.EPIPE)
    details=getattr(error,'exc_info',None)
    cause=details[1] if details and len(details)>1 else getattr(error,'__cause__',None)
    if cause is not None and cause is not error and transient(cause):return True
    # Extractors wrap HTTP/socket errors. Inspect locally; never store the text.
    message=str(error).lower()
    if re.search(r'(?:http(?: error)?|status(?: code)?)\s*[:=]?\s*(429|5\d\d)\b',message):return True
    return any(value in message for value in ('timed out','timeout','connection reset','connection refused','temporary failure in name resolution','network is unreachable','remote end closed','incomplete read'))
