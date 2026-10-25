# Synchronized deliberate deletion (PLA-244)

The Android library asks whether to delete from all devices, or all devices plus
server. Both choices keep title/source/history, never silently re-download a
previously deleted resource, and are available offline for cached local resources.
An immutable UUID command is persisted inside the private origin/user/device
storage before deleting this phone's partial/full copy. It is retried with any
network; control traffic does not require Wi-Fi. Logout cleanup removes this
account's outbox and does not create a synchronized deletion command.

`POST /api/v1/resources/{id}/delete` accepts `scope: devices|devices_and_server`
and `request_id: UUID`. Device-scoped auth and ownership are checked in the same
transaction. The command UUID/payload/result is durable; an identical retry returns
the original result without applying the deletion again after an approved new
request. Reusing a UUID for a different operation gives 409. Foreign resources
remain 404, even for administrators. Each device receives a persistent delivery
status `deleted` and `local_deleted_at`, plus `resource.deleted`; reconnecting
clients can use snapshots even after consuming older events. All-device deletion
also creates a resource-level deliberate-deletion mark for new devices.

Server deletion cancels queued/running work cooperatively, marks the resource
unavailable before unlinking files, and preserves metadata/tasks/history. A durable
resource cleanup job waits for an active worker lease to release. Cleanup unlinks
only `data_dir/media/<owner>/<resource>` using directory descriptors and no symlink
traversal; foreign users and Telegram storage are never included. Missing files
are already clean, filesystem failures stay queued. Five-second retries survive
service restart. A SQLite write transaction fences job recheck/unlink/completion
across multiple API processes. Publication rejects a pending cleanup job.

The Android transfer runtime disconnects an active media socket before local
cleanup. A fsynced `<resource UUID>.deleted` marker survives restarts and fences
old snapshots/partial writers; `.part` and `.media` are removed while the local
manifest retains history with state `deleted`. Only an explicitly approved
new-download action may remove this marker. Normal synchronization never does.
The authenticated server stream also checks delivery revocation between chunks;
confirmation/publication cannot overwrite a deleted delivery without approval.

Exclusively local logout/device-unlink cleanup remains independent. Offline phones
cannot execute an order until contacting the backend; external exported copies are
outside the application's deletion control. The confirmation makes offline timing
clear, without promising instantaneous remote erasure.

Backend tests cover scope/ownership, offline snapshot tombstones, idempotent retry
after explicit approval, active worker fencing, safe filesystem retry/symlinks,
new-device approval, and logout isolation. Android JVM tests cover durable outbox
restart/retry, interrupted session cleanup, partial/full removal, preserved history
and symlink targets. Integration with native transfer cancellation uses PLA-240.
