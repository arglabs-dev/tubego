"""Conservative portal identities; unknown query/capability parameters are preserved."""
import hashlib
import re
from urllib.parse import urlsplit,parse_qs
from tubego_server.delivery import read_setting,write_setting

YT_ID=re.compile(r'^[A-Za-z0-9_-]{11}$')

def youtube_id(url):
    parts=urlsplit(url);host=(parts.hostname or '').lower();path=parts.path.strip('/');value=None
    if host in ('youtu.be','www.youtu.be') and '/' not in path:value=path
    elif host in ('youtube.com','www.youtube.com','m.youtube.com','music.youtube.com','www.youtube-nocookie.com'):
        if path=='watch':
            values=parse_qs(parts.query).get('v',[])
            if len(values)==1:value=values[0]
        elif path.startswith(('shorts/','embed/','v/')) and path.count('/')==1:value=path.split('/')[1]
    return value if value and YT_ID.fullmatch(value) else None

def identity(url):
    video=youtube_id(url)
    return 'youtube:'+video if video else 'url:'+url

def key(value,fields):
    return hashlib.sha256((value+'\n'+fields['media_format']+'\n'+fields['quality']).encode()).hexdigest()

def alias_key(url):return 'source_identity:'+hashlib.sha256(url.encode()).hexdigest()

def extracted_identity(info):
    url=info.get('webpage_url') or info.get('url') or ''
    known=youtube_id(url)
    if known:return 'youtube:'+known
    extractor=info.get('extractor') or info.get('extractor_key');source=info.get('source_id') or info.get('id')
    if not isinstance(extractor,str) or not extractor or extractor.lower()=='generic' or source is None:return identity(url) if url else None
    host=(urlsplit(url).hostname or '').lower()
    if not host:return None
    return 'extractor:'+host+':'+extractor.lower()[:128]+':'+str(source)[:256]

def remember(conn,user_id,input_url,info,resource_id=None):
    value=extracted_identity(info)
    if not value:return
    write_setting(conn,'user',user_id,alias_key(input_url),value)
    canonical=info.get('webpage_url') or info.get('url')
    if canonical:write_setting(conn,'user',user_id,alias_key(canonical),value)
    if resource_id:write_setting(conn,'resource',resource_id,'source_identity',value)

def known(conn,user_id,url):return read_setting(conn,'user',user_id,alias_key(url)) or identity(url)
