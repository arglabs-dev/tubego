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

## Mandatory deployment prerequisite

The engine fails closed (503 `temporary_failure`) unless
`TUBEGO_MEDIA_EGRESS_PROXY=http://<restricted-proxy-host>:<port>` is configured.
A boolean “isolated” flag is deliberately insufficient. This change **does not
provide that proxy infrastructure**. Do not enable it with an ordinary open proxy.
Before enabling production analysis, deploy/test a proxy which accepts only HTTP
and HTTPS destinations on ports 80/443, resolves each target (including each HTTP
redirect and HTTPS CONNECT) and rejects any non-global IPv4/IPv6 address. It must
connect to the already validated public IP rather than resolve it again. Restrict
the worker's network so requests cannot bypass that proxy, including loopback,
private LAN, cloud metadata, IPv6 local routes and protocol handlers outside HTTP.
No private credential-bearing proxy URL is accepted by the setting.

Input normalization and the `YoutubeDL.urlopen` guard reject private/local URLs
and DNS answers for every extractor request, and validate the returned webpage
identity. These are defense in depth, **not full SSRF protection**: urllib/requests
may follow redirects internally and DNS can change after validation. Redirect and
rebinding protection must therefore be enforced by the above proxy/network layer.
Do not treat the post-extraction URL check as protection against a request already
made. Reuse this policy for the full media-download worker.

Tests use fake extractors/DNS, verify skip-download, rejected collections,
optional metadata, request guards, sanitization and fail-closed configuration.
Real portal/network compatibility is not validated by offline tests; no library
can guarantee all sites or bypass access restrictions. A proxy integration test
is required before declaring this operation deployed and functional.
