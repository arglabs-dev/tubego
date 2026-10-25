"""Onboarding integration with real loopback HTTP + SMTP sockets, no mail mocks.

HTTP and plaintext SMTP are fixture-only; Android production still requires
trusted HTTPS and the configured production SMTP STARTTLS policy.
"""
import email
import queue
import re
import socket
import socketserver
import threading
import time
import uuid
import requests
import uvicorn
from tubego_server.config import Settings
from tubego_server.main import create_app
from tubego_server import bootstrap_admin


class SMTP(socketserver.StreamRequestHandler):
    def handle(self):
        self.wfile.write(b'220 Tubego fixture relay\r\n')
        while line:=self.rfile.readline(8192):
            command=line.decode('ascii',errors='replace').strip().upper()
            if command.startswith(('EHLO','HELO')):self.wfile.write(b'250 fixture\r\n')
            elif command.startswith(('MAIL FROM:','RCPT TO:','RSET','NOOP')):self.wfile.write(b'250 OK\r\n')
            elif command=='DATA':
                self.wfile.write(b'354 Send message\r\n');body=[]
                while value:=self.rfile.readline(8192):
                    if value==b'.\r\n':break
                    body.append(value[1:] if value.startswith(b'..') else value)
                self.server.messages.put(email.message_from_bytes(b''.join(body)))
                self.wfile.write(b'250 Accepted\r\n')
            elif command=='QUIT':self.wfile.write(b'221 Bye\r\n');return
            else:self.wfile.write(b'500 Unsupported fixture command\r\n')


class Relay(socketserver.ThreadingTCPServer):
    allow_reuse_address=True
    daemon_threads=True


def token(messages,recipient):
    message=messages.get(timeout=3)
    assert message['To']==recipient
    text=message.get_payload(decode=True).decode('utf-8')
    assert 'https://tubego.fixture.example/api/v1/auth/' in text
    return re.search(r'#token=([^\s]+)',text).group(1)


def test_real_smtp_registration_verification_admin_approval_reset_and_reauth(tmp_path,monkeypatch):
    relay=Relay(('127.0.0.1',0),SMTP);relay.messages=queue.Queue()
    relay_thread=threading.Thread(target=relay.serve_forever,daemon=True);relay_thread.start()
    settings=Settings(tmp_path,public_url='https://tubego.fixture.example',smtp_host='127.0.0.1',smtp_port=relay.server_address[1],smtp_sender='no-reply@fixture.example',smtp_tls=False)
    app=create_app(settings)
    listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen(128)
    server=uvicorn.Server(uvicorn.Config(app,log_level='critical',access_log=False))
    thread=threading.Thread(target=lambda:server.run(sockets=[listener]),daemon=True);thread.start()
    base='http://127.0.0.1:'+str(listener.getsockname()[1])+'/api/v1'
    try:
        deadline=time.monotonic()+5
        while not server.started and time.monotonic()<deadline:time.sleep(.01)
        assert server.started
        monkeypatch.setenv('TUBEGO_DATA_DIR',str(tmp_path));monkeypatch.setenv('TUBEGO_BOOTSTRAP_PASSWORD','fixture-admin-password')
        monkeypatch.setattr('sys.argv',['bootstrap_admin','admin@fixture.example']);bootstrap_admin.main()
        with requests.Session() as http:
            http.trust_env=False
            def post(path,data,headers=None):return http.post(base+path,json=data,headers=headers,timeout=5)
            def get(path,headers=None):return http.get(base+path,headers=headers,timeout=5)
            assert get('/health').json()['status']=='ok'
            assert post('/auth/register',{'email':'student@fixture.example','password':'fixture-user-password'}).status_code==202
            verification=token(relay.messages,'student@fixture.example')
            assert post('/auth/verify',{'token':verification}).json()['status']=='pending_approval'
            user=post('/auth/login',{'email':'student@fixture.example','password':'fixture-user-password','device_name':'QA Android'}).json()
            headers={'Authorization':'Bearer '+user['token']}
            assert get('/account/status',headers).json()['status']=='pending_approval'
            assert post('/resources',{'url':'https://example.org/video','selection':'720','request_id':str(uuid.uuid4())},headers).status_code==403
            admin=post('/auth/login',{'email':'admin@fixture.example','password':'fixture-admin-password','device_name':'QA admin'}).json()
            admin_headers={'Authorization':'Bearer '+admin['token']}
            pending=get('/admin/registrations',admin_headers).json();assert any(row['id']==user['user_id'] for row in pending)
            assert post('/admin/registrations/'+user['user_id']+'/decision',{'approve':True},admin_headers).status_code==200
            assert get('/account/status',headers).json()['status']=='approved'
            assert get('/resources',headers).status_code==200
            assert post('/auth/password/forgot',{'email':'student@fixture.example'}).status_code==202
            reset=token(relay.messages,'student@fixture.example')
            assert post('/auth/password/reset',{'token':reset,'password':'fixture-new-password','revoke_other_sessions':True}).status_code==200
            replacement=post('/auth/login',{'email':'student@fixture.example','password':'fixture-new-password','device_name':'QA Android','device_id':user['device_id']}).json()
            assert replacement['device_id']==user['device_id']
            with app.state.db.transaction() as conn:conn.execute("UPDATE sessions SET expires_at='2000-01-01T00:00:00+00:00' WHERE device_id=? AND revoked_at IS NULL",(replacement['device_id'],))
            expired=get('/account/status',{'Authorization':'Bearer '+replacement['token']})
            assert expired.status_code==401 and expired.json()['detail']['code']=='session_expired'
            fresh=post('/auth/login',{'email':'student@fixture.example','password':'fixture-new-password','device_name':'QA Android','device_id':replacement['device_id']}).json()
            assert fresh['device_id']==replacement['device_id']
            assert post('/device/commands',{'id':str(uuid.uuid4()),'sequence':1,'kind':'noop','payload':{}},{'Authorization':'Bearer '+fresh['token']}).json()['status']=='complete'
    finally:
        server.should_exit=True;thread.join(timeout=5);listener.close()
        relay.shutdown();relay.server_close();relay_thread.join(timeout=2)
