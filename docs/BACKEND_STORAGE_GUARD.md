# PLA-249 — Shared server storage guard

`src.disk_guard` uses only Python's standard library. Telegram's Downloader and the
mobile worker sample the physical filesystem containing their configured download
roots. Separate channel directories on the same volume therefore see the same disk
utilization. At **90% used or above**, neither starts a new transfer. During native
HTTP/HLS/DASH transfers and before extractor requests/postprocessing, the guard
checks again. Known remaining bytes and local conversion estimates also require
sufficient available space; unknown sizes retain a 1 MiB minimum headroom.

Unclaimed jobs retain queue ordering with a paused phase and storage reason, without
spending attempts; those markers clear when space recovers. The mobile worker stops a blocked engine, preserves partial files and persists
`status=paused`, `phase=paused`, `pause_reason=server_storage`. Its scheduler lease
remains held and paused work counts as active, preventing another claim. It polls
outside SQLite/network operations every two seconds, then resumes the same task
when usage is below 90% and the required space is available. Disk pauses consume
neither error retries nor additional attempts. Durable attempt markers also survive
exclusive-worker restart; recovery includes interrupted paused work. Cancellation
and graceful shutdown remain possible while waiting. ENOSPC is a storage pause,
not an automatically retried source error. No files are deleted by this guard.

Telegram uses the same threshold/space logic at start, extractor requests,
progress and local postprocess boundaries. Its task remains visible as paused and
can be cancelled; it resumes when space recovers. Only native HTTP/HLS/DASH network
downloaders are allowed, since unmanaged ffmpeg/curl/RTMP downloads cannot observe
the guard. Telegram's existing task list remains in memory; partial files persist.
A source may close a long-paused connection or lack Range support, in which case
source-level recovery may restart the transfer or expose an error. No source can be
promised to support resumption.

The guard **cannot safely pause an already-running opaque FFmpeg subprocess**.
Instead, it checks before and after each local postprocess, estimates up to twice
existing local input sizes as required headroom, and observes any ENOSPC failure.
Another process could fill the disk while FFmpeg is running; these boundary checks
and estimates are preventive, not a hard filesystem quota or precise prediction
of output size. No claim is made of mid-command subprocess suspension. An output
is not published until guard checks pass after processing.

Approved administrators alone receive durable `server_storage_paused` events.
Transitions are stored by physical volume in the existing database settings;
repeated polls and process restarts do not resend the same crossing. Normal user
sessions receive their own task pause state, not administrative alerts. The bot
uses the same pre-existing mobile database (`TUBEGO_DATA_DIR/tubego.sqlite3`) for
admin events and does not import FastAPI or initialize/migrate that database.
When channels use different physical volumes, each is guarded independently.

Existing-file delivery, cleanup, configuration and account endpoints do not acquire
the disk download guard and remain available. The API must still have enough disk
space to write its database; unrelated processes filling the filesystem completely
can defeat that operational prerequisite.

Tests cover exact 90% crossing, insufficient remaining space, durable admin-only
alerts and deduplication, unclaimed work with zero attempts, active pause/resume
with intact partials and stable attempt count, cancellation while paused,
ENOSPC-as-pause, restart recovery, and Telegram's start/progress enforcement.
