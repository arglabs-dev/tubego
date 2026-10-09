# PLA-236 — Durable private server queue

The API accepts approved users at `POST /api/v1/resources`:

```json
{"url":"https://source.example/video","selection":"720","request_id":"d6c68de3-1552-4dbf-9efb-bd79874261a5"}
```

`selection` is 480/720/1080/best/audio. If omitted, the user's saved preference
applies only when “ask every time” is disabled; otherwise submission is rejected.
The result contains `resource_id`, `task_id`, `status`, a `task` object, and
`existing`. URL/public-DNS validation runs before acquiring a write transaction.
Request IDs are optional UUIDs; offline clients must preserve one per queued action.
Repeated requests return the same private resource/task. Reusing a request ID for
a different resource gives 409. URL+format+quality deduplication is owner-scoped;
source extractor identity/alternate URL deduplication is extended by PLA-243.
Historical or expired resources are returned as existing and not automatically
requeued. Explicit historical requests belong to PLA-243.

GET `/api/v1/tasks` or `/api/v1/tasks/{id}` exposes private queue state, progress,
attempts, sanitized error and phase, plus any lower-quality/unknown-height notice.
POST `/api/v1/tasks/{id}/priority` makes the selected queued task next within its
user's queue, without interrupting an active download. POST `.../cancel` cancels a queued task or
requests cooperative cancellation of running work. The return indicates
`cancelling` until acknowledged by the worker. POST `.../retry` requeues the same
failed/cancelled task, preserving partial downloads. Repeated retry does not create
another task; completed/ready resources reject retry with 409. Foreign IDs are 404.
Each mutation rechecks active session/device and approved owner inside its transaction.
Priority/task mutation events are synchronized to the user's devices.

The backend scheduler status stays `running` throughout analysis, downloading and
postprocessing, so it cannot claim another task prematurely. Separate durable
phases describe that work. Completed server tasks have phase `ready`; device-side
pending/downloading/paused/complete states remain in deliveries, independently of
the server worker. The API does not start or recover workers.

## Deployment and recovery

`docker compose -f compose.mobile.yaml up --build -d` runs API, restricted proxy and
**one** worker sharing `mobile-data`. Only the API port is published. The worker
uses `restricted_ytdlp`, native HTTP/HLS/DASH and local FFmpeg conversion. It never
uses ffmpeg/curl/RTMP to fetch remote media. Linux flock on `/data/worker.lock`
prevents a second supervisor from claiming or recovering live work. Only after
acquiring that lock can a restarted worker reset previously running tasks to queued.
The shared volume and database are required; network/distributed filesystems with
unreliable flock are outside this deployment contract.

For a local deployment, start the restricted proxy and then:

```sh
TUBEGO_MEDIA_EGRESS_PROXY=http://127.0.0.1:8081 PYTHONPATH=backend python -m tubego_server.worker
```

`--once` processes at most one task. SIGTERM/SIGINT requests a graceful stop;
checks before requests, progress and postprocessing boundaries requeue interrupted
work. If killed outright, the next exclusive supervisor recovers it. Stable UUID
paths beneath `/data/media/<user>/<resource>/media.<ext>` keep yt-dlp partial files
for supported source resumptions. Existing directory components are opened without
following symlinks. Telegram channel overlap is rejected before worker startup.
HTTP Range support depends on the source; if the source cannot resume, yt-dlp may
restart the transfer. No claim is made that changing upstream media is immutable.

The finalized file path is obtained from yt-dlp's actual postprocessed result,
including its nested `requested_downloads` entry, not inferred from an extension.
`publish_ready` calculates checksum and performs cancellation/lease preconditions
inside the publication transaction. A cancel arriving during the checksum cannot
publish deliveries. Published files must no longer be modified. A cancellation
after publication is rejected as already ready; deletion uses the deletion actions.
Final task state, phase, errors, event and lease release are atomic. The worker does
not hold SQLite transactions during DNS, network transfer or FFmpeg.

Failures remain failed for explicit retry; automated retry policy is PLA-254.
Disk thresholds/pausing integrate through PLA-249; the worker currently reports
storage failures without deleting files. Cancellation can wait until a blocking
source request times out (15 seconds) or a local postprocess finishes; it does not
kill ffmpeg halfway through writing a file. Cancelled/failed partial files remain
for retry and must be included in administrative storage cleanup.

## Verification

Tests execute real yt-dlp through the restricted proxy against a local source
representing public Internet DNS, verify publication and authenticated byte-range
transfer, and prove resumed bytes after exclusive-worker restart. Additional tests
cover multiple supervisors, stale fencing tokens, owner isolation, idempotent
requests/actions, priority order, extraction cancellation, cancellation during
checksum publication, sanitized failure and manual retry. Test-only resolver/socket
mapping never enables private destinations in production.
