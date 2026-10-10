# YouTube runtime dependencies and explicit source verification

The backend previously installed only the base yt-dlp wheel and FFmpeg. That was insufficient for yt-dlp's current YouTube challenge handling: it needs the EJS companion and a supported JavaScript runtime. The [upstream EJS guide](https://github.com/yt-dlp/yt-dlp/wiki/EJS) recommends Deno and describes its restricted permissions; runtime script downloads are optional rather than required when the companion is installed.

Production requirements now install `yt-dlp[default]==2026.8.19` and `deno==2.9.5`. The [exact release's dependency metadata](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/pyproject.toml) specifies `yt-dlp-ejs==0.8.0` in its default extra and Deno 2.9.5 in its pinned runtime extra. [The official Deno PyPI distribution](https://pypi.org/project/deno/2.9.5/) supplies the binary; Docker requires the binary wheel and checks `deno --version` plus EJS import during the image build. The controlled `YTDLP_VERSION` image rebuild also installs the `default` extra, so a reviewed yt-dlp update resolves that version's matching companion rather than keeping an incompatible script bundle. Deno remains explicitly pinned until an operator reviews a runtime update.

`restricted_ytdlp()` forces Deno as the sole runtime and disables remote components. Client or internal caller options cannot enable Node/Bun or npm/GitHub script retrieval. Installed EJS is used locally; the native HTTP/HLS/DASH downloader and rejecting proxy protections still apply to media requests. No cookies, portal credentials, TLS exemptions, alternate DNS resolver or unrestricted external downloader are added.

This corrects a deployment dependency omission. It does **not** establish that all YouTube requests will work: a public video can still require authentication, restrict a datacenter IP, rate-limit requests or remove formats. Such failures remain visible and must not be bypassed or presented as universal platform compatibility.

## Real YouTube matrix entry

The opt-in pipeline runner keeps the fixed [youtube-dl test video](https://www.youtube.com/watch?v=BaW_jenozKc) as `--source youtube` and adds [Me at the zoo, published on YouTube](https://www.youtube.com/watch?v=jNQXAC9IVRw) as `--source youtube_zoo`. The latter is a separate explicit fixture, never an automatic fallback. Both must have a real extracted duration of no more than thirty seconds; the converted output has the same duration cap. It verifies the public HTML page through the restricted proxy, logs installed dependency versions, calls the real authenticated metadata API, requires the `Youtube` extractor and exact source ID, and refuses to enqueue if its duration exceeds thirty seconds. If metadata succeeds, it performs the same real video/audio worker conversions, digest/range/authorization/confirmation checks as the public MP4 fixtures:

```sh
PYTHONPATH=backend:. timeout 180 /path/to/python-env/bin/python -u scripts/verify_public_source_e2e.py --source youtube_zoo
```

The runner's ASGI TestClient requires the test-only `httpx` package in its execution environment; this is not a production API dependency. For container verification, build the production image first, inspect its installed Deno/EJS versions as the unprivileged `tubego` user, and install test client dependencies only in an ephemeral test container/layer. Do not install EJS or a runtime on demand while serving requests. Keep reports/media in the private `/tmp/tubego-source-e2e/run-<uuid>` directory, never production `/data` or Telegram storage.

A failure exits nonzero and records the actual stage/error. The W3C/Blender direct-MP4 passes document the Generic extractor only; they are not a replacement for a successful YouTube run. Network/API failures and Docker/runtime checks must be reported separately. The no-network unit test validates the real YoutubeDL constructor's runtime/remote-component policy without pretending to extract media.

For an immutable test image with neither Git nor checkout metadata, pass its exact reviewed source SHA as `--revision <40-character-sha>` or set the image's existing `TUBEGO_BUILD_REVISION`. The report labels where that revision came from; if neither is available, it records `null` rather than inventing a commit. Installing Git into production just to run this test is unnecessary.

Initial validation: the local production Docker build succeeded with Deno 2.9.5 and packaged EJS import. The runtime-policy unit test and script compile/help checks passed. A real YouTube extraction/download result is recorded separately by the operator's container run; a successful dependency build alone is not a YouTube playback/download pass.

Observed container result for the original `BaW_jenozKc` fixture: installed yt-dlp 2026.8.19, EJS 0.8.0 and Deno 2.9.5 were verified; the public HTML HEAD returned 200, the proxy rejected a private destination with 403, and the API rejected a private URL with 422. The real metadata request then returned an unavailable-source error, so no YouTube extraction or download was established by that run. A public HTML response alone cannot distinguish a deleted video from provider or network restrictions. The second fixed fixture must be run independently and its actual result retained, without changing cookies, credentials, TLS or egress restrictions.
