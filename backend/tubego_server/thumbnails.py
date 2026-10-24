"""Small previews fetched through the same restricted egress as video sources."""
import base64
from tubego_server.media import restricted_ytdlp

MAX_BYTES = 32768


def content_type(data):
    if data.startswith(b'\xff\xd8\xff'): return 'image/jpeg'
    if data.startswith(b'\x89PNG\r\n\x1a\n'): return 'image/png'
    if data.startswith(b'RIFF') and data[8:12] == b'WEBP': return 'image/webp'
    return None


def preview(info, factory=None):
    items = info.get('thumbnails')
    candidates = [t for t in (items if isinstance(items, list) else []) if isinstance(t, dict) and isinstance(t.get('url'), str)]
    candidates.sort(key=lambda t: abs((t['width'] if isinstance(t.get('width'), (int, float)) else 320) - 320))
    urls = [t['url'] for t in candidates]
    if isinstance(info.get('thumbnail'), str): urls.append(info['thumbnail'])
    # A missing/oversized/unsupported preview must never fail the video download.
    for url in list(dict.fromkeys(urls))[:2]:
        try:
            with (factory or restricted_ytdlp)({'socket_timeout': 3, 'retries': 0}) as engine:
                with engine.urlopen(url) as response:
                    data = response.read(MAX_BYTES + 1)
            if 0 < len(data) <= MAX_BYTES and content_type(data):
                return base64.b64encode(data).decode('ascii')
        except Exception:
            continue
    return None
