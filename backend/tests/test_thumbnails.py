import base64
from io import BytesIO
from test_private_library import service, owner, media
from tubego_server.thumbnails import preview, MAX_BYTES
from tubego_server.scheduler import put_setting

JPEG = b'\xff\xd8\xff' + b'fixture'


def factory(data, seen):
    class Engine:
        def __init__(self, options): pass
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def urlopen(self, url): seen.append(url); return BytesIO(data)
    return Engine


def test_preview_small_image_and_candidate_selection():
    seen = []
    encoded = preview({'thumbnails': [{'url': 'https://video.example/large', 'width': 1920},
                                    {'url': 'https://video.example/small', 'width': 320}]}, factory(JPEG, seen))
    assert base64.b64decode(encoded) == JPEG
    assert seen == ['https://video.example/small']


def test_optional_missing_invalid_and_oversize_previews():
    assert preview({'thumbnails': None}) is None
    assert preview({'thumbnail': 'https://video.example/t'}, factory(b'not an image', [])) is None
    assert preview({'thumbnail': 'https://video.example/t'}, factory(JPEG + b'x'*MAX_BYTES, [])) is None
    def failed(options): raise OSError('offline')
    assert preview({'thumbnail': 'https://video.example/t'}, failed) is None


def test_thumbnail_is_private_and_excluded_from_library_payload(service):
    app, client = service
    alice, bob = owner(app), owner(app, role='admin')
    rid, _ = media(app, alice)
    with app.state.db.transaction() as conn:
        put_setting(conn,'resource',rid,'thumbnail_base64',base64.b64encode(JPEG).decode())
    path=f'/api/v1/resources/{rid}/thumbnail'
    assert client.get(path).status_code == 401
    assert client.get(path,headers=bob['headers']).status_code == 404
    response=client.get(path,headers=alice['headers'])
    assert base64.b64decode(response.json()['thumbnail_base64']) == JPEG
    assert response.headers['cache-control'] == 'no-store'
    library=client.get('/api/v1/resources',headers=alice['headers']).json()['items'][0]
    assert library['has_thumbnail'] is True
    assert 'thumbnail_base64' not in library
    with app.state.db.transaction() as conn:
        conn.execute("UPDATE sessions SET revoked_at='now' WHERE id=?",(alice['session'],))
    assert client.get(path,headers=alice['headers']).status_code == 401


def test_download_header_has_normalized_unicode_filename(service):
    from test_device_delivery import ready
    from urllib.parse import unquote
    app, client = service
    alice = owner(app)
    rid, _ = ready(app, alice)
    with app.state.db.transaction() as conn:
        conn.execute('UPDATE resources SET title=? WHERE id=?', ('../Mi vídeo: "resumen" / 2026?\r\n', rid))
    response = client.head(f'/api/v1/resources/{rid}/download', headers=alice['headers'])
    assert response.status_code == 200
    header = response.headers['content-disposition']
    assert unquote(header.split("filename*=UTF-8''")[1]) == 'Mi vídeo resumen 2026.mp4'


def test_filename_boundaries_and_audio():
    from tubego_server.filenames import download_name
    assert download_name('CON', 'audio') == 'Video CON.mp3'
    assert download_name('.../\\', 'video') == 'Video.mp4'
    assert download_name('ＡＢＣ', 'video') == 'ABC.mp4'
    assert len(download_name('🎥'*200, 'video').encode()) <= 164


def test_thumbnail_cannot_request_private_network(monkeypatch):
    import sys
    import types
    seen=[]
    class Engine:
        def __init__(self, options): pass
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def urlopen(self, request): seen.append(request); return BytesIO(JPEG)
    monkeypatch.setenv('TUBEGO_MEDIA_EGRESS_PROXY','http://proxy:3128')
    monkeypatch.setitem(sys.modules,'yt_dlp',types.SimpleNamespace(YoutubeDL=Engine))
    assert preview({'thumbnail':'http://169.254.169.254/latest/meta-data'}) is None
    assert seen == []
