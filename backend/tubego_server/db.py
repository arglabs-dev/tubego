"""SQLite storage with atomic, versioned migrations and foreign keys."""
from contextlib import contextmanager, closing
from pathlib import Path
import sqlite3

SCHEMA_VERSION = 3
SCHEMA = [
    """CREATE TABLE users (
        id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE COLLATE NOCASE,
        password_hash TEXT NOT NULL, role TEXT NOT NULL DEFAULT 'user',
        status TEXT NOT NULL DEFAULT 'pending_verification',
        email_verified_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
    )""",
    """CREATE TABLE devices (
        id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        name TEXT NOT NULL, platform TEXT NOT NULL DEFAULT 'android',
        last_seen_at TEXT, revoked_at TEXT, created_at TEXT NOT NULL
    )""",
    """CREATE TABLE sessions (
        id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        device_id TEXT REFERENCES devices(id) ON DELETE CASCADE,
        token_hash TEXT NOT NULL UNIQUE, expires_at TEXT NOT NULL,
        revoked_at TEXT, created_at TEXT NOT NULL
    )""",
    """CREATE TABLE resources (
        id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        source_url TEXT NOT NULL, source_key TEXT, title TEXT, duration_seconds REAL,
        media_format TEXT NOT NULL DEFAULT 'video', quality TEXT NOT NULL DEFAULT '720',
        server_path TEXT, size_bytes INTEGER, ready_at TEXT, first_delivered_at TEXT,
        server_deleted_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
    )""",
    """CREATE TABLE tasks (
        id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        resource_id TEXT NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
        status TEXT NOT NULL DEFAULT 'queued', priority INTEGER NOT NULL DEFAULT 0,
        progress REAL NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0,
        error_code TEXT, error_message TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
    )""",
    """CREATE TABLE deliveries (
        resource_id TEXT NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
        device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
        status TEXT NOT NULL DEFAULT 'pending', downloaded_bytes INTEGER NOT NULL DEFAULT 0,
        confirmed_at TEXT, deleted_at TEXT, updated_at TEXT NOT NULL,
        PRIMARY KEY (resource_id, device_id)
    )""",
    """CREATE TABLE settings (
        scope TEXT NOT NULL, owner_id TEXT NOT NULL DEFAULT '', key TEXT NOT NULL,
        value_json TEXT NOT NULL, updated_at TEXT NOT NULL,
        PRIMARY KEY (scope, owner_id, key)
    )""",
    """CREATE TABLE audit (
        id INTEGER PRIMARY KEY AUTOINCREMENT, actor_user_id TEXT,
        action TEXT NOT NULL, target_id TEXT, detail_json TEXT NOT NULL DEFAULT '{}',
        created_at TEXT NOT NULL
    )""",
    """CREATE TABLE commands (
        id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        device_id TEXT REFERENCES devices(id) ON DELETE CASCADE,
        kind TEXT NOT NULL, payload_json TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending',
        result_json TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
    )""",
    """CREATE TABLE events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        device_id TEXT REFERENCES devices(id) ON DELETE CASCADE,
        kind TEXT NOT NULL, payload_json TEXT NOT NULL, created_at TEXT NOT NULL
    )""",
    "CREATE INDEX tasks_user_status ON tasks(user_id, status)",
    "CREATE INDEX resources_user ON resources(user_id, created_at)",
    "CREATE INDEX events_user_cursor ON events(user_id, id)",
]


MIGRATION_2 = [
    """CREATE TABLE verification_tokens (
        token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        expires_at TEXT NOT NULL, used_at TEXT, created_at TEXT NOT NULL
    )""",
    "CREATE INDEX verification_user_created ON verification_tokens(user_id, created_at)",
]


MIGRATION_3 = [
    """CREATE TABLE password_reset_tokens (
        token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        expires_at TEXT NOT NULL, used_at TEXT, created_at TEXT NOT NULL
    )""",
    """CREATE TABLE auth_limits (
        scope TEXT NOT NULL, key_hash TEXT NOT NULL, window_start TEXT NOT NULL,
        attempts INTEGER NOT NULL, PRIMARY KEY(scope,key_hash)
    )""",
]


class Database:
    def __init__(self, path: Path):
        self.path = path

    def connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=15)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA busy_timeout = 15000")
        return connection

    def initialize(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.transaction() as connection:
            version = connection.execute("PRAGMA user_version").fetchone()[0]
            if version > SCHEMA_VERSION:
                raise RuntimeError("Database schema is newer than this server")
            if version == 0:
                for statement in SCHEMA:
                    connection.execute(statement)
                version = 1
            if version == 1:
                for statement in MIGRATION_2:
                    connection.execute(statement)
                version = 2
            if version == 2:
                for statement in MIGRATION_3:
                    connection.execute(statement)
            connection.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")
        with closing(self.connect()) as connection:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.execute("PRAGMA synchronous = FULL")

    @contextmanager
    def transaction(self):
        connection = self.connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            yield connection
            connection.commit()
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()
