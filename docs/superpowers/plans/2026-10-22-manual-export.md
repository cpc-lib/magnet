# Manual export implementation plan

Approved design: completing a task and reading exports must never create files. An authenticated POST explicitly exports the task's selected verified files; failed exports can be retried. Existing READY exports remain downloadable. The API's storage root must reference the host directory mounted as /data by the Worker.

1. Add regression tests for read-only listing and retrying a legacy failed export; run and observe failures.
2. Remove automatic completion/list exports, add POST /tasks/{taskId}/exports, and update persisted target paths on successful retries. Run backend tests.
3. Add task-detail export/retry controls and keep file verification progress independent of export status. Build the frontend.
4. Align local STORAGE_ROOT in both env files with Docker's existing host mount; document the mapping. Verify the existing ISO is readable there.
