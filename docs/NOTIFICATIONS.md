# Actionable alerts (PLA-253)

The app's “Centro de avisos” is available from download/network controls and the
administrator user screen. It persists the latest 100 alerts for the current
server/account/device even when Android notifications are denied. It offers explicit
opt-in; on Android 13+ only tapping that button requests POST_NOTIFICATIONS. Denying
it does not block downloads, synchronization, local playback or the center.

Stable channels are `tubego_action_required` for terminal failures/phone space and
`tubego_admin` for administration. Administrative entries are filtered by the
current server role and hidden/canceled after a role downgrade is observed. Logout
or revocation removes that device/account's inbox and outstanding system alerts.
Notifications use account/device tags and session checks, so callbacks from an old
session cannot post for a new account. Lock-screen content is private and generic;
raw extractor diagnostics, signed URLs, passwords and local paths are excluded.

Source events are server `download_failed`, `server_storage_paused`, verified
`registration_pending` and `admin_maintenance`; device terminal-failure incidents
and the PLA-250 persistent low-space sequence are local sources. Successful video
completion and ordinary progress/resource availability are deliberately ignored.
The existing low-importance transfer-process notification remains independent.

`GET /api/v1/device/alerts` returns at most 100 events (Android requests 50).
`POST .../alerts/ack` persists an independent device cursor, rechecking session,
ownership and live role. The app persists its inbox/cursor before acknowledging;
lost ACK responses replay safely. Polling processes at most four pages per run,
then resumes on a later run. Device failure incidents and space sequences deduplicate
local notices across process restarts. Manual retry creates a new failure incident.

There is no FCM/push integration. Background delivery uses the existing persistent
Android jobs, normally about every 15 minutes when permitted by the OS; opening the
center refreshes over any available connection. Android can delay jobs and long
running transfers, so administrative alerts remain accessible in the center rather
than claiming immediate delivery. Local failed/space records are checked when a
transfer job finishes, including without a successful server poll.

Server maintenance implementations should call the trusted
`notify_admins(conn, 'admin_maintenance', sanitized_payload, unique_transition_id)`
hook within their state/audit transaction. It deduplicates and addresses only
approved, email-verified administrators. Email verification already calls the hook
for approval requests; the disk guard already emits its administrator-only event.
The existing maintenance card owns actual update/restart operations.
