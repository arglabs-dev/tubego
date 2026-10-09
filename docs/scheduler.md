# Server scheduling contract (PLA-237)

The mobile backend starts with one active download. `Scheduler(database)`
selects only queued tasks owned by approved, verified users. Inside each user's
queue the next item has the highest `tasks.priority`, then the oldest creation
time (ID breaks ties). An administrator changes the durable `priority_level`
setting (`normal` or `prioritario`); changes never preempt a running task.

A cycle gives each active normal user one turn and each priority user three.
Users rotate in ID order within each pass; empty queues skip their slots. This
is a weight **per user**, not a 3:1 allocation between whole user groups. The
cursor is stored in global settings and survives scheduler instances/restarts.
Read-only `preview()` does not mutate the cursor or reserve work.

A worker calls `claim()`, performs the download outside the database transaction,
and calls `finish(task['id'], task['claim_token'], status)`. `claim()` returns
`None` if there is already a running task or no eligible queue. The transaction
makes concurrent workers unable to reserve two jobs. `finish` accepts completed,
failed, cancelled, or queued (for retry). The lease token prevents stale workers
from completing newly claimed work after recovery.

After a crash, an **exclusive worker supervisor**, once the prior worker has
stopped, calls `recover_after_worker_stopped()` to requeue interrupted tasks.
Never invoke this at API startup: several API processes must not reset a live
worker. Partial media resume and filesystem/download locking are the future
worker's responsibility; token fencing protects database finalization, not
network or filesystem operations from a worker that was not stopped.

Administrative endpoints are `GET /api/v1/admin/users/priorities` (paginated,
approved users) and `PUT /api/v1/admin/users/{id}/priority` with body
`{"level":"normal"}` or `{"level":"prioritario"}`. The setter creates an audit
entry. Authentication is delegated to the registration/auth module and always
requires an approved, verified administrator.

This card supplies a tested scheduling primitive and administrator setting.
The downloader worker and resource/task creation flows belong to subsequent
cards; downloads are not yet executed by this scheduler alone.
