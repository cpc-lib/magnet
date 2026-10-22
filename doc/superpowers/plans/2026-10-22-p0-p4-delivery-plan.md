# P0–P4 必须交付实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Each task must keep the test-first order below.

**Goal:** 在现有 Java 控制面、C++ libtorrent Worker、MySQL/Redis 和 React 前端基础上，完成 P0–P4 的真实可验收交付：解析、单用户下载、共享 Session、恢复、验证、导出和安全隔离。

**Architecture:** Java 保存用户意图和状态，C++ Worker 负责 libtorrent Session、Piece 校验和受控下载目录；同一资源在同一存储域只创建一个 Session。Java 通过按文件快照计算用户进度，并用导出服务把已验证文件物化到用户隔离目录。

**Tech Stack:** Java 21, Spring Boot 3.3, MyBatis, Flyway, MySQL 8, Redis, gRPC/Protobuf, libtorrent-rasterbar 2.0.8, C++20, React 18, TypeScript, Ant Design, Vite, JUnit 5, Testcontainers。

---

## 文件地图

| 责任 | 文件 |
| --- | --- |
| 资源/Session/导出领域 | `server/download-domain/src/main/java/com/magnetplatform/domain/resource/TorrentResource.java`, `server/download-domain/src/main/java/com/magnetplatform/domain/task/EngineSessionRecord.java`, 新增 `domain/export/FileExport.java`、`ExportState.java` |
| 引擎契约 | `server/download-engine-spi/src/main/java/com/magnetplatform/spi/ResourceDescriptor.java`, `EngineSnapshot.java`, `ResumeSnapshot.java` |
| 应用端口 | `server/download-application/src/main/java/com/magnetplatform/application/port/` 下新增下载存储、导出和 GC 端口 |
| 解析/任务/导出编排 | `ParseService.java`, `TaskService.java`, 新增 `ExportService.java`, `ReferenceGcService.java` |
| MyBatis/Flyway | `server/download-infrastructure/src/main/resources/db/migration/V4__runtime_storage_and_exports.sql`、对应 mapper/repository 文件 |
| Java gRPC 适配器 | `server/download-engine-libtorrent/src/main/java/com/magnetplatform/engine/libtorrent/GrpcLibtorrentEngine.java`, `MockLibtorrentEngine.java` |
| C++ Worker | `native/proto/download_engine.proto`, `native/libtorrent-worker/src/main.cpp`, `native/README.md` |
| REST DTO/API | `server/download-api/src/main/java/com/magnetplatform/api/dto/Dtos.java`, `TaskController.java`, 新增 `ExportController.java` |
| 前端 | `web/src/api/types.ts`, `App.tsx`, `pages/TasksPage.tsx`, 新增 `pages/TaskDetailPage.tsx` |
| 测试 | 现有 `server/**/src/test`；新增应用服务、存储、导出和 Worker smoke 测试 |

工作区没有 Git 元数据；实现期间用每个任务的测试、构建和 `git` 可用性检查作为检查点，不执行不存在的提交命令。

## Task 1: 建立运行时存储和数据库契约

**Files:**

- Create: `magnet-platform/server/download-infrastructure/src/main/resources/db/migration/V4__runtime_storage_and_exports.sql`
- Create: `magnet-platform/server/download-infrastructure/src/main/java/com/magnetplatform/infrastructure/FileDownloadStorage.java`
- Modify: `magnet-platform/server/download-domain/src/main/java/com/magnetplatform/domain/resource/TorrentResource.java`
- Modify: `magnet-platform/server/download-domain/src/main/java/com/magnetplatform/domain/task/EngineSessionRecord.java`
- Create: `magnet-platform/server/download-domain/src/main/java/com/magnetplatform/domain/export/ExportState.java`
- Create: `magnet-platform/server/download-domain/src/main/java/com/magnetplatform/domain/export/FileExport.java`
- Create: `magnet-platform/server/download-application/src/main/java/com/magnetplatform/application/port/DownloadStorage.java`
- Create: `magnet-platform/server/download-application/src/main/java/com/magnetplatform/application/port/FileExportRepository.java`
- Create: `magnet-platform/server/download-application/src/main/java/com/magnetplatform/application/port/ResourceGcRepository.java`
- Create: `magnet-platform/server/download-infrastructure/src/test/java/com/magnetplatform/infrastructure/FileDownloadStorageTest.java`

- [ ] **Step 1: Write the failing storage contract test**

Add a test in the infrastructure module that constructs a temporary `FileDownloadStorage` through the application-facing `DownloadStorage` contract and asserts the exact paths:

```java
@Test
void storagePathsStayInsideConfiguredRoot() throws IOException {
    Path root = Files.createTempDirectory("magnet-storage-");
    FileDownloadStorage storage = new FileDownloadStorage(root.toString());

    assertEquals(root.resolve("resources/" + RESOURCE_ID), storage.resourceRoot(RESOURCE_ID));
    assertEquals(root.resolve("resume/" + RESOURCE_ID + ".dat"), storage.resumeFile(RESOURCE_ID));
    assertEquals(root.resolve("exports/" + USER_ID + "/" + TASK_ID),
            storage.exportRoot(USER_ID, TASK_ID));
    assertThrows(DomainException.class,
            () -> storage.resolveResourceFile(RESOURCE_ID, "../outside"));
}
```

Run: `mvn -q -pl server/download-infrastructure -am -Dtest=FileDownloadStorageTest test`  
Expected: FAIL because `DownloadStorage`/`FileDownloadStorage` do not exist.

- [ ] **Step 2: Add the minimal storage contract and implementation**

Create the following contract in `DownloadStorage.java`:

```java
public interface DownloadStorage {
    Path resourceRoot(UUID resourceId);
    Path resumeFile(UUID resourceId);
    Path exportRoot(UUID userId, UUID taskId);
    Path resolveResourceFile(UUID resourceId, String relativePath);
    void writeResumeAtomically(UUID resourceId, byte[] bytes);
    Optional<byte[]> readResume(UUID resourceId);
    void materialize(Path source, Path target);
    void deleteTree(Path root);
}
```

Implement `FileDownloadStorage` with `StorageProperties.root()` as the normalized root. `resolveResourceFile` must use `PathSanitizer`-style component checks and `candidate.startsWith(resourceRoot(resourceId))`; `writeResumeAtomically` must write a sibling temporary file, close it, then use `ATOMIC_MOVE` with a `REPLACE_EXISTING` fallback.

Run `mvn -q -pl server/download-infrastructure -am -Dtest=FileDownloadStorageTest test`.  
Expected: PASS.

- [ ] **Step 3: Write the migration and domain constructor tests**

Add assertions to `TorrentMetaParserTest`/a new domain test that a resource carries a nullable `metadataPath`, and a Session record carries a nullable `resumePath` without changing existing constructor behavior.

Run: `mvn -q -pl server/download-domain -am test`  
Expected: FAIL until the new fields and constructors exist.

- [ ] **Step 4: Add V4 schema and update domain persistence fields**

`V4__runtime_storage_and_exports.sql` must apply these exact changes:

```sql
ALTER TABLE torrent_resource ADD COLUMN metadata_path VARCHAR(1024) NULL;
ALTER TABLE engine_session ADD COLUMN resume_path VARCHAR(1024) NULL;

CREATE TABLE file_export (
    id            BINARY(16) NOT NULL PRIMARY KEY,
    task_id       BINARY(16) NOT NULL,
    user_id       BINARY(16) NOT NULL,
    resource_id   BINARY(16) NOT NULL,
    file_index    INT NOT NULL,
    relative_path VARCHAR(1024) NOT NULL,
    target_path   VARCHAR(1024) NOT NULL,
    state         VARCHAR(16) NOT NULL,
    bytes         BIGINT NOT NULL DEFAULT 0,
    error_code    VARCHAR(32) NULL,
    error_message VARCHAR(1024) NULL,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    UNIQUE KEY uk_file_export_task_file (task_id, file_index),
    KEY idx_file_export_user (user_id, created_at),
    KEY idx_file_export_task (task_id, state)
) ENGINE = InnoDB;

CREATE TABLE resource_gc_candidate (
    resource_id  BINARY(16) NOT NULL PRIMARY KEY,
    state        VARCHAR(16) NOT NULL,
    candidate_at DATETIME(6) NOT NULL,
    not_before   DATETIME(6) NOT NULL,
    last_error   VARCHAR(1024) NULL,
    updated_at   DATETIME(6) NOT NULL
) ENGINE = InnoDB;
```

Update `TorrentResource`, `EngineSessionRecord`, their constructors, and the resource/session MyBatis result maps to include the new nullable paths. Keep existing application-layer constructors delegating to the new fields with `null` so current tests stay readable.

Run: `mvn -q -pl server/download-domain,server/download-infrastructure -am test`  
Expected: PASS, with Flyway migration syntax validated by the existing API integration profile when Docker is available.

- [ ] **Step 5: Add export and GC repository contracts**

Define `FileExportRepository` with `insert`, `findByTask`, `findOwned`, `updateState`, and `deleteOwnedByTask`. Define `ResourceGcRepository` with `upsertCandidate`, `findDue`, `removeCandidate`, and `recordFailure`. Add `FileExportMapper`, `ResourceGcMapper`, XML mapper files, and MyBatis repository implementations in `download-infrastructure`.

Run: `mvn -q -pl server/download-infrastructure -am test`  
Expected: PASS.

## Task 2: Persist validated metadata and wire it into task creation

**Files:**

- Modify: `server/download-application/src/main/java/com/magnetplatform/application/port/TorrentStorage.java`
- Modify: `server/download-application/src/main/java/com/magnetplatform/application/ParseService.java`
- Modify: `server/download-application/src/main/java/com/magnetplatform/application/TaskService.java`
- Modify: `server/download-application/src/test/java/com/magnetplatform/application/ParseServiceTest.java`
- Modify: `server/download-application/src/test/java/com/magnetplatform/application/TaskServiceTest.java`
- Modify: `server/download-engine-spi/src/main/java/com/magnetplatform/spi/ResourceDescriptor.java`

- [ ] **Step 1: Add failing metadata-path assertions**

Extend `ParseServiceTest` so that after `submitTorrent` the returned resource has the path returned by `FakeStorage.saveTorrent`, and after a resolving Magnet is completed the returned Worker bytes are saved and assigned to the same resource. Extend `TaskServiceTest` so the recording engine receives non-empty torrent bytes and a resource storage path when dispatching a queued task.

Run: `mvn -q -pl server/download-application -am -Dtest=ParseServiceTest,TaskServiceTest test`  
Expected: FAIL because metadata paths are not stored and `ResourceDescriptor` currently has no torrent/storage fields.

- [ ] **Step 2: Extend the storage and descriptor contracts minimally**

Keep upload storage behavior and add resource metadata lookup only through the existing `TorrentStorage` port:

```java
public interface TorrentStorage {
    String saveTorrent(byte[] bytes);
    Optional<byte[]> loadTorrent(String relativePath);
}
```

Change `ResourceDescriptor` to:

```java
public record ResourceDescriptor(
        String resourceId,
        byte[] btih,
        byte[] btmh,
        String versionType,
        String metadataUri,
        List<String> trackers,
        byte[] torrentFile,
        String storagePath,
        byte[] resumeData) {
}
```

Update every existing constructor call in `ParseService`, `TaskService`, tests, and the Mock engine to pass `null` for new optional fields until the real data path is wired.

- [ ] **Step 3: Persist the metadata path in ParseService**

Save uploaded bytes before creating the READY job. Refactor `resolveTorrentResource` to accept `metadataPath`; for a new resource set it directly, and for a skeleton resource set it during `completeResolvedMetadata`. Update the resource repository SQL so `metadata_path` is written on insert/update. Do not mark a Magnet READY until the returned bytes pass `TorrentMetaParser` and are atomically saved.

Run the focused tests.  
Expected: PASS.

- [ ] **Step 4: Pass validated metadata and storage path into TaskService**

Inject `TorrentStorage` and `DownloadStorage` into `TaskService`. In `ensureEngineSession`, load `resource.getMetadataPath()`, fail with `INPUT_LOST` if READY metadata is missing, read the saved resume bytes, and construct `ResourceDescriptor` with:

```java
new ResourceDescriptor(
        resource.getId().toString(),
        resource.getBtih(),
        resource.getBtmh(),
        resource.getVersionType().name(),
        resource.getMetadataPath(),
        resource.getTrackers(),
        torrentBytes,
        downloadStorage.resourceRoot(resource.getId()).toString(),
        resumeBytes)
```

Run: `mvn -q -pl server/download-application -am -Dtest=ParseServiceTest,TaskServiceTest test`  
Expected: PASS.

## Task 3: Complete the native Worker contract and implementation

**Files:**

- Modify: `magnet-platform/native/proto/download_engine.proto`
- Modify: `magnet-platform/native/libtorrent-worker/src/main.cpp`
- Modify: `magnet-platform/native/libtorrent-worker/CMakeLists.txt`
- Modify: `magnet-platform/native/Dockerfile`
- Modify: `magnet-platform/native/README.md`
- Create: `magnet-platform/native/test-fixtures/create_v1_fixture.py`

- [ ] **Step 1: Write the gRPC contract change first**

Add file-level verified progress to `GetSnapshotResponse`:

```proto
repeated int64 file_verified_bytes = 7;
```

Add explicit comments to `AddTorrentRequest.storage_path` that Java passes a path under the Worker download root. Regenerate Java/C++ stubs through the existing Maven/CMake generators; do not check generated `target` files into source.

Run: `mvn -q -pl server/download-engine-libtorrent -am -DskipTests compile`  
Expected: FAIL or compile errors until the Java adapter maps the new field.

- [ ] **Step 2: Add the failing Worker behavior checks**

Create `native/test-fixtures/create_v1_fixture.py` that writes a deterministic 32 KiB `payload.txt`, calculates two SHA-1 piece hashes at 16 KiB, and writes a minimal single-file V1 `.torrent` with `name`, `length`, `piece length`, and `pieces`. The script must accept an output directory and produce exactly `payload.txt` and `payload.torrent`.

Add a smoke-test checklist to `native/README.md` that invokes the built Worker with `AddTorrent`, `UpdateFilePriorities`, `GetSnapshot`, `SaveResumeData`, `Pause`, `Resume`, and `RemoveTorrent`; initially mark the checks as expected to fail for resume and file progress.

- [ ] **Step 3: Implement safe AddTorrent and resume loading**

In `main.cpp`:

1. Parse `--download-root` and store it in a normalized `g_download_root`.
2. Reject an `AddTorrentRequest.storage_path` that is empty, absolute outside the root, or escapes the root after normalization.
3. When `resume_data` is present, call `lt::read_resume_data`, then overwrite `save_path` only with the validated request path.
4. Otherwise load `torrent_file` or construct the info-hash request as today.
5. Keep the existing `resource_id` map as the single-Worker Session identity.

Run the Docker build:

```powershell
docker build -t magnet-libtorrent-worker:test .
```

Expected: exit code 0 and a compiled `/workspace/build/libtorrent-worker` binary.

- [ ] **Step 4: Implement file progress and resume serialization**

In `GetSnapshot`, call `torrent_handle.file_progress(lt::torrent_handle::piece_granularity)` and append each value to `file_verified_bytes`. The piece-granularity flag ensures progress counts only data that passed the hash check.

In `SaveResumeData`, call `handle.get_resume_data(lt::torrent_handle::save_info_dict)`, serialize with `lt::write_resume_data_buf`, increment a per-resource resume version, and return the bytes. Return a normal `CommandResponse`-style error in the response fields for missing handles instead of gRPC `UNIMPLEMENTED`.

Run the Worker smoke checklist against the Docker image.  
Expected: `GetSnapshot` returns one value per torrent file, and the returned resume bytes are non-empty for a valid Session.

- [ ] **Step 5: Keep lifecycle RPCs idempotent and update documentation**

Keep Pause/Resume/RemoveTorrent idempotent for an absent `resource_id`. For `remove_unreferenced_data=true`, use libtorrent’s delete-files removal option; otherwise remove only the Session. Add the exact supported RPC table and the `--download-root` mount requirement to `native/README.md`; remove the stale statement that `ResolveMetadata` and `SaveResumeData` are unimplemented.

Run: `docker build -t magnet-libtorrent-worker:test .` and the README smoke commands.  
Expected: all listed P2 lifecycle checks pass.

## Task 4: Map Worker snapshots and persist Session recovery state

**Files:**

- Modify: `server/download-engine-spi/src/main/java/com/magnetplatform/spi/EngineSnapshot.java`
- Modify: `server/download-engine-spi/src/main/java/com/magnetplatform/spi/ResumeSnapshot.java`
- Modify: `server/download-engine-libtorrent/src/main/java/com/magnetplatform/engine/libtorrent/GrpcLibtorrentEngine.java`
- Modify: `server/download-engine-libtorrent/src/main/java/com/magnetplatform/engine/libtorrent/MockLibtorrentEngine.java`
- Modify: `server/download-application/src/main/java/com/magnetplatform/application/port/EngineSessionRepository.java`
- Modify: `server/download-infrastructure/src/main/java/com/magnetplatform/infrastructure/MyBatisEngineSessionRepository.java`
- Modify: `server/download-infrastructure/src/main/resources/mappers/EngineSessionMapper.xml`
- Modify: `server/download-application/src/test/java/com/magnetplatform/application/TaskServiceTest.java`

- [ ] **Step 1: Write the failing snapshot mapping test**

Add a configurable fake engine to `TaskServiceTest` that returns:

```java
new EngineSnapshot("session", SessionState.ACTIVE, true,
        30_000L, 128L, 0L, 2, List.of(20_000L, 10_000L))
```

The test must assert that a task selecting only file 1 receives `10_000` verified bytes, not a proportion of the Session total. Run the focused test and observe failure because `EngineSnapshot` has no file list and `download_task_file` is never updated.

- [ ] **Step 2: Extend SPI and adapter mappings**

Change `EngineSnapshot` to include an immutable `List<Long> fileVerifiedBytes`; update `empty` and all Mock/test constructors. In `GrpcLibtorrentEngine.snapshot`, map `resp.getFileVerifiedBytesList()` and preserve the existing scalar fields. In `ResourceDescriptor` handling, set `torrent_file`, `storage_path`, and `resume_data` on `AddTorrentRequest` when present.

Run: `mvn -q -pl server/download-engine-libtorrent,server/download-application -am test`  
Expected: PASS for unit tests.

- [ ] **Step 3: Add CAS-safe file progress persistence**

Add `updateFileProgress(UUID taskId, int fileIndex, long verifiedBytes)` to `DownloadTaskRepository` and `DownloadTaskMapper.xml`:

```xml
<update id="updateFileProgress">
    UPDATE download_task_file
    SET verified_bytes = #{verifiedBytes}
    WHERE task_id = #{taskId} AND file_index = #{fileIndex}
      AND verified_bytes &lt;= #{verifiedBytes}
</update>
```

Implement the repository method and the matching in-memory test method. Clamp values in Java to the declared resource file size before writing.

- [ ] **Step 4: Persist resume metadata and recovery lookup**

Add `updateResume(UUID sessionId, long resumeVersion, String resumePath, LocalDateTime updatedAt)` and `findRecoverable()` to `EngineSessionRepository`. Update the mapper SQL to write `resume_version`, `resume_path`, and `state`. In `TaskService`, call `engine.saveResume` before moving an unused Session to IDLE and write the bytes through `DownloadStorage.writeResumeAtomically`.

Run the focused application tests.  
Expected: resume version/path persist and a new scheduler tick can reconstruct a Session using the saved descriptor.

## Task 5: Finish shared Session scheduling and P2/P3 task semantics

**Files:**

- Modify: `server/download-application/src/main/java/com/magnetplatform/application/TaskService.java`
- Modify: `server/download-domain/src/main/java/com/magnetplatform/domain/task/TaskStatus.java`
- Modify: `server/download-infrastructure/src/main/resources/mappers/DownloadTaskMapper.xml`
- Modify: `server/download-scheduler/src/main/java/com/magnetplatform/scheduler/TaskDispatcher.java`
- Modify: `server/download-application/src/test/java/com/magnetplatform/application/TaskServiceTest.java`

- [ ] **Step 1: Add failing A/B shared-resource tests**

Add two tasks for the same resource: Alice selects file 0, Bob selects file 1. After `dispatchTick`, assert the fake engine receives `{0, 1}`. Pause Alice, run another tick, and assert the demand becomes `{1}`. Delete Alice, run another tick, and assert Bob remains `DOWNLOADING` and the Session is not removed.

Run: `mvn -q -pl server/download-application -am -Dtest=TaskServiceTest test`  
Expected: FAIL because the current fake engine does not receive per-file progress/lifecycle changes and the task update loop uses Session-total proportional progress.

- [ ] **Step 2: Implement exact demand and progress rules**

Use only `QUEUED`, `STARTING`, `DOWNLOADING`, `VERIFYING`, and `REPAIRING` tasks for demand; exclude `PAUSING`, `PAUSED`, `FAILED`, `DELETING`, and `DELETED`. For each active task, sum `snapshot.fileVerifiedBytes[index]` over selected files, clamp to `selectedBytes`, update each file row, then update the task row with the sum.

Do not call `pauseSession` when one user pauses. Call it only when the resource has no active demand, after saving resume data and changing the Session record to IDLE.

- [ ] **Step 3: Implement resume/recovery dispatch**

At scheduler startup and on each tick, scan `EngineSessionRepository.findRecoverable()`. For each Session, load the resource, metadata, storage path, and resume bytes; call `createOrAttach`; update Session state to the returned engine state. A missing/corrupt resume file must use an empty resume and force libtorrent to recheck actual files; it must never mark a task COMPLETED from the old database count.

- [ ] **Step 4: Implement verified completion and repair**

Move a task to VERIFYING only when every selected file has reached its declared size in the Worker’s piece-granularity snapshot. Move VERIFYING to COMPLETED only after a second snapshot confirms the same values; the dispatcher then invokes `ExportService.materializeCompleted`, and export state independently determines whether a file is readable. If an existing completed task is recovered with lower actual progress, transition it to REPAIRING and then QUEUED.

Run the full `TaskServiceTest`.  
Expected: PASS for state transitions, demand union, per-user progress, recovery, and no cross-user Session removal.

## Task 6: Implement safe user exports and P4 GC

**Files:**

- Create: `server/download-application/src/main/java/com/magnetplatform/application/ExportService.java`
- Create: `server/download-application/src/main/java/com/magnetplatform/application/ReferenceGcService.java`
- Create: `server/download-application/src/test/java/com/magnetplatform/application/ExportServiceTest.java`
- Create: `server/download-api/src/main/java/com/magnetplatform/api/controller/ExportController.java`
- Modify: `server/download-api/src/main/java/com/magnetplatform/api/controller/TaskController.java`
- Modify: `server/download-api/src/main/java/com/magnetplatform/api/dto/Dtos.java`
- Modify: `server/download-scheduler/src/main/java/com/magnetplatform/scheduler/TaskDispatcher.java`

- [ ] **Step 1: Write failing export and symlink tests**

Add tests that:

```java
@Test
void materializesOnlySelectedVerifiedFiles() { /* selected file is READY; unselected file is absent */ }

@Test
void rejectsTraversalAndSymlinkEscape() { /* ../escape and a symlink target outside root both fail */ }

@Test
void strangerCannotListOrReadExport() { /* service returns NOT_FOUND */ }
```

Run: `mvn -q -pl server/download-application -am -Dtest=ExportServiceTest test`  
Expected: FAIL because `ExportService` and `FileExport` persistence do not exist.

- [ ] **Step 2: Implement materialization and export state**

`ExportService.materializeCompleted(task)` must:

1. reload the task and selected file rows;
2. require task status `COMPLETED` and every selected file to be verified;
3. resolve source files only through `DownloadStorage.resolveResourceFile`;
4. create `PENDING` records, copy to temporary target files, verify copied byte counts, atomically rename, and mark each record `READY`;
5. on an `IOException`, mark the record `FAILED` with `EXPORT_IO_ERROR` and leave the source intact.

Implement `listOwned`, `openOwned`, and `deleteOwned` with a task ownership check before every operation.

- [ ] **Step 3: Add REST endpoints**

Add the following controller methods with `JwtPrincipal` ownership checks:

```java
@GetMapping("/tasks/{taskId}/exports")
public List<ExportView> list(Authentication auth, @PathVariable UUID taskId)

@GetMapping("/tasks/{taskId}/exports/{exportId}/content")
public ResponseEntity<Resource> content(Authentication auth, @PathVariable UUID taskId,
                                        @PathVariable UUID exportId)
```

Update `TaskController.delete` to pass `removeOwnExport` into `ExportService.deleteOwned` before deleting the task tombstone. Return `404` for an export owned by another user.

- [ ] **Step 4: Implement GC candidate lifecycle**

`ReferenceGcService` must mark a resource candidate only when there are no non-deleted tasks, no non-terminal exports, and no resume requirement. A scheduled sweep must re-check those conditions after `magnet.gc.grace-seconds` (default `300`), set state `GC_DELETING`, call `DownloadEngine.removeSession(resourceId, true)`, delete the resource directory/resume file, and remove the candidate. On any exception, store the message and keep the candidate for the next sweep.

Run: `mvn -q -pl server/download-application,server/download-api,server/download-scheduler -am test`  
Expected: PASS for export authorization, safe materialization, delete semantics, and GC retry behavior.

## Task 7: Complete API integration coverage for P0–P4

**Files:**

- Modify: `server/download-api/src/test/java/com/magnetplatform/api/ApiIntegrationTest.java`
- Modify: `server/download-api/src/main/java/com/magnetplatform/api/GlobalExceptionHandler.java` if new storage/export errors need explicit mappings
- Modify: `server/download-api/src/main/resources/application.yml`
- Modify: `magnet-platform/infra/docker-compose.yml`

- [ ] **Step 1: Add failing integration scenarios**

Extend `ApiIntegrationTest` with:

1. A creates a task from a READY upload; B gets `404` for the task and its files.
2. A and B submit the same resource; both can see their own parse history but not each other’s task.
3. A lists/reads its export; B gets `404` for the same export.
4. `removeOwnExport=false` leaves the export; `true` removes only A’s export.
5. malformed `targetSubdirectory` returns `422`.

Run: `mvn -q -pl server/download-api -am -Dtest=ApiIntegrationTest test`  
Expected: FAIL at the new endpoint/behavior assertions.

- [ ] **Step 2: Make Testcontainers execution explicit**

Run `docker version --format '{{.Server.Version}}'` and ensure it returns the active server version. Run the integration test without `-q` and inspect the Surefire XML for `disabled="true"`; if Testcontainers still selects the invalid pipe strategy, configure the test JVM with `DOCKER_HOST=npipe:////./pipe/docker_engine` in the Maven test execution and keep `disabledWithoutDocker=true` only for hosts with no Docker.

- [ ] **Step 3: Implement API wiring and run the full integration test**

Add DTO conversions for `FileExport`, inject the new services, and set `magnet.gc.grace-seconds` to `300` in `application.yml`. Keep the existing Compose topology: the API currently runs on the host, so document `STORAGE_ROOT=./data` for the API and bind-mount the same host `data` directory to the Worker’s `/data`; do not invent an API container or change the P5/P6 scope.

Run: `mvn -pl server/download-api -am test`  
Expected: all existing auth/parse isolation assertions and the new task/export assertions pass; no test is silently skipped when Docker is available.

## Task 8: Finish the P0–P4 web workflow

**Files:**

- Modify: `magnet-platform/web/src/api/types.ts`
- Modify: `magnet-platform/web/src/App.tsx`
- Modify: `magnet-platform/web/src/pages/TasksPage.tsx`
- Create: `magnet-platform/web/src/pages/TaskDetailPage.tsx`
- Modify: `magnet-platform/web/src/pages/ParseDetailPage.tsx`

- [ ] **Step 1: Add the typed API model and route**

Add these exact types:

```ts
export interface TaskFileDetail extends TaskFileView {
  relativePath: string;
  sizeBytes: number;
}

export interface ExportView {
  id: string;
  fileIndex: number;
  relativePath: string;
  state: 'PENDING' | 'READY' | 'FAILED' | 'DELETED';
  bytes: number;
  errorMessage: string | null;
}
```

Add `/tasks/:id` to `App.tsx`, link each task row to it, and keep all routes under `Protected`/`Shell`.

- [ ] **Step 2: Write the task detail UI against the API contract**

`TaskDetailPage` must load `/tasks/{id}`, `/tasks/{id}/files`, and `/tasks/{id}/exports`; display the task status and selected/verified bytes; render file-level progress; show `READY` exports as links to `/api/v1/tasks/{id}/exports/{exportId}/content`; and disable actions according to the existing `allowedActions` rules.

- [ ] **Step 3: Add live refresh and delete wording**

Use the existing two-second SSE endpoint for the task detail page with a fallback three-second GET refresh. Stop the connection on terminal status/unmount. Update the list delete confirmation to state whether the export will be removed and route `removeOwnExport=true` only when the user confirms that option.

Run:

```powershell
cd D:\release\magnet\magnet-platform\web
npm run build
```

Expected: TypeScript compile succeeds and Vite produces a production bundle.

## Task 9: Real Worker smoke and P2–P4 end-to-end verification

**Files:**

- Create: `magnet-platform/native/test-fixtures/README.md`
- Modify: `magnet-platform/infra/docker-compose.yml`
- Modify: `magnet-platform/native/README.md`
- Create: `magnet-platform/native/test-fixtures/verify_worker.ps1`

- [ ] **Step 1: Generate a deterministic legal fixture**

Run:

```powershell
python native/test-fixtures/create_v1_fixture.py native/test-fixtures/out
```

Expected files:

- `native/test-fixtures/out/payload.txt`
- `native/test-fixtures/out/payload.torrent`

The script must generate a fixed payload and correct SHA-1 piece hashes; it must not download external content.

- [ ] **Step 2: Build and run the Worker with a shared data root**

Run:

```powershell
docker build -t magnet-libtorrent-worker:test native
docker run --rm --name magnet-worker-test -p 50051:50051 `
  -v "${PWD}\native\test-fixtures\out:/fixtures:ro" `
  -v magnet-worker-test-data:/data `
  magnet-libtorrent-worker:test
```

Before AddTorrent, copy the generated `payload.txt` into the host directory that is bind-mounted as `/data/resources/<resourceId>/payload.txt`; then use the Java gRPC adapter smoke test or generated client to call Health, AddTorrent with `/data/resources/<resourceId>`, UpdateFilePriorities, GetSnapshot, SaveResumeData, Pause, Resume, and RemoveTorrent. This validates the real Worker’s piece verification/recovery path without relying on an external tracker or unlicensed content.

- [ ] **Step 3: Verify restart and corruption behavior**

Stop the Worker after saving resume data, restart it with the same named volume, re-add the resource with the saved resume bytes, and assert that the first snapshot reflects the actual file state. Corrupt one payload byte, restart, and assert that libtorrent reports lower verified progress before the task can return to COMPLETED.

- [ ] **Step 4: Verify A/B shared Session behavior**

Run the API against the same shared storage root and create two users/tasks for one resource. Assert through the Worker test hook/log or Session repository that only one `engine_session` row exists, the demand union contains both selected files, and removing A leaves B downloading.

Run the complete verification suite:

```powershell
cd D:\release\magnet\magnet-platform
mvn test
cd web
npm run build
```

Expected: Maven exit code 0 with no failed tests, Web build exit code 0, Worker smoke exit code 0, and the documented P0–P4 scenarios all evidenced by test output or API assertions.

## Task 10: Update delivery documentation and perform plan self-review

**Files:**

- Modify: `magnet-platform/native/README.md`
- Modify: `magnet-platform/README.md` if present; otherwise create `magnet-platform/README.md`
- Modify: `doc/superpowers/specs/2026-10-22-p0-p4-delivery-design.md` only when an implementation fact changes the approved design

- [ ] **Step 1: Document exact local startup**

Document the required order: `docker compose up -d mysql redis`, build/run Worker with the shared `/data` mount, set `MAGNET_GRPC_ENABLED=true`, start the Spring Boot API, then start the Web Vite dev server. Include the exact health check and migration command used by the final verification.

- [ ] **Step 2: Mark only verified milestones**

Add a small delivery matrix with P0–P4 and links to tests/commands. Leave P5–P7 explicitly marked “未纳入本次交付”; do not claim real Worker completion until Task 9 has fresh passing evidence.

- [ ] **Step 3: Self-review the plan against the design**

Check every design requirement against Tasks 1–9, search this plan for unfinished markers and vague instructions, and verify all referenced class/method names match the current code or an earlier task. Fix plan inconsistencies before implementation begins.
