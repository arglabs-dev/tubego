import socket
import sys
import types
import pytest
from fastapi.testclient import TestClient
from tubego_server.config import Settings
from tubego_server.main import create_app
from tubego_server.media import MediaError, analyze_media, normalize_url
from tubego_server.routers.media import approved_user


def public_dns(host, port, **kwargs):
    return [(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('8.8.8.8', port))]


def factory(info=None, error=None):
    class Fake:
        def __init__(self, options):
            assert options['skip_download'] and options['noplaylist']
            assert options['cachedir'] is False and options['usenetrc'] is False
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def extract_info(self, url, download):
            assert download is False
            if error: raise error
            return info
    return Fake


def test_normalization():
    assert normalize_url(' HTTPS://VIDEO.Example/watch?v=x#chapter ', public_dns) == 'https://video.example/watch?v=x'


@pytest.mark.parametrize('url', [
    'file:///etc/passwd', 'ftp://video.example/v', 'https://u:p@video.example/v',
    'https://localhost/v', 'https://x.local/v', 'http://127.0.0.1/',
    'http://[::1]/', 'http://169.254.169.254/', 'https://10.0.0.1/',
    'https://[fc00::1]/', 'https://video.example:8080/', 'https://video.example/\nx',
    'https://127.1/', 'https://[::ffff:127.0.0.1]/', 'https://x.internal/'
])
def test_private_and_invalid_urls(url):
    resolver = socket.getaddrinfo if '127.1' in url else public_dns
    with pytest.raises(MediaError) as error:
        normalize_url(url, resolver)
    assert error.value.code == 'invalid_url'


def test_dns_with_any_private_address_rejected():
    def mixed(host, port, **kwargs):
        return public_dns(host, port) + [(socket.AF_INET, 1, 6, '', ('192.168.1.4', port))]
    with pytest.raises(MediaError): normalize_url('https://video.example', mixed)


def test_dns_unavailable_is_retryable():
    def failed(*args, **kwargs): raise socket.gaierror('sensitive internal hostname')
    with pytest.raises(MediaError) as error: normalize_url('https://video.example', failed)
    assert error.value.code == 'temporary_failure'
    assert 'hostname' not in str(error.value)


def test_metadata_and_optional_fields():
    info = analyze_media('https://video.example/v', factory=factory({'id': '123',
        'title': 'Lecture', 'duration': 125.5, 'extractor_key': 'Example'}), resolver=public_dns)
    assert info['title'] == 'Lecture' and info['duration_seconds'] == 125.5
    assert info['source_id'] == '123' and info['extractor'] == 'Example'
    missing = analyze_media('https://video.example/v', factory=factory({'duration': float('nan')}), resolver=public_dns)
    assert missing['title'] is None and missing['duration_seconds'] is None


@pytest.mark.parametrize('info', [{'_type': 'playlist', 'entries': []}, {'_type': 'multi_video'}, {'entries': []}])
def test_collections_rejected(info):
    with pytest.raises(MediaError) as error:
        analyze_media('https://video.example/v', factory=factory(info), resolver=public_dns)
    assert error.value.code == 'unsupported'


def test_private_redirect_identity_rejected():
    with pytest.raises(MediaError) as error:
        analyze_media('https://video.example/v', factory=factory({'webpage_url': 'http://127.0.0.1/'}), resolver=public_dns)
    assert error.value.code == 'invalid_url'


@pytest.mark.parametrize('message,code', [
    ('Unsupported URL secret?token=abc', 'unsupported'),
    ('Private video; use cookies /home/user/cookie', 'authentication_required'),
    ('Video deleted', 'unavailable'), ('Not available in your country', 'source_restricted'),
    ('timeout token=secret /server/path', 'temporary_failure')])
def test_safe_error_codes(message, code):
    with pytest.raises(MediaError) as error:
        analyze_media('https://video.example/v', factory=factory(error=RuntimeError(message)), resolver=public_dns)
    assert error.value.code == code
    assert 'secret' not in str(error.value) and '/home' not in str(error.value)


def test_real_engine_requires_proxy(monkeypatch):
    monkeypatch.delenv('TUBEGO_MEDIA_EGRESS_PROXY', raising=False)
    with pytest.raises(MediaError) as error: analyze_media('https://video.example', resolver=public_dns)
    assert error.value.code == 'temporary_failure'


def test_every_extractor_request_guarded(monkeypatch):
    monkeypatch.setenv('TUBEGO_MEDIA_EGRESS_PROXY', 'http://proxy:3128')
    class DummyYDL:
        def __init__(self, options): assert options['proxy'] == 'http://proxy:3128'
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def extract_info(self, *args, **kwargs):
            self.urlopen(types.SimpleNamespace(url='http://169.254.169.254/latest/meta-data'))
        def urlopen(self, request): pytest.fail('Guard must prevent sending request')
    monkeypatch.setitem(sys.modules, 'yt_dlp', types.SimpleNamespace(YoutubeDL=DummyYDL))
    with pytest.raises(MediaError) as error: analyze_media('https://video.example', resolver=public_dns)
    assert error.value.code == 'invalid_url'


def test_endpoint_contract_and_fail_closed(tmp_path, monkeypatch):
    app = create_app(Settings(tmp_path))
    with TestClient(app) as client:
        assert client.post('/api/v1/media/analyze', json={'url':'https://video.example'}).status_code in (401,503)
        from test_private_library import owner
        user=owner(app)
        client.headers.update(user['headers'])
        original_normalize=normalize_url
        monkeypatch.setattr('tubego_server.media.normalize_url',lambda url:original_normalize(url,public_dns))
        monkeypatch.setattr('tubego_server.routers.media.analyze_media', lambda url: {'title':None, 'duration_seconds':None, 'url':url})
        assert client.post('/api/v1/media/analyze', json={'url':'https://video.example'}).json()['title'] is None
        def fail(url): raise MediaError('unsupported')
        monkeypatch.setattr('tubego_server.routers.media.analyze_media', fail)
        response = client.post('/api/v1/media/analyze', json={'url':'https://video.example'})
        assert response.status_code == 422 and response.json()['detail']['code'] == 'unsupported'
