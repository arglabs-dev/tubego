# Private libraries and roles (PLA-230)

All private API calls depend on `require_approved`: session validity, revocation,
email verification and approval are checked on every call. Administrators receive
no implicit ability to read another person's media. Administrative maintenance
uses separate routes guarded by `require_admin`.

`GET /api/v1/resources` returns only the current account's metadata. It accepts
`limit` (1–100), an opaque `cursor`, literal title `search`, and optional `status`
(`ready`, `processing`, `failed`, `deleted`). These status filters describe server
state, not whether a particular phone has a downloaded copy. `GET
/api/v1/resources/{id}` returns the same public fields for an owned resource.
Paths, internal owner IDs and credentials are excluded. Unknown and foreign IDs
both return the same 404 response. Responses are not cacheable.

Future task, delivery, device, session, event, command, download and mutation
routers must use `LibraryScope(connection, principal)` inside their existing
transaction. Its lookup methods restrict records and referenced records to the
owner. A resource ownership check for a mutation must not happen in a different
transaction from the mutation. Never trust a client-supplied user ID or role.

The media root is `settings.data_dir / "media"`; `resources.server_path` should
be relative to it. `open_private_media` provides descriptor-based opening without
following symlinks, refusing paths outside that root and non-regular files. It
also accepts an absolute stored path inside the root for compatibility. A future
worker must finalize files atomically and never mutate ready files in place.
`byte_range` supplies validated single-range resume support. The authenticated
streaming endpoint belongs to PLA-238; these utilities do not expose media by
themselves. Never publish static filesystem URLs or mount this directory publicly.

For administrator mutations, use `audit_admin` in the same database transaction
as the change, recording action, actor, target and non-secret details. It rejects
non-administrators. Approval and bootstrap already write audit entries. Future
blocking, configuration and global deletion cards must consume this helper;
those actions are not implemented by the private-library card itself.

The first administrator must be provisioned locally by the operator:

```bash
PYTHONPATH=backend python -m tubego_server.bootstrap_admin admin@example.com
```

The command prompts for a password and is disabled once an administrator exists.
It is not a public registration endpoint. Public registration always creates a
normal user pending email verification, regardless of submitted role/status.
