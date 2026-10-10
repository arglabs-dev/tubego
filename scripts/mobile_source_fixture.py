"""Disposable real public-source TLS fixture; not production or SMTP verification."""
import argparse
import os
import subprocess
import threading
from pathlib import Path
import uvicorn
from fastapi.responses import JSONResponse
from tubego_server.config import Settings
from tubego_server.db import Database
from tubego_server.auth import hash_password, utcnow
from tubego_server.main import create_app
from tubego_server.worker import Worker
from tubego_server.egress_proxy import ProxyServer, ProxyHandler

SOURCE = 'https://download.blender.org/durian/trailer/sintel_trailer-480p.mp4'
USER = '11111111-1111-4111-8111-111111111111'
EMAIL = 'tubego-test@example.invalid'
PASSWORD = 'MVP test password 2026'


def prepare(directory):
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    if directory.is_symlink() or directory.stat().st_uid != os.getuid():
        raise RuntimeError('Fixture directory must be operator-owned and not a symlink')
    directory.chmod(0o700)
    settings = Settings(directory / 'data')
    marker = directory / '.tubego-mobile-source-fixture'
    if settings.database_path.exists():
        raise RuntimeError('Each run requires a fresh directory/database; refusing any existing database')
    marker.write_text('Disposable public-source instrumentation fixture\n')
    marker.chmod(0o600)
    database = Database(settings.database_path)
    database.initialize()
    stamp = utcnow()
    with database.transaction() as conn:
        conn.execute("""INSERT INTO users VALUES(?,?,?,'user','approved',?,?,?)
            ON CONFLICT(id) DO UPDATE SET password_hash=excluded.password_hash,
            status='approved',email_verified_at=excluded.email_verified_at""",
            (USER, EMAIL, hash_password(PASSWORD), stamp, stamp, stamp))
    cert, key = directory / 'cert.pem', directory / 'key.pem'
    if not cert.exists() or not key.exists():
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-sha256',
            '-days', '2', '-nodes', '-keyout', str(key), '-out', str(cert),
            '-subj', '/CN=Tubego emulator fixture', '-addext',
            'subjectAltName=IP:10.0.2.2,IP:127.0.0.1'], check=True,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        key.chmod(0o600)
    return settings, cert, key


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--port', type=int, default=9443)
    args = parser.parse_args()
    settings, cert, key = prepare(args.directory)
    proxy = ProxyServer(('127.0.0.1', 0), ProxyHandler)
    threading.Thread(target=proxy.serve_forever, daemon=True).start()
    os.environ['TUBEGO_MEDIA_EGRESS_PROXY'] = 'http://127.0.0.1:' + str(proxy.server_address[1])
    stopped = threading.Event()

    def process():
        with Worker(settings) as worker:
            while not stopped.is_set():
                if not worker.run_once():
                    stopped.wait(.2)
    thread = threading.Thread(target=process, daemon=True)
    thread.start()
    print(f'Fixture https://10.0.2.2:{args.port}; copy {cert} to androidTest raw/fixture_cert.pem', flush=True)
    app = create_app(settings)

    @app.middleware('http')
    async def fixed_fixture_source(request, call_next):
        if request.method == 'POST' and request.url.path in ('/api/v1/resources', '/api/v1/device/commands', '/api/v1/media/analyze'):
            body = await request.json()
            payload = body.get('payload', {}) if request.url.path.endswith('/commands') else body
            if ('url' in payload and payload['url'] != SOURCE):
                return JSONResponse({'error': {'code': 'unsupported', 'message': 'This fixture accepts only its fixed public Blender source'}}, status_code=422)
        return await call_next(request)

    try:
        uvicorn.run(app, host='127.0.0.1', port=args.port,
                    ssl_certfile=str(cert), ssl_keyfile=str(key), log_level='warning')
    finally:
        stopped.set()
        thread.join(timeout=10)
        proxy.shutdown()
        proxy.server_close()


if __name__ == '__main__':
    main()
