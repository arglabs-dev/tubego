import pytest
from test_private_library import service, owner, media
from tubego_server.categories import from_metadata
from tubego_server.scheduler import put_setting


@pytest.mark.parametrize('metadata,expected', [
    ({'categories': ['Music', 'Entertainment']}, 'Music'),
    ({'categories': [None, '', '  Science   & Technology  ']}, 'Science & Technology'),
    ({'categories': ['ＡＢＣ\x00']}, 'ABC'),
    ({'categories': ['Science\n&\tTechnology']}, 'Science & Technology'),
    ({'categories': ['x' * 200]}, 'x' * 80),
    ({}, ''), ({'categories': None}, ''), ({'categories': 'Music'}, ''),
    ({'tags': ['Music'], 'title': 'Music lesson'}, ''),
])
def test_source_category_normalization_and_no_guessing(metadata, expected):
    assert from_metadata(metadata) == expected


def test_private_category_catalog_filter_pagination_and_legacy(service):
    app, client = service
    alice, bob = owner(app), owner(app, role='admin')
    legacy, _ = media(app, alice, 'Legacy')
    music = [media(app, alice, 'Music '+str(n))[0] for n in range(3)]
    foreign, _ = media(app, bob, 'Secret')
    with app.state.db.transaction() as conn:
        for rid in music:
            put_setting(conn, 'resource', rid, 'category', 'Music')
        put_setting(conn, 'resource', foreign, 'category', 'Secret category')
    path = '/api/v1/resources'
    headers = alice['headers']
    first = client.get(path, headers=headers, params={'category': 'Music', 'limit': 2}).json()
    second = client.get(path, headers=headers, params={
        'category': 'Music', 'limit': 2, 'cursor': first['next_cursor']}).json()
    assert {r['id'] for r in first['items'] + second['items']} == set(music)
    assert second['next_cursor'] is None
    assert first['categories'] == ['', 'Music']
    assert len(client.get(path, headers=headers).json()['items']) == 4
    assert [r['id'] for r in client.get(path, headers=headers, params={'category': ''}).json()['items']] == [legacy]
    assert client.get(path, headers=headers, params={'category': "Music' OR 1=1--"}).json()['items'] == []
    assert client.get(path, headers=headers, params={'category': 'Music', 'search': 'Music 1'}).json()['items'][0]['title'] == 'Music 1'
    assert client.get(path, headers=headers, params={'category': 'x' * 81}).status_code == 422
    assert client.get(path).status_code == 401
    assert client.get(path+'/'+foreign, headers=headers).status_code == 404
    assert client.get(path+'/'+music[0], headers=headers).json()['category'] == 'Music'
    with app.state.db.transaction() as conn:
        conn.execute("UPDATE resources SET server_deleted_at='now',server_path=NULL WHERE id=?", (music[0],))
    assert client.get(path+'/'+music[0], headers=headers).json()['category'] == 'Music'


def test_worker_persists_category_before_publishing(service):
    from pathlib import Path
    from test_worker import seed
    from test_egress_proxy import answers
    from tubego_server.tasks import submit
    from tubego_server.worker import Worker
    app, client = service
    principal, headers, _ = seed(app)
    result = submit(app.state.db, principal, 'https://video.example/v', '720', resolver=answers)
    class Engine:
        def __init__(self, options): self.options = options
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def urlopen(self, request): return None
        def extract_info(self, *args, **kwargs):
            return {'title': 'A lecture', 'height': 720, 'categories': ['Education']}
        def process_ie_result(self, info, download):
            path = Path(self.options['outtmpl'].replace('%(ext)s', 'mp4'))
            path.write_bytes(b'final')
            return {'filepath': str(path)}
    with Worker(app.state.settings, Engine) as worker:
        assert worker.run_once()
    resource = client.get('/api/v1/resources/'+result['resource_id'], headers=headers).json()
    assert resource['category'] == 'Education'
    assert resource['server_available'] is True
