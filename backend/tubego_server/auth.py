"""Shared authentication primitives. Session issuance belongs to PLA-229."""
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import hmac
import secrets
from fastapi import Request, HTTPException


def utcnow():
    return datetime.now(timezone.utc).isoformat()


def token_hash(token):
    return hashlib.sha256(token.encode()).hexdigest()


def hash_password(password):
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(password.encode(), salt=salt, n=16384, r=8, p=1).hex()
    return f"scrypt$16384$8$1${salt.hex()}${digest}"


def verify_password(password, encoded):
    try:
        kind, n, r, p, salt, digest = encoded.split("$")
        if kind != "scrypt" or (n, r, p) != ("16384", "8", "1"):
            return False
        actual = hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt), n=16384, r=8, p=1).hex()
        return hmac.compare_digest(actual, digest)
    except (ValueError, TypeError):
        return False


def get_principal(request: Request):
    header = request.headers.get("Authorization", "")
    if not header.startswith("Bearer ") or len(header) > 512:
        raise HTTPException(401, "Authentication required")
    with closing(request.app.state.db.connect()) as conn:
        row = conn.execute("""SELECT u.*, s.id AS session_id, s.device_id FROM users u
            JOIN sessions s ON u.id=s.user_id LEFT JOIN devices d ON d.id=s.device_id
            WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at>?
            AND (s.device_id IS NULL OR d.revoked_at IS NULL)""",
            (token_hash(header[7:]), utcnow())).fetchone()
    if row is None:
        raise HTTPException(401, "Invalid or expired session")
    if row["status"] in ("blocked", "rejected"):
        raise HTTPException(403, "Account unavailable")
    return dict(row)


def require_approved(request: Request):
    principal = get_principal(request)
    if principal["status"] != "approved" or not principal["email_verified_at"]:
        raise HTTPException(403, "Email verification and approval required")
    return principal


def require_admin(request: Request):
    principal = require_approved(request)
    if principal["role"] != "admin":
        raise HTTPException(403, "Administrator required")
    return principal
