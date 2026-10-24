from contextlib import closing

from test_login import service, user, login, headers
from test_private_library import owner, media
from tubego_server.auth import token_hash
from tubego_server.delivery import publish_ready


def web_login(client, **overrides):
    return client.post('/api/v1/web/login', json={
        'email': 'hello@example.com', 'password': 'correct horse battery', **overrides})


def test_web_page_security(service):
    _, client = service
    response = client.get('/send')
    assert response.status_code == 200
    assert response.headers['cache-control'] == 'no-store'
    assert "frame-ancestors 'none'" in response.headers['content-security-policy']
    assert 'CSP_NONCE' not in response.text
    assert 'localStorage' not in response.text and 'sessionStorage' not in response.text
    assert client.get('/send').headers['content-security-policy'] != response.headers['content-security-policy']


def test_browser_is_not_a_device_and_logout_preserves_phone(service):
    app, client = service
    user(app)
    phone = login(client)
    browser = web_login(client)
    assert browser.status_code == 200
    assert browser.headers['cache-control'] == 'no-store'
    with closing(app.state.db.connect()) as conn:
        session = conn.execute('SELECT * FROM sessions WHERE token_hash=?',
                               (token_hash(browser.json()['token']),)).fetchone()
        assert session['device_id'] is None
        assert conn.execute('SELECT count(*) FROM devices').fetchone()[0] == 1
    assert client.get('/api/v1/web/deliveries', headers=headers(browser)).json()['devices'][0]['id'] == phone.json()['device_id']
    assert client.post('/api/v1/web/logout', json={}, headers=headers(phone)).status_code == 403
    assert client.post('/api/v1/web/logout', json={}, headers=headers(browser)).status_code == 200
    assert client.get('/api/v1/tasks', headers=headers(browser)).status_code == 401
    assert client.get('/api/v1/account/status', headers=headers(phone)).status_code == 200


def test_browser_approval_and_shared_login_limits(service):
    app, client = service
    user(app, 'pending_approval')
    assert web_login(client).status_code == 403
    assert web_login(client, password='wrong').status_code == 401
    assert web_login(client, email='missing@example.com').status_code == 401
    for _ in range(8):
        assert web_login(client, password='wrong').status_code == 401
    assert login(client).status_code == 429


def test_browser_submission_and_delivery_to_phone(service, monkeypatch):
    app, client = service
    user(app)
    phone = login(client)
    browser = web_login(client)
    monkeypatch.setattr('tubego_server.tasks.normalize_url', lambda url: url)
    body = {'url': 'https://example.com/video.mp4', 'selection': '720',
            'request_id': '56929797-d7ce-4efb-a68a-456d29ef6819'}
    first = client.post('/api/v1/resources', json=body, headers=headers(browser))
    assert first.status_code == 200
    rid = first.json()['resource_id']
    assert client.get('/api/v1/web/deliveries', headers=headers(browser)).json()['tasks'][0]['source_url'] == body['url']
    assert client.post('/api/v1/resources', json=body, headers=headers(browser)).json()['resource_id'] == rid
    root = app.state.settings.data_dir / 'media'
    root.mkdir(exist_ok=True)
    (root / 'video.mp4').write_bytes(b'phone media')
    publication = publish_ready(app.state.db, root, rid, 'video.mp4')
    assert publication['recipients'] == [phone.json()['device_id']]
    synced = client.get('/api/v1/device/sync', headers=headers(phone)).json()
    assert synced['deliveries'][0]['id'] == rid
    assert client.get('/api/v1/web/deliveries', headers=headers(browser)).json()['items'][0]['status'] == 'pending'
    assert client.post(f'/api/v1/resources/{rid}/deliveries/confirm', headers=headers(phone),
                       json={'size_bytes': 11, 'sha256': publication['sha256']}).status_code == 200
    assert client.get('/api/v1/web/deliveries', headers=headers(browser)).json()['items'][0]['status'] == 'complete'


def test_web_delivery_status_is_private(service):
    app, client = service
    user(app)
    browser = web_login(client)
    foreign = owner(app)
    media(app, foreign, 'Foreign secret')
    data = client.get('/api/v1/web/deliveries', headers=headers(browser)).json()
    assert data == {'devices': [], 'items': [], 'tasks': []}
    assert client.get('/api/v1/web/deliveries').status_code == 401
    with app.state.db.transaction() as conn:
        conn.execute("UPDATE users SET status='blocked' WHERE id='user'")
    assert client.get('/api/v1/web/deliveries', headers=headers(browser)).status_code == 403
