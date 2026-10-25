"""Owner-scoped access primitives for every future private-data operation.

Use with the connection already held by a mutation's transaction; do not check
ownership in one transaction and mutate in another. Administrators have no
implicit bypass for private libraries. Unknown IDs and foreign IDs both give 404.
"""
from fastapi import HTTPException


class LibraryScope:
    def __init__(self, connection, principal):
        if principal['status'] != 'approved' or not principal['email_verified_at']:
            raise HTTPException(403, 'Email verification and approval required')
        self.connection = connection
        self.user_id = principal['id']

    def _one(self, sql, parameters):
        row = self.connection.execute(sql, parameters).fetchone()
        if row is None:
            raise HTTPException(404, 'Not found')
        return row

    def resource(self, resource_id):
        return self._one('SELECT * FROM resources WHERE id=? AND user_id=?',
                         (resource_id, self.user_id))

    def task(self, task_id):
        return self._one('''SELECT t.* FROM tasks t JOIN resources r ON r.id=t.resource_id
            WHERE t.id=? AND t.user_id=? AND r.user_id=?''',
            (task_id, self.user_id, self.user_id))

    def device(self, device_id):
        return self._one('SELECT * FROM devices WHERE id=? AND user_id=?',
                         (device_id, self.user_id))

    def session(self, session_id):
        return self._one('''SELECT s.* FROM sessions s LEFT JOIN devices d ON d.id=s.device_id
            WHERE s.id=? AND s.user_id=? AND (s.device_id IS NULL OR d.user_id=?)''',
            (session_id, self.user_id, self.user_id))

    def event(self, event_id):
        return self._one('''SELECT e.* FROM events e LEFT JOIN devices d ON d.id=e.device_id
            WHERE e.id=? AND e.user_id=? AND (e.device_id IS NULL OR d.user_id=?)''',
            (event_id, self.user_id, self.user_id))

    def command(self, command_id):
        return self._one('''SELECT c.* FROM commands c LEFT JOIN devices d ON d.id=c.device_id
            WHERE c.id=? AND c.user_id=? AND (c.device_id IS NULL OR d.user_id=?)''',
            (command_id, self.user_id, self.user_id))

    def delivery(self, resource_id, device_id):
        return self._one('''SELECT l.* FROM deliveries l
            JOIN resources r ON r.id=l.resource_id JOIN devices d ON d.id=l.device_id
            WHERE l.resource_id=? AND l.device_id=? AND r.user_id=? AND d.user_id=?''',
            (resource_id, device_id, self.user_id, self.user_id))
