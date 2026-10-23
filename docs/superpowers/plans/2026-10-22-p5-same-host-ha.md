# P5 same-host HA implementation plan

Goal: fail closed under Worker faults and control-plane partitions, with safe
dual Worker takeover using the existing resource/session identity.

1. Write regression tests for lease expiry, loss and rejected attachment. Observe
   the current unsafe behavior, then fix SQL and scheduler token propagation.
2. Extend the command envelope with lease expiry. Implement native lifetime
   resource locks, persisted epochs, command validation and expiry watchdog.
3. Add a two-Worker Docker smoke runner with deterministic local torrent bytes;
   test crashes, frozen processes, stale commands and verified recovery.
4. Document paired API/Worker setup and shared Linux lock volume. Run all Maven
   modules and frontend build, update HANDOFF/ISSUE, review and commit.
