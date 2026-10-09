# Explicit backend-only bulk cleanup (PLA-245)

The application provides ServerCleanupActivity (`server_url` extra) for cleaning
only the signed-in user's server files. Approved administrators additionally see
the global scope, which affects all mobile-library users and excludes the
independent Telegram channel. Confirmation explicitly names that scope, reports
known file count/bytes and in-progress tasks when available, and states that local
mobile files and permanent resource history will remain. Offline confirmations
show a cached estimate or say quantities are unavailable; scope is evaluated when
the command actually executes, including newly added server copies then in scope.

GET `/api/v1/server-cleanup/preview?scope=own|global` returns resources in scope,
known server file count/bytes, unknown sizes, active tasks and pending cleanup jobs.
POST `/api/v1/server-cleanup` requires `scope`, immutable UUID `request_id` and
strict `confirmed:true`. Device ownership, approval and current administrator role
for global scope are revalidated inside the same SQLite write transaction. Scope
cannot be changed by replaying a request UUID. A completed retry returns the
original accepted result rather than cleaning resources subsequently added.

The transaction marks resources unavailable and cancels queued/running/paused
server jobs before physical cleanup. Worker cancellation flags and lease fencing
prevent publication or recreation of a cleaned copy. Existing SHA-confirmed mobile
deliveries, local tombstones, title/source/history and per-user preservation settings
are untouched. Authenticated media streams stop between chunks after availability
is revoked; client synchronization preserves downloaded mobile files and can mark
partial transfers unavailable. No `resource.deleted` or device wipe is emitted.

Cleanup shares PLA-244's resource_cleanup module and durable jobs, rather than a
second deletion implementation. Only owning resource directories are unlinked using
safe directory descriptors without following symlinks. Partial files are included.
Filesystem errors remain pending, missing copies count as already cleaned, and a
previous unsafe-reference error cannot disappear through rescheduling a resource
whose server path was already cleared. Jobs wait for cancelled worker leases.
Cross-process SQLite fencing protects publication and retry races. Up to 64
resources are attempted immediately; larger sweeps continue via the existing
five-second runner. Accepted responses describe initially scheduled jobs; a later
preview provides their current pending count.

A token-free outbox persists the confirmation inside the private
origin/user/device directory. Any-network background jobs retry the same UUID after
restarts/connectivity loss. A queued global action therefore cannot execute under a
stale cached admin role. Permission rejection is shown as a durable error and needs
manual retry with current permissions. Logout/account changes fence asynchronous
responses and erase this device's queue without creating any new remote cleanup.
No local media files are removed by the bulk-cleanup client.

Backend tests cover owner/global isolation, preview estimates, administrator
demotion, unchanged complete deliveries/history, idempotent replay, all active task
states, worker lease waiting, filesystem retry/symlinks, confirmation/auth failures
and preservation of unsafe cleanup metadata. JVM tests cover offline restart/UUID
scope, permission rejection/manual retry without local erasure, and logout races.
