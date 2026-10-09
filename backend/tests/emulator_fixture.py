"""Explicit, local-only HTTPS fixture for TransferInstrumentedTest.

Run from repository root: PYTHONPATH=backend:. python backend/tests/emulator_fixture.py
This is not production setup. Credentials/content are disposable test data.
"""
import argparse
from pathlib import Path
import subprocess
import uuid
import uvicorn
from tubego_server.config import Settings
from tubego_server.db import Database
from tubego_server.main import create_app
from tubego_server.auth import hash_password,utcnow
from tubego_server.delivery import publish_ready

USER='11111111-1111-4111-8111-111111111111'
RESOURCE='22222222-2222-4222-8222-222222222222'
EMAIL='tubego-test@example.invalid'
PASSWORD='MVP test password 2026'


def prepare(directory: Path):
    directory.mkdir(parents=True,exist_ok=True)
    settings=Settings(directory/'data')
    database=Database(settings.database_path);database.initialize()
    stamp=utcnow()
    with database.transaction() as conn:
        conn.execute("""INSERT INTO users VALUES(?,?,?,'user','approved',?,?,?)
            ON CONFLICT(id) DO UPDATE SET password_hash=excluded.password_hash,status='approved',email_verified_at=excluded.email_verified_at""",(USER,EMAIL,hash_password(PASSWORD),stamp,stamp,stamp))
        conn.execute("""INSERT OR IGNORE INTO resources(id,user_id,source_url,title,created_at,updated_at)
            VALUES(?,?,'https://example.invalid/fixture','Transfer test fixture',?,?)""",(RESOURCE,USER,stamp,stamp))
    root=settings.data_dir/'media';root.mkdir(exist_ok=True)
    target=root/(RESOURCE+'.bin')
    with target.open('wb') as stream:
        pattern=bytes(range(256))*4096
        for _ in range(16):stream.write(pattern)
    publish_ready(database,root,RESOURCE,target.name)
    cert=directory/'cert.pem';key=directory/'key.pem'
    if not cert.exists() or not key.exists():
        subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-sha256','-days','2','-nodes',
                        '-keyout',str(key),'-out',str(cert),'-subj','/CN=Tubego emulator fixture',
                        '-addext','subjectAltName=IP:10.0.2.2,IP:127.0.0.1'],check=True,
                       stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        key.chmod(0o600)
    return settings,cert,key


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory',type=Path,default=Path('/tmp/tubego-emulator-fixture'))
    parser.add_argument('--port',type=int,default=9443)
    args=parser.parse_args();settings,cert,key=prepare(args.directory)
    print(f'Local test server: https://10.0.2.2:{args.port}; instrumentation certificate: {cert}',flush=True)
    # Loopback only. Standard hostname verification remains enabled in the app.
    uvicorn.run(create_app(settings),host='127.0.0.1',port=args.port,
                ssl_certfile=str(cert),ssl_keyfile=str(key),log_level='warning')


if __name__=='__main__':main()
