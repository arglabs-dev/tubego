# Identity deduplication and explicit recovery (PLA-243)

Normal submission returns the existing private resource ID when its normalized
source identity, format and quality match. Different audio/video/quality selections
are separate explicit variants. User ownership always scopes identity lookups.
YouTube short/watch/shorts/embed/v links and tracking/timestamp parameters resolve
to the same valid eleven-character video ID; unknown portals preserve the complete
query string, including signed/capability parameters. Generic extractor IDs do not
collapse unrelated pages. Metadata analyzed by the trusted backend or extracted by
the worker records portal/host/source-ID aliases; untrusted client-supplied identity
fields are never used. Previously created URL-key histories and submission UUIDs
are recognized across this identity upgrade. Identity lookup/insert is atomic in a
SQLite write transaction; DNS/portal analysis occurs outside it.

Normal duplicate submission displays the existing ficha and does not restore a
server file or deliberately deleted device copy. Android's entry history links to
that resource's ficha; the explicit recovery activity shows the saved URL, title,
variant and duration. Its private cached history remains accessible offline.

`POST /api/v1/resources/{id}/request-again` accepts an immutable `request_id` UUID
and `approve_redownload` boolean. It revalidates the device session/owner inside
the same transaction, returns an idempotent prior result, rejects pending server
cleanup and retains at most one queued/running/paused task for that resource.
When the backend copy is absent it creates one job for the same permanent ficha;
when present it requests delivery without duplicating the server job. All current
active devices are considered: complete confirmed copies are retained and checksum
publication skips them when unchanged. A changed checksum starts the new variant
generation. Normal server expiry does not create a local deliberate tombstone.

A deliberately deleted delivery needs explicit approval. Approval applies to the
requesting device only; other deliberately deleted copies remain
`approval_required`, including a newly linked device following all-device deletion.
This honors independent per-device consent while normal missing copies are queued.
Existing title/source/history remains unchanged; commands and actions are audited.

The Android confirmation persists a token-free, origin/user/device-scoped recovery
command for delivery over any network, using the same UUID across restarts and
network retries. Before sending it, the dispatcher flushes earlier offline deletion
commands. It stops the transfer runtime before approving a generation. Only after
the authenticated server acknowledgement does it remove the local tombstone and
reset a deleted/missing record to pending under SessionStore.class, then wake
normal download jobs. Downloads still follow Wi-Fi/mobile consent policy.

A random durable deletion-generation stamp fences stale recovery responses: a newer
local delete intent is never cleared by an older approval acknowledgement. Logout
or account change similarly prevents asynchronous responses from recreating erased
private state. Background scheduling uses any-network control traffic, not media
transfer. Temporary cleanup-pending errors remain queued for retry.

Backend tests cover portal aliases, signed-query preservation, private variants,
trusted extracted identity, legacy upgrade, concurrent single-job requests,
checksum-complete skipping, per-device deliberate approval, cleanup/ownership,
revocation and request-ID reuse. JVM tests cover durable recovery retry/consent,
newer deletion-generation fencing and session-cleanup races.
