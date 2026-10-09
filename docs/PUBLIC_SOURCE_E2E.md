# Real public-source pipeline verification

This opt-in integration runner uses the real restricted egress proxy, yt-dlp, SQLite scheduler, exclusive worker, FFmpeg audio extraction and authenticated API delivery. It neither mocks network/DNS/processing nor relaxes SSRF/TLS protections. It does not use ADB or production/bot storage. Two approved users/devices are seeded in a private temporary database, with hashed bearer sessions; no passwords or tokens appear in the report.

Run from a checkout with backend dependencies installed and `ffmpeg`/`ffprobe` on PATH:

```sh
PYTHONPATH=backend:. timeout 180 /path/to/python-env/bin/python -u scripts/verify_public_source_e2e.py
```

The default fixture is [W3C's public Sintel trailer](https://media.w3.org/2010/05/sintel/trailer.mp4). The operator may explicitly select its [official Blender mirror](https://download.blender.org/durian/trailer/sintel_trailer-480p.mp4):

```sh
PYTHONPATH=backend:. timeout 180 /path/to/python-env/bin/python -u scripts/verify_public_source_e2e.py --source blender
```

Both fixed URLs were independently checked for HTTP 200, `video/mp4`, 4,372,373 bytes and HTTP Range support. The Blender mirror is an optional separately chosen fixture, not an automatic workaround if the original source restricts access. No arbitrary URL, cookies, portal credentials, User-Agent changes, insecure TLS flags or DNS resolver substitutions are accepted.

Each invocation creates `/tmp/tubego-source-e2e/run-<uuid>/` with mode 0700. It validates public DNS, starts a real loopback restricted proxy, verifies that the proxy rejects a private destination with 403, verifies the public HEAD response through that proxy, and checks the API rejects a private resource URL with 422. It then fetches real metadata and submits two resources from the same URL: best video and audio. The worker downloads both, FFmpeg converts the audio to MP3, the runner probes streams/duration and recomputes the published digest. Authenticated HEAD and two consecutive byte ranges must match the actual files; anonymous access must return 401 and another user's bearer token 404. The client's size/digest confirmation must produce a complete delivery in device sync.

A per-stage JSON report and the test media remain in the private run directory for review. The runner exits nonzero on any failure, including source restrictions. It does not silently retry, fabricate metadata, swap sources, or erase an earlier failure. A timeout may leave a private incomplete test directory; it never deletes user/server/bot files. Operator test environments must permit DNS, outbound public HTTPS, loopback proxy sockets and TestClient's ASGI runtime. A restricted child execution failed closed at DNS resolution; the unrestricted root execution performed the actual end-to-end validation.

## Evidence from 2026-10-09

The successful unrestricted run on revision `51946ec` produced:

| Selection | Final bytes | Streams | Probe duration | Authenticated Range |
| --- | ---: | --- | ---: | --- |
| Best video | 4,372,373 | H.264 + AAC | 52.208333 s | 206 |
| Audio | 1,247,954 | MP3 | 51.96 s | 206 |

Both SHA-256 digests matched the worker's published hashes, both confirmations reached `complete`, anonymous requests returned 401, and foreign-user requests returned 404. Exact digests and scope details are in [the sanitized evidence JSON](evidence/public-source-e2e-2026-10-09.json).

An earlier unrestricted attempt verified the same source's HEAD response but metadata returned the API's `source_restricted` error (422). A later independent invocation succeeded without changing restrictions. This demonstrates real network/source intermittency; it does not establish that every supported platform or future request will succeed.

The real yt-dlp extractor here was `Generic` for a direct public MP4. It returned a title but no duration; only downloaded files' durations were established by ffprobe. A separate invocation against the Blender mirror also completed both real transfers, conversion, digest checks, ranges and confirmations (private run `run-a52948f1-076f-4c2c-819e-a709a22cb2d8`). A later W3C attempt again returned `source_restricted`; the runner keeps that failure rather than switching silently.

This run verifies the server pipeline and authorization, not YouTube/Vimeo extraction, SMTP registration, background Android transfer, a production reverse proxy or Android playback. Those have separate verification paths. CI does not automatically fetch this external fixture; run this explicit integration check when validating external downloading or egress changes.
