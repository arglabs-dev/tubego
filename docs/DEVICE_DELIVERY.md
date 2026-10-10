# Device delivery protocol (PLA-238)

The backend exposes authenticated media and durable delivery state. Android
scheduling/transfer execution is supplied by PLA-239/240; this backend card does
not itself download files onto a phone or configure push delivery.

## Publishing from the server worker

After finalizing an immutable file atomically beneath `settings.data_dir/media`,
the worker calls:

```python
publish_ready(database, media_root, resource_id, relative_server_path)
```

The helper calculates the complete SHA-256 and size before publication. A single
transaction rechecks the owner is verified/approved, marks the resource ready,
records `settings(scope='resource', owner_id=resource_id, key='media_sha256')`, and
creates pending deliveries and silent `resource_available` events for all active
linked devices of the owner. Other users and revoked devices are excluded.
Repeated publication of the same existing file is idempotent and does not
backfill devices linked after its first publication. Re-publication after server
removal constitutes a new availability interval.

A previously completed delivery of that same checksum/size is preserved. A local
deleted tombstone changes a delivery to `approval_required`; automatic transfer
must not run until explicitly approved. A changed checksum invalidates previous
completion. Completion checksums are stored in settings with scope `delivery`,
owner ID `device_id:resource_id` and key `confirmed_sha256`.

## Device synchronization

`GET /api/v1/device/sync?event_cursor=0&delivery_cursor=&limit=50` requires a valid,
approved, verified account session bound to an active owned device. Each operation
rechecks that state inside the same transaction as its database work.

The response contains device-specific `deliveries` (private resource metadata,
`sha256`, `delivery_status`, `downloaded_bytes`, `local_deleted_at`, and
`server_available`), `next_delivery_cursor`, `events`, the last `event_cursor`, and
`has_more_events`. Both collections are independently paginated, limit 1–100.
Follow the delivery cursor until null; separately follow the event cursor while
`has_more_events` is true. Delivery rows are ordered by resource ID; event IDs are
monotonic. On launch/reconnect, fetch the full delivery snapshot starting with an
empty delivery cursor, even if no event arrives or the event cursor is current.
The snapshot is authoritative; notifications are hints. Server availability and
local completion are independent states.

New devices have no automatic history backfill. They can inspect their owner's
private history and explicitly request an available item with
`POST /api/v1/resources/{id}/deliveries/request`, JSON `{}`. If the item was locally
removed, send `{"approve_redownload":true}` only after user confirmation. If the
server copy is unavailable, the endpoint returns 404 and the client must use the
resource re-request flow documented in RESOURCE_RECOVERY.md. Already completed same-file requests are no-ops.

## Media transfer and confirmation

`GET` or `HEAD /api/v1/resources/{id}/download` requires device-bound auth, owner
scoping and an eligible delivery for that device. No public URL or administrator
bypass exists. Responses expose `Content-Length`, `X-Content-SHA256`, `ETag`, and
`Accept-Ranges: bytes`. A single `Range` request returns 206/Content-Range; invalid
ranges return 416. `If-Range` must equal the SHA-256 ETag to resume; otherwise the
full current representation is returned. Files open through descriptor-based
no-symlink helpers and stream from that descriptor. There are no completion
notifications.

After persisting the full file and calculating its local checksum, the client
calls `POST /api/v1/resources/{id}/deliveries/confirm` with
`{"size_bytes":N,"sha256":"64 lowercase hex characters"}`. Both must match
server-generated metadata; a partial byte count or wrong checksum cannot mark a
delivery complete. The first valid completion sets `first_delivered_at` once.
Repeated confirmations are idempotent. The confirmation is an authenticated
client attestation, not cryptographic proof of possession against a malicious
client; expected metadata is necessarily public to that same owner. Production
clients must calculate the checksum from the completed local file.

Revoked sessions/devices, blocked/unverified owners, unavailable server files,
and foreign resource IDs cannot confirm or initiate new media transfers.
Streaming revalidates session/device/owner and resource availability before each
chunk, so a known revocation or deletion stops subsequent output. Client transfer
writes and renames also check current session identity and local deletion markers;
remote cleanup is applied when the offline client next contacts the server. Already
received bytes or externally exported copies cannot be remotely recalled. Ready
files must never be modified in place.
