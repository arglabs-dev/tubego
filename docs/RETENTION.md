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
