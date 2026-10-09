"""Run once: PYTHONPATH=backend python -m tubego_server.bootstrap_admin email."""
import argparse
import getpass
import os
import uuid
from tubego_server.config import Settings
from tubego_server.db import Database
from tubego_server.auth import hash_password, utcnow
from tubego_server.routers.registration import EmailInput, Registration

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("email")
    args = parser.parse_args()
    password = os.environ.get("TUBEGO_BOOTSTRAP_PASSWORD") or getpass.getpass("Admin password (12+ characters): ")
    body = Registration(email=args.email, password=password)
    db = Database(Settings.from_env().database_path)
    db.initialize()
    with db.transaction() as conn:
        if conn.execute("SELECT 1 FROM users WHERE role='admin'").fetchone():
            parser.error("An administrator already exists; bootstrap is disabled")
        now = utcnow()
        user_id = str(uuid.uuid4())
        conn.execute("INSERT INTO users VALUES (?,?,?,'admin','approved',?,?,?)", (user_id,body.email,hash_password(password),now,now,now))
        conn.execute("INSERT INTO audit(actor_user_id,action,target_id,created_at) VALUES (?,?,?,?)", (user_id,"admin.bootstrap",user_id,now))
    print("Administrator created. Login is delivered in PLA-229.")

if __name__ == "__main__":
    main()
