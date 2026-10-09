from contextlib import closing
from collections import namedtuple
from test_private_library import service,owner
from tubego_server.routers import system


def test_storage_uses_real_guard_state_without_mutating_cleanup_or_alerts(service,monkeypatch):
    app,client=service;admin=owner(app,role='admin')
    import src.disk_guard
    Usage=namedtuple('Usage','total used free')
    monkeypatch.setattr(src.disk_guard.shutil,'disk_usage',lambda path:Usage(10*1024**3,9*1024**3,1024**3))
    response=client.get('/api/v1/admin/storage',headers=admin['headers'])
    assert response.status_code==200 and response.headers['cache-control']=='no-store'
    state=response.json();assert state['used_percent']==90 and state['downloads_paused'] is True and state['reason']=='threshold_90_percent'
    assert state['free_bytes']==1024**3 and state['pause_at_used_percent']==90 and 'checked_at' in state
    assert str(app.state.settings.data_dir) not in response.text
    monkeypatch.setattr(src.disk_guard.shutil,'disk_usage',lambda path:Usage(10*1024**3,1024**3,9*1024**3))
    assert client.get('/api/v1/admin/storage',headers=admin['headers']).json()['downloads_paused'] is False
    with closing(app.state.db.connect()) as conn:
        assert conn.execute('SELECT count(*) FROM events').fetchone()[0]==0
        assert conn.execute('SELECT count(*) FROM audit').fetchone()[0]==0
        assert conn.execute('SELECT count(*) FROM settings').fetchone()[0]==0


def test_storage_is_admin_only_rechecks_verified_role_and_session(service):
    app,client=service;normal=owner(app);admin=owner(app,role='admin');unverified=owner(app,role='admin',verified=False)
    assert client.get('/api/v1/admin/storage').status_code==401
    assert client.get('/api/v1/admin/storage',headers=normal['headers']).status_code==403
    assert client.get('/api/v1/admin/storage',headers=unverified['headers']).status_code==403
    assert client.get('/api/v1/admin/storage',headers=admin['headers']).status_code==200
    with app.state.db.transaction() as conn:conn.execute("UPDATE users SET role='user' WHERE id=?",(admin['id'],))
    assert client.get('/api/v1/admin/storage',headers=admin['headers']).status_code==403
    with app.state.db.transaction() as conn:conn.execute("UPDATE users SET role='admin' WHERE id=?",(admin['id'],));conn.execute("UPDATE sessions SET revoked_at='now' WHERE user_id=?",(admin['id'],))
    assert client.get('/api/v1/admin/storage',headers=admin['headers']).status_code==401


def test_failed_storage_measurement_is_explicit_unavailable_not_fake_zero_use(service,monkeypatch):
    app,client=service;admin=owner(app,role='admin')
    import src.disk_guard
    def failed(path):raise OSError('Unavailable volume')
    monkeypatch.setattr(src.disk_guard.shutil,'disk_usage',failed)
    response=client.get('/api/v1/admin/storage',headers=admin['headers'])
    assert response.status_code==200
    assert response.json()['downloads_paused'] is True
    assert response.json()['used_percent'] is None and response.json()['reason']=='storage_unavailable'
