# Account revocation and erasure (PLA-232)

Approved administrators can list users, block, unblock or delete from
AdminUsersActivity. Every endpoint requires an active approved administrator
session; mutations re-check the session/role inside the same SQLite transaction.
An administrator cannot block/delete their own account or the last active admin.

- GET `/api/v1/admin/users`: account states and cleanup-pending/error indicators.
- POST `/admin/users/{id}/block`: revoke all sessions/devices, cancel queued/running
  work cooperatively and schedule deletion of owned backend files. Account, email,
  preferences and permanent resource history are retained while access is blocked.
- DELETE `/admin/users/{id}`: perform the same revocation, then remove original
  email/password, verification/reset tokens, history/resources/tasks, commands,
  personal settings and old event payloads. Only minimal anonymous revocation
  tombstones (opaque user/device/session IDs, token hashes and deletion timestamps)
  remain so a long-offline client can still learn to wipe on its next request.
  Old audit detail payloads are scrubbed; opaque audit actions remain.
- POST `/admin/users/{id}/unblock`: requires completed cleanup and a fresh login.
  Verified accounts become approved; unverified accounts return to verification
  and then the normal approval workflow. Deleted accounts cannot be restored.
  Old sessions/devices/files are never resurrected.
- POST `/admin/users/{id}/cleanup/retry`: retry the durable filesystem cleanup.

Deleted user emails are replaced with a random unreachable `.invalid` marker;
original addresses can later register a separate new account. Deleted identities
are not authenticated by login/reset, while their retained old bearer hashes
produce `account_unavailable` plus `wipe_local:true`, including when expired.
Android's existing ApiClient/SessionLifecycle observer wipes only the associated
managed local account/device directory. A disconnected/force-stopped client cannot
receive a remote order until it contacts the server. Exported copies/external
players are outside Tubego's deletion control; confirmations state these limits.

Revocation and resource `server_deleted_at` changes commit before filesystem work.
A durable `settings` job (`scope=account_cleanup`, owner=user ID, key=media) survives
process/service restart. Physical cleanup traverses only `data_dir/media/<user ID>`
using directory descriptors and O_NOFOLLOW; symlinks are unlinked, never followed.
Other users' directories and independent Telegram files are untouched. It includes
unrecorded partial downloads. Invalid/foreign stored-media references are surfaced
as pending errors; they never authorize deletion outside the owning directory.
Missing files/directories count as already cleaned; transient permission/storage
failures remain pending for automatic/manual retry rather than losing the job.

If that user's downloader owns the current scheduler lease, physical cleanup waits
until cooperative cancellation releases it. This prevents an active worker from
recreating files after cleanup. Unblock is denied while cleanup is pending, so a
newly requested file cannot be deleted by an old cleanup job. Cleanup runs every
five seconds in the API lifespan; `TUBEGO_ACCOUNT_CLEANUP_INTERVAL` controls the
interval (0 disables the runner for isolated tests). A restarted API resumes jobs.
Mutations/cleanup are audited and idempotent; errors do not log signed URLs/paths.

Tests exercise two private users and isolated files, missing/retryable filesystem
operations, symlink confinement, worker lease cancellation, role enforcement,
self-lockout protection, stale admin session fencing, personal-data erasure and
long-offline token cleanup signals. Real foreground/background mobile receipt is
provided by the previously tested revocation observer; this card adds its admin UI.
