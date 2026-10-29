"""Read-only, disposable HTTPS responses for UI review. Never contacts a real server.

Run: python3 visual_fixture.py --directory /tmp/tubego-visual-fixture --port 9445
Copy the generated certificate to androidTest/res/raw/visual_cert.pem before building tests.
Only synthetic accounts and GET responses are supported; all writes are rejected.
"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import ssl
import subprocess
from urllib.parse import urlparse, parse_qs

RESOURCE = dict(id='33333333-3333-4333-8333-333333333333', title='Ideas para aprender algo nuevo',
                source_url='https://video.example.invalid/ideas', media_format='video', quality='720',
                created_at='2026-10-12T08:00:00Z', server_available=True, duration_seconds=180,
                size_bytes=18000000, category='Education', latest_task=dict(id='44444444-4444-4444-8444-444444444444',
                status='completed', progress=1, phase='ready'))
USER = dict(id='11111111-1111-4111-8111-111111111111', email='persona@example.invalid',
            role='user', status='approved', level='normal', preserve_server_files=False)

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urlparse(self.path).path.removeprefix('/api/v1')
        token = self.headers.get('Authorization', '')
        admin = 'ui-admin' in token
        if path.startswith('/admin/') and not admin:
            self.respond({'detail': {'code': 'administrator_required'}}, 403)
            return
        value = {'items': [], 'next_cursor': None}
        if path == '/account/status': value = dict(role='admin' if admin else 'user', status='approved')
        elif path == '/health': value = dict(status='ok', version='ui-fixture')
        elif path == '/account/preferences/language': value = dict(language='en' if '-en' in token else 'es')
        elif path == '/account/preferences': value = dict(ask_every_time=True, selection='720', rewind_seconds=10)
        elif path == '/resources':
            category = parse_qs(urlparse(self.path).query, keep_blank_values=True).get('category')
            value = dict(items=[RESOURCE] if category is None or category == ['Education'] else [],
                         next_cursor=None, categories=['Education'])
        elif path == '/device/sync': value = dict(deliveries=[], next_delivery_cursor=None, resources=[], deletions=[])
        elif path == '/admin/storage': value = dict(used_percent=47, free_bytes=53*1024**3, total_bytes=100*1024**3, downloads_paused=False, checked_at='2026-10-12T08:00:00Z')
        elif path == '/admin/mail/status': value = dict(host='smtp.example.invalid',port=587,transport='STARTTLS',sender='avisos@example.invalid',username='servidor',password_configured=True,public_url_https=True,ready=True,test_recipient='prueba@example.invalid')
        elif path in ['/admin/users', '/admin/users/priorities', '/admin/users/retention']: value = dict(items=[USER], next_cursor=None)
        elif path == '/admin/retention/deadlines': value = dict(absolute_hours=72, delivery_hours=4)
        elif path == '/admin/maintenance': value = dict(installed=dict(tubego='ui-fixture',yt_dlp='fixture',backend_revision='fixture'),capabilities=dict(mutation_enabled=False),jobs=[])
        elif path == '/server-cleanup/preview': value = dict(files_known=3,bytes_known=54000000,in_progress=0,sizes_unknown=0,cleanup_pending=0)
        self.respond(value)
    def respond(self,value,status=200):
        body=json.dumps(value).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def do_POST(self):
        if self.command == 'POST' and urlparse(self.path).path == '/api/v1/media/analyze':
            body=json.loads(self.rfile.read(min(int(self.headers.get('Content-Length','0')),16384)))
            url=body.get('url','')
            tracks=[dict(id='en~original',language='en',kind='original')]
            if 'no-spanish' not in url:
                tracks += [dict(id='es-es~dubbed',language='es-es',kind='dubbed'),dict(id='es-419~auto',language='es-419',kind='auto')]
            self.respond(dict(url=url,title='Video con idiomas',duration_seconds=20,source_id='audio-fixture',extractor='Fixture',audio_tracks=tracks))
            return
        self.respond({'detail':{'code':'read_only_visual_fixture'}},405)
    do_PUT=do_POST
    do_DELETE=do_POST
    def log_message(self,*args): pass

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--directory',type=Path,required=True);parser.add_argument('--port',type=int,default=9445);args=parser.parse_args()
    args.directory.mkdir(parents=True,exist_ok=True)
    cert,key=args.directory/'cert.pem',args.directory/'key.pem'
    if not cert.exists():
        subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','3','-keyout',str(key),'-out',str(cert),'-subj','/CN=TubeGo visual fixture','-addext','subjectAltName=IP:10.0.2.2,IP:127.0.0.1'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        key.chmod(0o600)
    server=ThreadingHTTPServer(('127.0.0.1',args.port),Handler);tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.load_cert_chain(cert,key);server.socket=tls.wrap_socket(server.socket,server_side=True)
    print(f'Visual fixture on https://10.0.2.2:{args.port}; certificate {cert}',flush=True);server.serve_forever()
if __name__=='__main__':main()
