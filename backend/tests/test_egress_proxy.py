from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import socket
import threading
import pytest
from tubego_server.egress_proxy import (DestinationDenied, ProxyHandler, ProxyServer,
                                        connect_public, destination, parse_target)
from tubego_server.media import analyze_media


def answers(host, port, **kwargs):
    return [(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('8.8.8.8', port))]


@pytest.mark.parametrize('address', ['127.0.0.1', '10.0.0.1', '169.254.169.254',
    '192.168.1.2', '::1', 'fe80::1', 'fc00::1', '::ffff:8.8.8.8', '224.0.0.1', 'ff02::1'])
def test_local_dns_denied(address):
    def resolver(host, port, **kwargs):
        return [(socket.AF_INET6 if ':' in address else socket.AF_INET, 1, 6, '', (address, port))]
    with pytest.raises(DestinationDenied): destination('video.example', 80, resolver)


@pytest.mark.parametrize('host,port', [('localhost',80),('a.local',443),('a.internal',80),('video.example',8080)])
def test_hosts_ports_denied(host, port):
    with pytest.raises(DestinationDenied): destination(host, port, answers)


def test_mixed_dns_denied():
    def resolver(host, port, **kwargs):
        return answers(host, port) + [(socket.AF_INET, 1, 6, '', ('127.0.0.1', port))]
    with pytest.raises(DestinationDenied): destination('video.example', 80, resolver)


def test_connect_uses_validated_sockaddr(monkeypatch):
    seen = []
    class FakeSocket:
        def settimeout(self, timeout): pass
        def connect(self, address): seen.append(address)
        def close(self): pass
    def resolver(host, port, **kwargs):
        seen.append((host,port))
        return answers(host,port)
    monkeypatch.setattr(socket, 'getaddrinfo', resolver)
    monkeypatch.setattr(socket, 'socket', lambda *args: FakeSocket())
    connect_public('video.example', 443)
    assert seen == [('video.example', 443), ('8.8.8.8', 443)]


@pytest.mark.parametrize('method,target', [('CONNECT','127.0.0.1:8080'),
    ('CONNECT','user:pass@video.example:443'), ('CONNECT','video.example:443/path'),
    ('GET','file:///etc/passwd'), ('GET','https://video.example/x'),
    ('GET','http://user:pass@video.example/')])
def test_target_restrictions(method,target):
    with pytest.raises(DestinationDenied): parse_target(method,target)


class Source(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        if self.path == '/redirect':
            self.send_response(302)
            self.send_header('Location', 'http://127.0.0.1/private')
            self.end_headers()
        else:
            self.send_response(200)
            self.send_header('Content-Type', 'video/mp4')
            self.send_header('Content-Length', '4')
            self.end_headers()
            self.wfile.write(b'fake')
    def do_HEAD(self):
        self.send_response(200)
        self.send_header('Content-Type', 'video/mp4')
        self.send_header('Content-Length', '4')
        self.end_headers()


@contextmanager
def proxy_fixture():
    source = ThreadingHTTPServer(('127.0.0.1',0), Source)
    proxy = ProxyServer(('127.0.0.1',0), ProxyHandler)
    contacted = []
    proxy.denied = []
    def connector(host, port):
        # Test-only fake Internet: validate public DNS, then map its IP to fixture.
        def resolver(name, target_port, **kwargs):
            if name in ('video.example','public.example'): return answers(name,target_port)
            return socket.getaddrinfo(name,target_port,type=socket.SOCK_STREAM)
        try:
            destination(host, port, resolver)
        except DestinationDenied:
            proxy.denied.append((host,port))
            raise
        contacted.append((host,port))
        return socket.create_connection(source.server_address)
    proxy.connector = connector
    for server in (source,proxy):
        threading.Thread(target=server.serve_forever, daemon=True).start()
    try: yield proxy, contacted
    finally:
        proxy.shutdown(); proxy.server_close(); source.shutdown(); source.server_close()


def request(proxy, raw):
    with socket.create_connection(proxy.server_address) as conn:
        conn.settimeout(3)
        conn.sendall(raw)
        chunks=[]
        while True:
            data=conn.recv(65536)
            if not data: break
            chunks.append(data)
        return b''.join(chunks)


def test_http_public_then_private_redirect_rejected():
    with proxy_fixture() as (proxy, contacted):
        first = request(proxy, b'GET http://video.example/redirect HTTP/1.1\r\nHost: video.example\r\n\r\n')
        assert b'302' in first and b'Location: http://127.0.0.1/private' in first
        follow = request(proxy, b'GET http://127.0.0.1/private HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n')
        assert follow.startswith(b'HTTP/1.1 403')
        assert contacted == [('video.example',80)]


def test_private_connect_rejected():
    with proxy_fixture() as (proxy, contacted):
        reply = request(proxy, b'CONNECT 169.254.169.254:443 HTTP/1.1\r\nHost: 169.254.169.254\r\n\r\n')
        assert reply.startswith(b'HTTP/1.1 403') and not contacted


def test_public_connect_tunnel():
    with proxy_fixture() as (proxy, contacted):
        with socket.create_connection(proxy.server_address) as conn:
            conn.settimeout(3)
            conn.sendall(b'CONNECT public.example:443 HTTP/1.1\r\nHost: public.example:443\r\n\r\n')
            assert b'200 Connection Established' in conn.recv(4096)
            # HTTP in this test tunnel substitutes for an encrypted HTTPS stream.
            conn.sendall(b'GET /video.mp4 HTTP/1.0\r\n\r\n')
            reply = conn.recv(4096)
            assert b'200 OK' in reply
        assert contacted == [('public.example',443)]


@pytest.mark.parametrize('raw,status', [
    (b'GET http://video.example/ HTTP/1.1\r\nContent-Length: 0\r\nContent-Length: 4\r\n\r\n',400),
    (b'GET http://video.example/ HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n',400),
    (b'GET http://video.example/ HTTP/1.1\r\nX: '+b'x'*32768+b'\r\n\r\n',431),
    (b'DELETE http://video.example/ HTTP/1.1\r\n\r\n',405)])
def test_request_limits(raw,status):
    with proxy_fixture() as (proxy, contacted):
        assert request(proxy,raw).startswith(f'HTTP/1.1 {status}'.encode())
        assert not contacted


def test_real_ytdlp_client_through_restricted_proxy(monkeypatch):
    with proxy_fixture() as (proxy, contacted):
        monkeypatch.setenv('TUBEGO_MEDIA_EGRESS_PROXY', f'http://127.0.0.1:{proxy.server_address[1]}')
        result = analyze_media('http://video.example/lecture.mp4', resolver=answers)
        assert result['title'] == 'lecture'
        assert result['duration_seconds'] is None
        assert contacted and all(host == 'video.example' for host,port in contacted)


def test_real_ytdlp_redirect_cannot_bypass_proxy_via_no_proxy(monkeypatch):
    from tubego_server.media import MediaError
    with proxy_fixture() as (proxy, contacted):
        monkeypatch.setenv('TUBEGO_MEDIA_EGRESS_PROXY', f'http://127.0.0.1:{proxy.server_address[1]}')
        monkeypatch.setenv('NO_PROXY', '*')
        # The request handler follows this redirect internally; initial URL guard
        # alone would not prevent it. Proxy must see and reject the private target.
        with pytest.raises(MediaError):
            analyze_media('http://video.example/redirect', resolver=answers)
        assert contacted and all(host == 'video.example' for host,port in contacted)
        assert ('127.0.0.1',80) in proxy.denied


def test_secure_engine_denies_external_network_downloader(monkeypatch):
    from tubego_server.media import MediaError, restricted_ytdlp
    monkeypatch.setenv('TUBEGO_MEDIA_EGRESS_PROXY', 'http://127.0.0.1:8081')
    with restricted_ytdlp({'external_downloader': 'curl'}) as engine:
        assert engine.params['external_downloader'] is None
        assert list(engine._request_director.handlers) == ['Requests']
        with pytest.raises(MediaError) as error:
            engine.dl('/unused/file', {'url':'rtmp://video.example/live','protocol':'rtmp'})
        assert error.value.code == 'unsupported'
