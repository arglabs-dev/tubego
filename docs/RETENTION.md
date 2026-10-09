# Server retention (PLA-247)

Only an approved administrator can change `preserve_server_files` for a user,
from Android's “Administrar conservación del servidor” screen or authenticated
`PUT /api/v1/admin/users/{id}/retention`. Default: false. The paginated GET endpoint
lists the current values. Disabling preservation immediately checks that user's
existing resources; Android warns before this action.

For automatic cleanup, each finalized publication stores its recipient snapshot.
At least one still-linked recipient must have confirmed its entire size and SHA-256,
and every remaining recipient in that snapshot must have done so. Revoked devices
are excluded; an empty set never qualifies. Late-linked devices do not retroactively
extend an existing publication. Re-publication after removal creates a new snapshot.
Confirmations are client assertions checked against server size and digest; this is
not proof against a malicious authenticated client.

Confirmation, device revocation and logout invoke cleanup. A write transaction
rechecks owner approval, publication, confirmations and preservation immediately
before unlink. Directory descriptors reject symlinks and traversal; nested worker
paths must begin with the resource owner's identifier. Legacy flat paths remain
supported. Server deletion preserves resource history, completed deliveries and
mobile files. It emits a synchronization event, not a completion notification.
Repeated valid confirmations remain idempotent after automatic server deletion.

Preservation excludes automatic cleanup only. Explicit cleanup and account blocking
must still delete server copies. PLA-248 adds configurable absolute/delivery deadlines
through the trusted `delete_if_eligible(..., expired=predicate)` extension; this card
does not introduce a timer or claim a deadline is already enforced.

## Configurable deadlines (PLA-248)

Android administration also edits two global integer limits, in hours: initially
72 from `ready_at` and 4 from `first_delivered_at`. Accepted range: 1–8760 hours.
The deadline is the earlier of those two dates; without a first confirmation only
the absolute date applies. Timestamps are UTC and use the backend clock. Changing
limits recalculates existing files from their original dates, without resetting
those dates or extending the absolute deadline after a late confirmation.

`GET /api/v1/admin/retention/deadlines` reads persisted settings.
`POST .../deadlines/preview` receives `absolute_hours` and `delivery_hours` and
returns the number of currently eligible expirations plus a single-use preview
token valid for five minutes. `PUT .../deadlines` requires that token and the same
limits. If immediate removals are possible, `confirm_immediate_deletion: true` is
required. Changed impact requires a fresh preview, so Android shows the impact
before committing the policy. The write and audit entry require a currently
approved administrative session.

Run `python -m tubego_server.retention_service` with the same `TUBEGO_DATA_DIR` as
the API. `compose.mobile.yaml` includes this service and the shared volume. The
service sweeps on startup and every 60 seconds. It uses the SQLite write fence
around each unlink, re-reading current policy and preservation, so concurrent
sweepers or a stale scan cannot override a newer extension. Restart reads the same
persisted settings and original timestamps. Expired files may remain until the
next sweep (up to about 60 seconds under normal operation); unlink failures retry
later. No local mobile files or history are removed by this service.
