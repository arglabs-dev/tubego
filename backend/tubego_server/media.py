"""Single-resource metadata. Deployment must isolate ALL extractor egress."""
import ipaddress
import math
import os
import socket
from urllib.parse import urlsplit, urlunsplit


MESSAGES = {
    "invalid_url": "Use one public HTTP or HTTPS resource URL.",
    "unsupported": "This source or collection is not supported.",
    "unavailable": "The resource is no longer available.",
    "authentication_required": "The source requires authentication; portal credentials are not supported.",
    "source_restricted": "The source restricts access to this resource.",
    "temporary_failure": "The resource could not be analyzed. Try again later.",
}


class MediaError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(MESSAGES[code])


def normalize_url(value, resolver=socket.getaddrinfo):
    """Validate input DNS/IP; NOT a network egress or DNS-rebinding guarantee."""
    if not isinstance(value, str):
        raise MediaError("invalid_url")
    value = value.strip()
    if not value or len(value) > 4096 or any(c.isspace() or ord(c) < 32 for c in value):
        raise MediaError("invalid_url")
    try:
        parts = urlsplit(value)
        host = parts.hostname
        port = parts.port
        if (parts.scheme.lower() not in ("http", "https") or not host
                or parts.username is not None or parts.password is not None
                or "\\" in value or "%" in host or port not in (None, 80, 443)):
            raise ValueError()
        host = host.rstrip(".").encode("idna").decode("ascii").lower()
        if host == "localhost" or host.endswith((".localhost", ".local", ".internal")):
            raise ValueError()
        try:
            addresses = [ipaddress.ip_address(host)]
        except ValueError:
            addresses = [ipaddress.ip_address(record[4][0]) for record in
                         resolver(host, port or (443 if parts.scheme.lower() == "https" else 80),
                                  type=socket.SOCK_STREAM)]
        if not addresses or any(not address.is_global for address in addresses):
            raise ValueError()
        netloc = f"[{host}]" if ":" in host else host
        if port is not None:
            netloc += f":{port}"
        return urlunsplit((parts.scheme.lower(), netloc, parts.path or "/", parts.query, ""))
    except (ValueError, UnicodeError):
        raise MediaError("invalid_url") from None
    except OSError:
        raise MediaError("temporary_failure") from None


class SilentLogger:
    # Extractor messages may contain signed URLs, local paths, or credentials.
    def debug(self, *args): pass
    def warning(self, *args): pass
    def error(self, *args): pass


def classify_error(error):
    # Return stable codes, never the raw diagnostic. Unknown errors remain retryable.
    message = str(error).lower()
    if any(s in message for s in ("unsupported url", "no suitable extractor")):
        return "unsupported"
    if any(s in message for s in ("sign in", "login required", "private video", "authentication", "cookies")):
        return "authentication_required"
    if any(s in message for s in ("geo", "country", "copyright", "age-restricted", "http error 403")):
        return "source_restricted"
    if any(s in message for s in ("removed", "deleted", "not available", "unavailable", "http error 404", "http error 410")):
        return "unavailable"
    return "temporary_failure"


def analyze_media(url, *, factory=None, resolver=socket.getaddrinfo):
    normalized = normalize_url(url, resolver)
    options = {"skip_download": True, "noplaylist": True, "extract_flat": False,
               "quiet": True, "no_warnings": True, "logger": SilentLogger(),
               "socket_timeout": 15, "retries": 0, "extractor_retries": 0,
               "cachedir": False, "usenetrc": False}
    if factory is None:
        # A rejecting proxy is necessary for redirects and DNS rebinding protection.
        proxy = os.environ.get("TUBEGO_MEDIA_EGRESS_PROXY", "")
        try:
            proxy_parts = urlsplit(proxy)
            if (proxy_parts.scheme != "http" or not proxy_parts.hostname
                    or proxy_parts.username or proxy_parts.password
                    or proxy_parts.path not in ("", "/") or proxy_parts.query
                    or proxy_parts.fragment or proxy_parts.port is None):
                raise ValueError()
        except ValueError:
            raise MediaError("temporary_failure") from None
        import yt_dlp

        class GuardedYoutubeDL(yt_dlp.YoutubeDL):
            def urlopen(self, request):
                # Guard every extractor request; internal redirects MUST be filtered
                # at the configured proxy as well. DNS resolution alone is not enough.
                target = request if isinstance(request, str) else getattr(request, "url", None)
                if target is None and hasattr(request, "get_full_url"):
                    target = request.get_full_url()
                normalize_url(target, resolver)
                if hasattr(request, "proxies"):
                    request.proxies = {"all": proxy, "http": proxy, "https": proxy}
                return super().urlopen(request)

        options["proxy"] = proxy
        factory = GuardedYoutubeDL
    try:
        with factory(options) as engine:
            info = engine.extract_info(normalized, download=False)
        if not isinstance(info, dict):
            raise MediaError("unavailable")
        if info.get("_type") in ("playlist", "multi_video") or "entries" in info:
            raise MediaError("unsupported")
        # Final webpage identity may be a redirect. Validate it too before exposing it.
        canonical = normalize_url(info.get("webpage_url") or normalized, resolver)
        title = info.get("title")
        duration = info.get("duration")
        return {
            "url": canonical,
            "source_id": str(info["id"])[:256] if info.get("id") is not None else None,
            "extractor": str(info.get("extractor_key") or info.get("extractor") or "")[:128] or None,
            "title": title[:512] if isinstance(title, str) and title.strip() else None,
            "duration_seconds": duration if isinstance(duration, (int, float))
                and not isinstance(duration, bool) and math.isfinite(duration) and duration >= 0 else None,
        }
    except MediaError:
        raise
    except Exception as error:
        raise MediaError(classify_error(error)) from None
