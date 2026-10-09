# Bounded retries (PLA-254)

Each server task and each device/resource transfer receives an initial attempt plus
three automatic retries. Backoff minimums: 30, 120 and 300 seconds. HTTP 429/5xx,
DNS failures, socket timeouts/reset/refusal and interrupted network reads qualify.
Unsupported/private/restricted/removed sources, unavailable formats, integrity or
protocol mismatches and unknown programming errors fail immediately. Raw extractor
messages, signed URLs, paths and credentials are never persisted as diagnostics.

Server tasks persist `next_retry_at` in scoped settings and their attempt count in
the existing task row. The scheduler excludes future retries while processing other
eligible queues with the existing weighted policy. Storage pauses and supervisor
interruption preserve `attempt_active`; restarting continues that attempt without
spending another. A successful publication survives a crash before task completion
without another extraction. Manual retry clears the budget and deadline on the same
task, only while failed/cancelled; repeated retry while active creates no new job or
resource. Ready resources are protected against re-execution.

Android persists failure count, next eligible epoch time and a stable error code in
each `TransferRecord` manifest. Partials survive transient failures. The retry waits
before attempting another media request, checks the file's Wi-Fi/data permission
again, and does not spend a failure for Wi-Fi loss, OS stop or a storage pause.
JobScheduler may defer these minimum waits under Android power/background policy.
Verified completed media is reused while retrying its small confirmation request;
a snapshot acknowledging server completion clears a lost-response retry without
retransferring the media. A changed server checksum starts a new transfer budget.

“Descargas y permisos de red” displays failed local transfers and an explicit
retry button. The existing per-video/device data permission remains in effect.
No retry creates or broadens a mobile-data authorization. The backend task retry
endpoint is `/api/v1/tasks/{task_id}/retry`.

Only final failures emit `download_failed` events, with safe resource/task IDs,
error code, attempt count and a manual-retry indication. Local terminal failures
persist `state=failed` and `failure_code`, for PLA-253 notifications. Administrators
can read sanitized final diagnostics in “Administrar usuarios → Diagnósticos de
descargas fallidas” or paginated `/api/v1/admin/download-errors`. Technical extractor
output is intentionally suppressed; these diagnostics report stable classifications
and counts rather than secret-bearing raw traces.
