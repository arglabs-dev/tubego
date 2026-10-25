# Android URL entry and durable command outbox (PLA-233)

Manual entry and Android Share → Tubego use the same single-resource flow. An
exported ShareReceiverActivity accepts only ACTION_SEND / text/plain, extracts
exactly one HTTP(S) URL, rejects embedded credentials, and forwards only the parsed
URL to the non-exported entry screen. An external intent cannot override the
configured server origin. A server/session must have been configured first.
Playlists/batches are outside this increment; source validation remains server-side.

LinkEntryActivity requires an approved cached account and exposes account-status
navigation otherwise. Users may query metadata online using MediaInfoClient or
save immediately offline. Quality is selected before saving; cached account
preferences determine defaults and whether QualityPicker prompts each time. The
quality can always be overridden per resource. Preferences cache and outbox are
under the private LocalLibraryStorage account/device directory and are erased
on logout/revocation with that device's files.

Each confirmed entry is written as a token-free Properties record with a stable
UUID request_id, URL and selection. A fsynced temporary file is atomically renamed
before the screen reports success. Application/process restart preserves the entry.
The outbox uses POST /api/v1/resources {url, selection, request_id}; retries reuse
the same UUID, and the backend idempotency implementation is PLA-236. Responses
persist resource_id (or id for compatible transports) and mark the entry submitted.
This means accepted by the server, not that video transfer to the phone completed.
Permanent 4xx validation failures are shown; transient failures remain queued.

A persisted JobScheduler job sends this small control request over ANY available
connection, including cellular. No media is transferred by this job. It fetches
current account status before dispatch and derives credentials only from the
origin-scoped SessionStore. Missing/revoked/switched sessions cannot submit another
user's queue. Submitted entries are not retransmitted, while an interrupted request
can safely retry after reconnection. OS scheduling/force-stop can delay background
work until the next allowed run; entering the screen offers scheduling refresh.

MainActivity uses a ScrollView and links to URL entry, queue and preferences.
`server_connection/server_url` stores only the configured HTTPS origin; legacy
activity preferences are read as fallback. Cached preferences never store tokens;
MediaPreferencesActivity now uses encrypted SessionStore rather than legacy
plaintext session preferences.

Writes/checks are fenced by SessionStore.class against local logout/revocation
cleanup. No network request runs under that lock; LinkOutbox commits only if its
original owner session is still active. Lifecycle cleanup uses the same lock.
Thus an old request returning after logout cannot recreate erased private files.

Tests cover single-URL parsing, rejection of ambiguous/credential-bearing input,
durable restart, unchanged idempotency keys, terminal/manual retry state, duplicate
submitted-request suppression, and a revoked owner whose directory was deleted
while its network request was running. These JVM checks do not substitute for
Android JobScheduler/device integration or the backend worker's source download.
