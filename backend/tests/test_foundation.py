import sqlite3
import pytest
from fastapi.testclient import TestClient
from tubego_server.config import Settings
from tubego_server.db import Database, SCHEMA_VERSION
from tubego_server.main import create_app


def test_health_version_and_openapi(tmp_path):
    with TestClient(create_app(Settings(tmp_path))) as client:
        assert client.get("/api/v1/health").json() == {
            "status": "ok", "version": "0.1.0", "database": "ok"}
        assert client.get("/api/v1/version").json()["api_version"] == "v1"
        assert "/api/v1/health" in client.get("/api/v1/openapi.json").json()["paths"]
    assert (tmp_path / "tubego.sqlite3").exists()


def test_persistence_migration_and_rollback(tmp_path):
    db = Database(tmp_path / "tubego.sqlite3")
    db.initialize()
    with db.transaction() as conn:
        conn.execute("INSERT INTO settings VALUES ('global','','test','42','2026-01-01T00:00:00Z')")
    restarted = Database(db.path)
    restarted.initialize()
    with restarted.connect() as conn:
        assert conn.execute("PRAGMA user_version").fetchone()[0] == SCHEMA_VERSION
        assert conn.execute("SELECT value_json FROM settings").fetchone()[0] == "42"
    with pytest.raises(RuntimeError):
        with db.transaction() as conn:
            conn.execute("UPDATE settings SET value_json = '99'")
            raise RuntimeError("cancel transaction")
    with db.connect() as conn:
        assert conn.execute("SELECT value_json FROM settings").fetchone()[0] == "42"


def test_foreign_keys_enforced(tmp_path):
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    with pytest.raises(sqlite3.IntegrityError):
        with db.transaction() as conn:
            conn.execute("INSERT INTO devices(id,user_id,name,created_at) VALUES ('d','missing','phone','now')")
