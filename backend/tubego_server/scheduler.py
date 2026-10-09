"""Durable weighted scheduler. A worker must claim before performing network I/O.

One server download runs at a time. Each active user's cycle has one slot, or
three for priority users. Within a user's queue, higher task priority wins,
then FIFO. Read-only previews do not spend turns. Recovery may only be called
by the exclusive worker supervisor after the previous worker has stopped.
"""
from contextlib import closing
from datetime import datetime, timezone
import json
import uuid


def now():
    return datetime.now(timezone.utc).isoformat()


def put_setting(conn, scope, owner, key, value):
    conn.execute("""INSERT INTO settings(scope,owner_id,key,value_json,updated_at)
        VALUES(?,?,?,?,?) ON CONFLICT(scope,owner_id,key) DO UPDATE SET
        value_json=excluded.value_json,updated_at=excluded.updated_at""",
        (scope, owner, key, json.dumps(value), now()))


def setting(conn, key):
    row = conn.execute("SELECT value_json FROM settings WHERE scope='global' AND owner_id='' AND key=?", (key,)).fetchone()
    return json.loads(row[0]) if row else None


class Scheduler:
    def __init__(self, database):
        self.database = database

    def _next(self, conn):
        if setting(conn, 'maintenance_gate'):return None
        if conn.execute("SELECT 1 FROM tasks WHERE status IN ('running','paused') LIMIT 1").fetchone():
            return None
        ready_time=json.dumps(now())
        users = conn.execute("""SELECT DISTINCT u.id, s.value_json FROM users u
            JOIN tasks t ON t.user_id=u.id LEFT JOIN settings s ON
            s.scope='user' AND s.owner_id=u.id AND s.key='priority_level'
            WHERE t.status='queued' AND NOT EXISTS(SELECT 1 FROM settings r WHERE r.scope='task' AND r.owner_id=t.id AND r.key='next_retry_at' AND r.value_json>?) AND u.status='approved' AND u.email_verified_at IS NOT NULL
            ORDER BY u.id""",(ready_time,)).fetchall()
        slots = sorted((stage, user['id']) for user in users
            for stage in range(3 if user['value_json'] == json.dumps('prioritario') else 1))
        if not slots:
            return None
        cursor = setting(conn, 'scheduler_cursor')
        slot = next((slot for slot in slots if cursor is None or slot > tuple(cursor)), slots[0])
        task = conn.execute("""SELECT * FROM tasks WHERE user_id=? AND status='queued' AND NOT EXISTS(SELECT 1 FROM settings r WHERE r.scope='task' AND r.owner_id=tasks.id AND r.key='next_retry_at' AND r.value_json>?)
            ORDER BY priority DESC,created_at,id LIMIT 1""", (slot[1],ready_time)).fetchone()
        return dict(task), slot

    def preview(self):
        with closing(self.database.connect()) as conn:
            # Keep running-task check, active queues and cursor in one snapshot.
            conn.execute('BEGIN')
            selection = self._next(conn)
            return selection[0] if selection else None

    def claim(self):
        with self.database.transaction() as conn:
            selection = self._next(conn)
            if selection is None:
                return None
            task, slot = selection
            token = uuid.uuid4().hex
            conn.execute("UPDATE tasks SET status='running',updated_at=? WHERE id=?", (now(), task['id']))
            put_setting(conn, 'global', '', 'scheduler_cursor', slot)
            put_setting(conn, 'global', '', 'scheduler_lease', {'task_id': task['id'], 'token': token})
            task.update(status='running', claim_token=token)
            return task

    def finish(self, task_id, claim_token, status='completed'):
        if status not in ('completed', 'failed', 'cancelled', 'queued'):
            raise ValueError('Invalid task completion status')
        with self.database.transaction() as conn:
            lease = setting(conn, 'scheduler_lease')
            if lease != {'task_id': task_id, 'token': claim_token}:
                return False
            result = conn.execute("UPDATE tasks SET status=?,updated_at=? WHERE id=? AND status='running'", (status, now(), task_id))
            conn.execute("DELETE FROM settings WHERE scope='global' AND owner_id='' AND key='scheduler_lease'")
            return result.rowcount == 1

    def recover_after_worker_stopped(self):
        """Requeue interrupted work; caller must have stopped the prior worker.

        This is deliberately not part of API startup or scheduler construction:
        starting a second API process must never steal a live worker's job.
        Tokens fence stale completion calls after recovery.
        """
        with self.database.transaction() as conn:
            result = conn.execute("UPDATE tasks SET status='queued',updated_at=? WHERE status IN ('running','paused')", (now(),))
            conn.execute("DELETE FROM settings WHERE scope='global' AND owner_id='' AND key='scheduler_lease'")
            return result.rowcount
