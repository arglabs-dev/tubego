# PLA-234 — Single-resource analysis

Approved users call `POST /api/v1/media/analyze` with `{"url":"https://…"}`.
The response contains `url`, `source_id`, `extractor`, `title`, and `duration_seconds`.
Missing title/duration are null and do not reject the resource. Android's
`MediaInfoClient` can use `new MediaInfoClient(apiClient::request)` once the
account transport is integrated. URL-entry UI belongs to PLA-233. Quality choices
remain local/offline and are validated by the later download job, not this endpoint.

The existing bot continues independently. The mobile engine embeds yt-dlp using
[`extract_info(download=False)`](https://github.com/yt-dlp/yt-dlp#embedding-yt-dlp).
It does not download media, write cache, use netrc, or supply cookies/credentials.
Only single resources are accepted; playlist/multi-video results are rejected.
Supported sources follow the installed yt-dlp version, without availability guarantees.
Errors have stable codes and fixed messages; raw diagnostics, paths, cookies, and
signed download URLs are not returned or logged. Source failures are classified
best-effort; unfamiliar extractor diagnostics become `temporary_failure`.

## Restricted egress proxy

`backend/tubego_server/egress_proxy.py` provides an HTTP forwarding and CONNECT
proxy. It accepts destination ports 80/443, rejects localhost/local/internal names
and all non-global, multicast, unspecified and mapped IPv6 DNS answers. Every
connection resolves the destination, checks **all** returned addresses, then
connects a validated numeric sockaddr without another lookup. HTTP redirects
require a fresh proxy request and validation; HTTPS redirects open a fresh CONNECT.
The original Host header is rebuilt for forwarded HTTP. Request headers are
limited to 32 KiB, request bodies to 1 MiB, and connections to a 30-second inactivity
timeout. It does not log destinations, headers, queries or exception diagnostics.
GET, HEAD and POST are supported, plus CONNECT. Unsupported protocols, ports,
chunked request bodies and malformed/duplicate headers are rejected.

The engine fails closed (503 `temporary_failure`) unless
`TUBEGO_MEDIA_EGRESS_PROXY=http://<restricted-proxy-host>:<port>` is configured.
`YoutubeDL.urlopen` validates every extractor request and forces the configured
proxy even when the request has its own proxy map. The reusable
`restricted_ytdlp(options)` engine limits networking to RequestsRH, whose session
disables environment proxy overrides. This prevents urllib NO_PROXY redirect
bypasses. yt-dlp and requests are pinned to tested versions; rerun these tests on
upgrades. No boolean “isolated” flag is
used. `compose.mobile.yaml` now starts the provided proxy as `egress`, passes its
address to the API, and publishes **no proxy port on the host**:

```sh
docker compose -f compose.mobile.yaml up --build -d
```

For a non-container local setup, start the proxy (loopback binding by default):

```sh
PYTHONPATH=backend python -m tubego_server.egress_proxy
TUBEGO_MEDIA_EGRESS_PROXY=http://127.0.0.1:8081 PYTHONPATH=backend uvicorn tubego_server.main:app
```

Do not publish this unauthenticated proxy or replace it with an unrestricted proxy.
The Compose network is not a global egress sandbox: the API may need direct SMTP
or other service traffic. Protection here applies to the mobile yt-dlp engine's
HTTP requests, via the forced proxy and validated public destinations. It does not
make arbitrary plugins/subprocesses safe. The later full download worker must reuse
`restricted_ytdlp`. Its download hook permits only native HTTP/HLS/DASH downloaders
and rejects ffmpeg, RTMP and external network downloaders before they run. FFmpeg
postprocessing must be limited to already downloaded local files. Do not load untrusted extractor plugins. Network-level restrictions
remain useful additional defense for a dedicated worker.

Initial URL and final webpage validation are defense in depth; they do not alone
prevent requests to private redirect targets. The supplied proxy performs that
validation at connection time. The client/proxy integration tests use the **real
installed yt-dlp** against a fake public source, with test-only socket mapping to a
local fixture. They verify successful metadata extraction, a working CONNECT tunnel,
public-to-private redirect denial, validated-IP pinning and parser limits. No
production override permits private destinations. Real portal availability still
requires portal-specific testing; no library guarantees all sites or bypasses access
restrictions.
