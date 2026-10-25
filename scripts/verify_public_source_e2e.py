"""Real public-source E2E. No fake extractor, resolver, worker, ffmpeg or bearer auth.
Run with PYTHONPATH=<repo>/backend:<repo> <toolchain>/python this_file.py.
"""
import os
import argparse
import re
import shutil
import sys
import json
import uuid
import time
import hashlib
import socket
import threading
import subprocess
from pathlib import Path
from urllib.parse import urlsplit
from contextlib import closing
from datetime import datetime,timezone,timedelta
import requests
from fastapi.testclient import TestClient
from tubego_server.config import Settings
from tubego_server.main import create_app
from tubego_server.auth import utcnow,token_hash
from tubego_server.worker import Worker
from tubego_server.egress_proxy import ProxyServer,ProxyHandler,destination
from tubego_server.delivery import read_setting

SOURCES={'w3c':'https://media.w3.org/2010/05/sintel/trailer.mp4',
         'blender':'https://download.blender.org/durian/trailer/sintel_trailer-480p.mp4',
         'youtube':'https://www.youtube.com/watch?v=BaW_jenozKc',
         'youtube_zoo':'https://www.youtube.com/watch?v=jNQXAC9IVRw'}
YOUTUBE_IDS={'youtube':'BaW_jenozKc','youtube_zoo':'jNQXAC9IVRw'}
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source',choices=tuple(SOURCES),default='w3c',help='Choose a fixed, independently verified public fixture; no arbitrary URL accepted')
parser.add_argument('--revision',help='Exact 40-character tested source/build SHA when no Git checkout is present')
args=parser.parse_args()
if args.revision and not re.fullmatch(r'[0-9a-f]{40}',args.revision):parser.error('--revision requires an exact lowercase 40-character SHA')
BASE=Path('/tmp/tubego-source-e2e')
BASE.mkdir(mode=0o700,parents=True,exist_ok=True)
if BASE.is_symlink() or BASE.stat().st_uid!=os.getuid():raise RuntimeError('Private output root must be owned by the current operator, without symlinks')
BASE.chmod(0o700)
SOURCE=SOURCES[args.source]
run=BASE/('run-'+str(uuid.uuid4()));run.mkdir(mode=0o700)
revision=args.revision or os.environ.get('TUBEGO_BUILD_REVISION','')
revision_source='explicit_argument' if args.revision else 'container_build_environment'
if not re.fullmatch(r'[0-9a-f]{40}',revision):
    revision=None;revision_source='unavailable'
    if shutil.which('git'):
        try:
            observed=subprocess.run(['git','rev-parse','HEAD'],cwd=Path(__file__).resolve().parents[1],capture_output=True,text=True,check=True).stdout.strip()
            if re.fullmatch(r'[0-9a-f]{40}',observed):revision=observed;revision_source='source_checkout'
        except (subprocess.SubprocessError,OSError):pass
report={'revision':revision,'revision_source':revision_source,'source':SOURCE,'data_dir':str(run),'started':utcnow(),'stages':[]}
def stage(name,**data):
    report['stages'].append({'stage':name,**data});print(json.dumps(report['stages'][-1]),flush=True)
    (run/'report.json').write_text(json.dumps(report,indent=2))
def owner(app):
    uid,did,sid=[str(uuid.uuid4()) for _ in range(3)];token=uuid.uuid4().hex;now=utcnow()
    with app.state.db.transaction() as conn:
        conn.execute('INSERT INTO users VALUES(?,?,?,?,?,?,?,?)',(uid,uid+'@example.test','unused-fixture-password','user','approved',now,now,now))
        conn.execute('INSERT INTO devices(id,user_id,name,created_at) VALUES(?,?,?,?)',(did,uid,'E2E source fixture',now))
        conn.execute('INSERT INTO sessions VALUES(?,?,?,?,?,NULL,?)',(sid,uid,did,token_hash(token),(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat(),now))
    return {'uid':uid,'did':did,'headers':{'Authorization':'Bearer '+token}}

proxy=None;thread=None
try:
    target=urlsplit(SOURCE)
    from importlib.metadata import version,PackageNotFoundError
    dependencies={}
    for name in ('yt-dlp','yt-dlp-ejs','deno'):
        try:dependencies[name]=version(name)
        except PackageNotFoundError:dependencies[name]=None
    stage('installed_downloader_dependencies',packages=dependencies)
    addresses=destination(target.hostname,target.port or 443)[1]
    stage('source_dns_validated',addresses=sorted({a[4][0] for a in addresses}))
    proxy=ProxyServer(('127.0.0.1',0),ProxyHandler)
    thread=threading.Thread(target=proxy.serve_forever,daemon=True);thread.start()
    os.environ['TUBEGO_MEDIA_EGRESS_PROXY']='http://127.0.0.1:'+str(proxy.server_address[1])
    with requests.Session() as net:
        net.trust_env=False
        denied_proxy=net.get('http://127.0.0.1/private.mp4',proxies={'http':os.environ['TUBEGO_MEDIA_EGRESS_PROXY']},timeout=10,allow_redirects=False)
        assert denied_proxy.status_code==403
        stage('restricted_proxy_private_destination_denied',status=denied_proxy.status_code)
        verified=net.head(SOURCE,proxies={'https':os.environ['TUBEGO_MEDIA_EGRESS_PROXY']},timeout=20,allow_redirects=False)
        expected_type='text/html' if args.source in YOUTUBE_IDS else 'video/mp4'
        assert verified.status_code==200 and verified.headers.get('Content-Type','').startswith(expected_type),(verified.status_code,verified.headers.get('Content-Type'))
        stage('public_source_verified_via_restricted_proxy',status=verified.status_code,bytes=int(verified.headers.get('Content-Length') or 0),content_type=verified.headers['Content-Type'])
    app=create_app(Settings(run))
    with TestClient(app) as client:
        alice=owner(app);bob=owner(app)
        denied=client.post('/api/v1/media/analyze',headers=alice['headers'],json={'url':'http://127.0.0.1/private.mp4'})
        assert denied.status_code==422
        stage('ssrf_private_url_denied',status=denied.status_code)
        metadata=client.post('/api/v1/media/analyze',headers=alice['headers'],json={'url':SOURCE})
        assert metadata.status_code==200,metadata.text
        stage('real_metadata',metadata=metadata.json())
        if args.source in YOUTUBE_IDS:
            assert metadata.json()['source_id']==YOUTUBE_IDS[args.source]
            assert metadata.json()['extractor']=='Youtube'
            duration=metadata.json()['duration_seconds']
            assert isinstance(duration,(int,float)) and 0<duration<=30,'Fixture must remain a short test video'
        for selection in ('best','audio'):
            submitted=client.post('/api/v1/resources',headers=alice['headers'],json={'url':SOURCE,'selection':selection,'request_id':str(uuid.uuid4())})
            assert submitted.status_code==200,submitted.text
            item=submitted.json();rid=item['resource_id'];tid=item['task_id'];started=time.monotonic()
            stage('task_submitted',selection=selection,resource_id=rid,task_id=tid)
            with Worker(Settings(run)) as worker:
                assert worker.run_once()
            task=client.get('/api/v1/tasks/'+tid,headers=alice['headers'])
            assert task.status_code==200,task.text
            assert task.json()['status']=='completed',task.text
            with closing(app.state.db.connect()) as conn:
                row=conn.execute('SELECT * FROM resources WHERE id=?',(rid,)).fetchone()
                path=run/'media'/row['server_path']
                # server_path may be canonical absolute. Path joining preserves that.
                digest=read_setting(conn,'resource',rid,'media_sha256');size=row['size_bytes']
            assert path.is_file() and path.stat().st_size==size
            assert hashlib.sha256(path.read_bytes()).hexdigest()==digest
            probe=json.loads(subprocess.run(['ffprobe','-v','error','-show_format','-show_streams','-of','json',str(path)],check=True,capture_output=True,text=True).stdout)
            duration=float(probe['format']['duration']);assert 0<duration<(30 if args.source in YOUTUBE_IDS else 180)
            codecs=[s['codec_name'] for s in probe['streams']]
            if selection=='audio':assert codecs==['mp3'] and path.suffix=='.mp3'
            else:assert any(s['codec_type']=='video' for s in probe['streams'])
            head=client.head('/api/v1/resources/'+rid+'/download',headers=alice['headers'])
            assert head.status_code==200 and int(head.headers['content-length'])==size and head.headers['x-content-sha256']==digest
            first=client.get('/api/v1/resources/'+rid+'/download',headers={**alice['headers'],'Range':'bytes=0-1023'})
            next_part=client.get('/api/v1/resources/'+rid+'/download',headers={**alice['headers'],'Range':'bytes=1024-2047','If-Range':head.headers['etag']})
            assert first.status_code==next_part.status_code==206
            assert first.content+next_part.content==path.read_bytes()[:2048]
            assert first.headers['content-range']==f'bytes 0-1023/{size}'
            assert client.get('/api/v1/resources/'+rid+'/download').status_code==401
            assert client.get('/api/v1/resources/'+rid+'/download',headers=bob['headers']).status_code==404
            confirmed=client.post('/api/v1/resources/'+rid+'/deliveries/confirm',headers=alice['headers'],json={'size_bytes':size,'sha256':digest})
            assert confirmed.status_code==200,confirmed.text
            sync=client.get('/api/v1/device/sync',headers=alice['headers']);assert sync.status_code==200
            delivery=next(x for x in sync.json()['deliveries'] if x['id']==rid)
            assert delivery['delivery_status']=='complete'
            stage('real_download_conversion_publish_range_confirm',selection=selection,bytes=size,sha256=digest,duration_seconds=duration,codecs=codecs,range_status=first.status_code,elapsed_seconds=round(time.monotonic()-started,2),media_path=str(path))
    report['result']='PASS';stage('finished',result='PASS')
except Exception as exc:
    report['result']='FAIL';report['failure_type']=type(exc).__name__;report['failure']=str(exc)
    stage('failed',failure_type=type(exc).__name__,failure=str(exc))
    raise
finally:
    if proxy:proxy.shutdown();proxy.server_close()
    (run/'report.json').write_text(json.dumps(report,indent=2))
