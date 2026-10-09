# Controlled administrative maintenance (PLA-252)

The Android `MaintenanceActivity` (extra `server_url`) exposes installed versions, upstream version checks, a bounded server-side download speed measurement, and confirmed update/restart jobs. Its command file lives in the origin/user/device private library and is erased on local logout. Commands carry a stable UUID, never a token; retries after a lost response cannot create a second job. A persisted ANY-network Android job sends queued commands. The screen reconnects and reads durable server status; a network error is never shown as successful maintenance.

The API checks the current approved administrator, device ownership and session for every request, and checks them again before a queued operation begins. Read jobs run inside the API using fixed HTTPS upstream hosts, no redirects or proxy environment, bounded responses, timeouts and a one-minute cooldown. Measurements transfer exactly 5 MB from Cloudflare to the **server**, returning download Mbps and elapsed time; this does not benchmark the phone or upload speed. Read jobs have a two-minute claim lease, so an API restart can recover them without accepting a stale runner result. Job responses omit actor/session/payload internals. Requested and completed operations are audited; public errors never include subprocess output or credentials.

## Mutations are disabled by default

The immutable container deployment does not receive Docker access. Update/restart calls fail with `capability_unavailable` unless the API has `TUBEGO_MAINTENANCE_MUTATIONS=true` and an external supervisor has a fresh heartbeat. A supervisor approves a specific clean **pre-staged official Git revision**, and explicit yt-dlp versions. The mobile user cannot supply a repository, command, branch, shell expression or arbitrary package. Updating the backend means building/recreating from that operator-staged revision; staging another revision remains an operator deployment step. On startup the explicitly enabled supervisor verifies its exact SHA by fetching that object from the fixed official HTTPS GitHub repository with TLS verification and redirects disabled; it does not checkout or accept a client-provided ref. No host maintenance was executed while developing or testing this feature.

An operator who chooses to enable this capability must:

1. Back up the existing mobile database/files and stop/migrate the named `mobile-data` volume into an existing host bind directory without changing its ownership. Keep Telegram's storage independent. Do not enable the overlay with an empty directory.
2. Use a clean, trusted checkout whose origin is `arglabs-dev/tubego`, including the tracked maintenance overlay. Pre-stage the reviewed commit; do not run the supervisor from a checkout being edited. The supervisor accepts only its exact current SHA.
3. Bring the **existing Compose project** up using `compose.mobile.yaml` plus `compose.maintenance.yaml`, with the existing bind directory, approved SHA and version. The supervisor verifies the running API's `/data` bind source matches its database directory before acting. API, worker and retention must share the same SQLite database. Changing a project name creates a different deployment and is not supported.
4. Run the supervisor outside the API containers, using a Python environment with the backend's requirements:

   ```sh
   TUBEGO_MAINTENANCE_MUTATIONS=true PYTHONPATH=backend python -m tubego_server.maintenance_supervisor \
     --repo /operator/staged-official-tubego \
     --project existing-project \
     --data-dir /operator/existing-mobile-data \
     --yt-dlp-version 2026.8.19
   ```

   Repeating `--yt-dlp-version` exposes more operator-approved package versions. The API and CLI must both be explicitly enabled. The host supervisor holds an exclusive file lock and polls durable jobs; it never accepts arguments/commands from the client other than the validated action, SHA or version.

A write job sets a durable scheduler gate before waiting. Current server downloads finish normally; no new task starts while maintenance waits. Paused tasks also keep the gate waiting and can be canceled through the existing task UI. The supervisor stops the worker, uses fixed `docker compose` argv with `shell=False`, rebuilds pinned immutable images for updates, recreates only API/worker/retention/egress, checks API health, and verifies the observed package version and SHA. A yt-dlp update additionally requires the operator's staged SHA to match the running backend, avoiding an unintended simultaneous code update. The API may restart without losing the job because the supervisor is external and the job is stored in the shared database. Existing worker startup recovery and claim fencing remain in force.

An interrupted supervisor never blindly replays a `running` write job. Deployment/verification failures leave the scheduler gate in place; do not assume downloads resumed. The operator must inspect and repair deployment state, then may use the same CLI with `--recover-interrupted`. This explicitly rebuilds the operator-approved staged revision/pin, recreates and verifies the service, marks the original operation failed with `operator_recovered_interrupted_operation`, and clears its gate. If the container is completely stopped, restore it with normal operator deployment tools first; recovery fails closed if it cannot inspect the API bind. This is an operator recovery action, not a mobile retry that secretly repeats an update.

## Validation

Automated tests use fake networking and a fake Compose driver; they do not fetch code, install packages, build production images or restart any real service. Tests cover access controls, disabled capabilities, selected-version validation, UUID replay, rate limits, sanitized failure results, worker draining, role changes while queued, interrupted write fencing, bounded downloads, claim-lease recovery and fixed argv construction. Android build/lint/unit validation confirms the activity and background command service compile. A real deployment update/restart rehearsal remains an operator opt-in integration check; no deployment success is claimed from these unit tests.
