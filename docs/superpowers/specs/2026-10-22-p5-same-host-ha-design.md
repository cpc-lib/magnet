# P5: same-host dual Worker HA

Approved scope: two API scheduler instances, each with a distinct `magnet.worker.id`
and its own Worker gRPC endpoint, share MySQL and the host data bind mount.
This is Worker/process HA, not host, MySQL, or shared-storage HA.

## Ownership and fencing

MySQL UTC time grants resource leases. Renewal requires a still-live matching
owner/epoch; an expired lease can only be acquired with a higher epoch. Lease
operations commit independently before RPCs. Every data RPC carries the lease's
absolute expiry, epoch and a short command deadline. RPC failures are errors,
not fake successful attachment or zero-byte resume snapshots.

Workers acquire a resource-specific `flock` before adding torrents and retain it
for the process lifetime. Locks and persisted epoch watermarks live on a shared
Docker named volume (Linux filesystem), not the Windows data bind mount. All
Workers sharing data MUST share this lock volume. Epochs survive Worker restart;
old epochs/deadlines cannot mutate or delete files. A watchdog terminates the
Worker process when any data lease expires: process exit closes all libtorrent
threads and files before the kernel releases the lock. This deliberately restarts
other sessions on that Worker too; their owners reattach on the next tick.
It avoids falsely treating asynchronous torrent pause as completed disk fencing.
When frozen by SIGSTOP, a Worker retains locks; takeover waits rather than writing
concurrently. SIGKILL or resumed watchdog exit releases locks safely.

Metadata-only epoch-zero sessions cannot download payload or delete data. Their
bounded TTL and OS locks prevent them from bypassing resource write isolation.
They can be upgraded on the same Worker to a leased data session.

## Scheduling and recovery

Schedulers drive only resources they own. Lost ownership means no further
resume writes or shared-row state changes. Successful renewal refreshes the
adapter's command token. A higher-epoch owner must acquire the physical lock
before attach succeeds; rejection leaves tasks retryable. Recovery rechecks disk
without trusting old resume verification counts. Task completion keeps its two
snapshot rule, and exports remain manual. GC requires current lease ownership
and Worker acknowledgement before deleting shared host files.

## Verification

Unit/integration tests: SQL lease competition, expired renewal, stale owner state
updates, RPC rejection propagation, no commands after lost lease, takeover epoch.
Docker smoke: two real Workers on isolated test volumes, exclusive lock,
expired command, stale epoch after restart, crash takeover with disk recheck,
frozen-old-owner takeover blocked, watchdog exit without heartbeat.
Run `mvn test` and `npm run build`; record skips/limitations in HANDOFF and ISSUE.
