"""Restricted HTTP/CONNECT proxy for yt-dlp. No destination is resolved twice."""
import argparse
import ipaddress
import select
import socket
import socketserver
import time
from urllib.parse import urlsplit, urlunsplit

MAX_HEADERS = 32768
MAX_BODY = 1024 * 1024
TIMEOUT = 30


class DestinationDenied(ValueError):
    pass


def destination(host, port, resolver=None):
    resolver = resolver or socket.getaddrinfo
    if port not in (80, 443) or not host or '%' in host:
        raise DestinationDenied()
    try:
        host = host.rstrip('.').encode('idna').decode('ascii').lower()
        if host == 'localhost' or host.endswith(('.localhost', '.local', '.internal')):
            raise DestinationDenied()
        answers = resolver(host, port, type=socket.SOCK_STREAM)
        if not answers:
            raise DestinationDenied()
        for family, kind, protocol, _, address in answers:
            ip = ipaddress.ip_address(address[0])
            if (not ip.is_global or ip.is_multicast or ip.is_unspecified
                    or (isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped)):
                raise DestinationDenied()
            if family not in (socket.AF_INET, socket.AF_INET6):
                raise DestinationDenied()
        return host, answers
    except (ValueError, UnicodeError, OSError):
        raise DestinationDenied() from None


def connect_public(host, port):
    _, answers = destination(host, port)
    for family, kind, protocol, _, address in answers:
        sock = socket.socket(family, socket.SOCK_STREAM, protocol)
        sock.settimeout(TIMEOUT)
        try:
            # Numeric sockaddr from the validated DNS answer. No second DNS lookup.
            sock.connect(address)
            return sock
        except OSError:
            sock.close()
    raise OSError('Destination connection failed')


def parse_target(method, target):
    try:
        parts = urlsplit('//' + target if method == 'CONNECT' else target)
        if parts.username is not None or parts.password is not None or not parts.hostname:
            raise DestinationDenied()
        if '\\' in target or any(c.isspace() or ord(c) < 32 for c in target):
            raise DestinationDenied()
        if method == 'CONNECT':
            if parts.path or parts.query or parts.fragment or parts.port is None:
                raise DestinationDenied()
            port = parts.port
        else:
            if parts.scheme not in ('http', 'https') or parts.fragment:
                raise DestinationDenied()
            # HTTPS is tunneled via CONNECT; HTTP forwarding cannot substitute TLS.
            if parts.scheme != 'http':
                raise DestinationDenied()
            port = parts.port or 80
        if port not in (80, 443):
            raise DestinationDenied()
        return parts.hostname, port, urlunsplit(('', '', parts.path or '/', parts.query, ''))
    except ValueError:
        raise DestinationDenied() from None


def relay(left, right):
    sockets = [left, right]
    last_data = time.monotonic()
    while True:
        ready, _, _ = select.select(sockets, [], [], 1)
        if not ready:
            if time.monotonic() - last_data >= TIMEOUT:
                return
            continue
        for source in ready:
            data = source.recv(65536)
            if not data:
                return
            last_data = time.monotonic()
            (right if source is left else left).sendall(data)


class ProxyHandler(socketserver.StreamRequestHandler):
    rbufsize = 0

    def respond(self, status):
        # No URL, query, upstream exception, or header appears in diagnostics.
        reason = {400: 'Bad Request', 403: 'Forbidden', 405: 'Method Not Allowed',
                  431: 'Request Header Fields Too Large', 502: 'Bad Gateway'}[status]
        self.connection.sendall(f'HTTP/1.1 {status} {reason}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n'.encode())

    def handle(self):
        self.connection.settimeout(TIMEOUT)
        upstream = None
        started = False
        try:
            first = self.rfile.readline(MAX_HEADERS + 1)
            if len(first) > MAX_HEADERS:
                self.respond(431); return
            method, target, version = first.decode('ascii').rstrip('\r\n').split(' ')
            if method not in ('CONNECT', 'GET', 'HEAD', 'POST'):
                self.respond(405); return
            if version not in ('HTTP/1.0', 'HTTP/1.1'):
                self.respond(400); return
            headers = {}
            size = len(first)
            while True:
                line = self.rfile.readline(MAX_HEADERS + 1)
                size += len(line)
                if size > MAX_HEADERS:
                    self.respond(431); return
                if line == b'\r\n': break
                if not line or not line.endswith(b'\r\n') or line[:1] in (b' ', b'\t'):
                    raise ValueError()
                name, value = line[:-2].decode('latin1').split(':', 1)
                if not name or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#$%&\'*+-.^_`|~' for c in name):
                    raise ValueError()
                value = value.strip()
                if any(ord(c) < 32 or ord(c) == 127 for c in value) or name.lower() in headers:
                    raise ValueError()
                headers[name.lower()] = value
            if 'transfer-encoding' in headers:
                raise ValueError()
            length = int(headers.get('content-length', '0'))
            if length < 0 or length > MAX_BODY or (method in ('CONNECT', 'HEAD') and length):
                raise ValueError()
            host, port, path = parse_target(method, target)
            upstream = self.server.connector(host, port)
            if method == 'CONNECT':
                self.connection.sendall(b'HTTP/1.1 200 Connection Established\r\n\r\n')
                started = True
                relay(self.connection, upstream)
            else:
                authority = f'[{host}]' if ':' in host else host
                if port != 80: authority += f':{port}'
                # Remove hop-by-hop headers including those named by Connection.
                hop = {'host', 'connection', 'proxy-connection', 'proxy-authorization',
                       'keep-alive', 'te', 'trailer', 'upgrade', 'expect'}
                hop.update(x.strip().lower() for x in headers.get('connection', '').split(','))
                outbound = f'{method} {path} HTTP/1.1\r\nHost: {authority}\r\nConnection: close\r\n'
                outbound += ''.join(f'{name}: {value}\r\n' for name, value in headers.items() if name not in hop)
                upstream.sendall((outbound + '\r\n').encode('latin1'))
                if length:
                    remaining = length
                    while remaining:
                        chunk = self.rfile.read(min(remaining, 65536))
                        if not chunk: raise ValueError()
                        upstream.sendall(chunk)
                        remaining -= len(chunk)
                started = True
                while True:
                    chunk = upstream.recv(65536)
                    if not chunk: break
                    self.connection.sendall(chunk)
        except DestinationDenied:
            if not started: self.respond(403)
        except (ValueError, UnicodeError):
            if not started: self.respond(400)
        except OSError:
            if not started:
                try: self.respond(502)
                except OSError: pass
        finally:
            if upstream: upstream.close()


class ProxyServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    connector = staticmethod(connect_public)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8081)
    args = parser.parse_args()
    with ProxyServer((args.host, args.port), ProxyHandler) as server:
        server.serve_forever()


if __name__ == '__main__': main()
