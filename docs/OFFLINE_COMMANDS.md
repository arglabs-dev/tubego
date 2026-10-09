# PLA-246: durable mobile commands

Each account/device's private library holds `command-outbox`, with a UUID and an
immutable monotonic sequence allocated before an intention is presented as saved.
The queue includes link submissions, explicit recovery, all-device deletion,
server cleanup, preferences, resource priority, and task cancellation/retry.
Every command uses any available network; media transfer permissions are separate.
No credential is stored in the queue. Logout/revocation clears the private library.

The transport serializes all these command types for one server origin. It sends
`POST /api/v1/device/commands`, then records the receipt only while the same session
remains active. Network requests never hold `SessionStore.class` or SQLite write
transactions. Session expiration pauses synchronization; it does not authorize
local deletion. Existing explicit account/device revocation handlers still apply.

The backend persists a device cursor plus pending/finished receipts. A lost reply
replays the same UUID and sequence. Existing resource mutations retain their
atomic effect receipts. Preferences and task mutations also save an effect receipt
in the same transaction as their mutation, so a server restart between the effect
and wrapper receipt cannot repeat a priority increase or overwrite newer values.
Semantic rejection completes that slot and becomes a visible error; transient
network failures, cleanup leases, and expired sessions preserve the pending slot.
User retry creates a new intention after intervening changes, with a fresh UUID.

Delete/recovery mutations increment a resource revision in their transaction and
include it in receipts/events. An older receipt cannot execute its effect again.
A newer local deletion or cleanup sequence fences an earlier recovery reply;
existing deliberate-deletion markers also fence stale delivery snapshots.
New recovery requires explicit approval after deliberate deletion.

Legacy outboxes have no immutable creation timestamp: filesystem mtime changes
when they are retried or acknowledged. Upgrade never pretends to recover that
order. It preserves request UUIDs, assigns permanent sequences once, and turns an
ambiguous pending recovery (alongside pending deletion/cleanup) into a visible
error requiring a new explicit request. Other legacy effects retain their UUID
receipts. This conservative upgrade does not silently recreate deleted files.

The actions screen exposes pending/rejected commands and explicit retries. Android
JobScheduler uses any network and retries with backoff; execution can be delayed
by Android background scheduling. The current MVP retains finished receipts and
local command records for correctness, without pruning them.
