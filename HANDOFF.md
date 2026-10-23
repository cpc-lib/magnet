# HANDOFF — Java 多用户 Magnet / BitTorrent 下载平台

> 最后更新：2026-10-22 | 当前阶段：**P0-P4 全部完成并通过全量验证（包含 Docker Worker Smoke 与 Testcontainers API 集成测试）** | 分支：`main`

---

## 一、项目概览

| 维度 | 内容 |
|---|---|
| **定位** | Java 21 + Spring Boot 3 多用户下载管理平台（BT V1/V2/Hybrid + HTTP(S) 预留） |
| **控制面** | Java 21 + Spring Boot 3.3 + MyBatis + Flyway (V1-V4) + MySQL 8.4 + Redis 7 + gRPC/Protobuf |
| **引擎侧** | C++ libtorrent-rasterbar 2.0.8 Sidecar Worker（gRPC）；HTTP(S) 预留 aria2 SPI 占位 |
| **前端** | React 18 + TypeScript + Vite + Ant Design |
| **规格基线** | `doc/superpowers/specs/java-multiuser-bittorrent-spec.md` |
| **交付计划** | `doc/superpowers/plans/2026-10-22-p0-p4-delivery-plan.md` |
| **运行栈** | JDK 21 / Maven 3.9 / Node 20+ / Docker Desktop (MySQL 8.4 + Redis 7 + libtorrent-worker) |

**模块地图**：

```
magnet/
├── server/
│   ├── download-engine-spi/        # SPI（DownloadEngine, EngineSnapshot, ResourceDescriptor, …）
│   ├── download-domain/            # 领域模型（DownloadTask, TorrentResource, TaskStatus, FileExport, …）
│   ├── download-application/       # 应用服务（ParseService, TaskService, ExportService, ReferenceGcService, AuthService）
│   ├── download-infrastructure/   # MyBatis + Flyway (V1-V4) + Redis + 文件存储实现
│   ├── download-engine-libtorrent/ # gRPC/Protobuf 适配器 + MockLibtorrentEngine
│   ├── download-engine-aria2/      # aria2 占位（P6 范围）
│   ├── download-scheduler/         # 定时调度（TaskDispatcher, ParseTimeoutSweeper, GcSweeper）
│   └── download-api/               # REST 控制器 + JWT 安全 + application.yml + 集成测试
├── native/
│   ├── proto/download_engine.proto # gRPC 契约接口
│   ├── libtorrent-worker/          # C++20 Sidecar 源码（libtorrent 2.0.8）
│   └── test-fixtures/              # 确定性 32 KiB V1 测试夹具与 Worker 端到端验证脚本
├── web/                            # React 18 + Ant Design 前端
├── infra/docker-compose.yml        # MySQL 8.4 + Redis 7 + libtorrent-worker
└── README.md                       # 根目录交付文档与快速启动指南
```

---

## 二、P0–P4 交付矩阵（与规格/计划对齐）

| 优先级 | 规格章节 | 计划 Task | 核心交付 | 状态 | 验证手段与结果 |
|---|---|---|---|---|---|
| P0 | §4.1 用户与租户 | Task 1 | User / JWT / 密码加密与租户隔离 | ✅ | `AuthServiceTest` 5/5 通过 |
| P0 | §4.2 资源解析 | Task 2 | Magnet / `.torrent` 解析，DHT 抓取回填 | ✅ | `ParseServiceTest` 14/14 通过 |
| P0 | §4.3 下载生命周期 | Task 4+5 | 逐文件选择、开始/暂停/恢复/重试/删除、CAS 防并发 | ✅ | `TaskServiceTest` 20/20 通过 |
| P0 | §7 实时状态与恢复 | Task 4+5 | 重启会话挂载、Resume 原子写入与加载恢复 | ✅ | `TaskServiceTest` recover 用例 + Worker 容器重启验证通过 |
| P0 | §8 文件完整性校验 | Task 3+4 | libtorrent `file_progress(piece_granularity)` 精确校验 | ✅ | Worker 侧 `main.cpp` + `verify_worker.ps1` 校验值 `[32768]` 通过 |
| P1 | §5.3 多用户复用 | Task 5 | 同资源单会话，活跃需求按文件并集下发，用户暂停删除互不影响 | ✅ | `TaskServiceTest` + `ApiIntegrationTest.sharedSessionAcrossTwoUsersAndDemandUnion` 通过 |
| P1 | §10.2 引用计数 GC | Task 6 | 宽限期 + 二次引用核查 + 物理资源容错 GC | ✅ | `ReferenceGcServiceTest` 3/3 通过 |
| P4 | §10.1 数据安全 | Task 1+6 | DownloadStorage 路径穿越防护、软链接防逃逸、Resume 原子替换 | ✅ | `FileDownloadStorageTest` (6), `ExportServiceTest` (3) 通过 |
| — | **Task 7** | 集成测试 | REST API 层真实 MySQL Testcontainers 跨租户与导出流式读取集成测试 | ✅ | `ApiIntegrationTest` 3/3 用例全通过（无 skip） |
| — | **Task 8** | Web 详情页 | `TaskDetailPage.tsx`：文件级进度条、READY 导出链接、状态机禁用逻辑、SSE 同步 | ✅ | `web` 生产打包成功（`tsc -b && vite build`，0 警告） |
| — | **Task 9** | Worker Smoke | 真实 Docker C++ Worker 容器端到端验证（生命周期 + Resume 恢复 + 坏块检测） | ✅ | `verify_worker.ps1` 全项通过（`file_verified_bytes` 精确匹配） |
| — | **Task 10** | 交付文档 | 根目录 `README.md`、`native/README.md`、`native/test-fixtures/README.md`、本 `HANDOFF.md` | ✅ | 交付矩阵对齐，P5–P7 明确标注未纳入 |

---

## 三、当前构建与自动化测试验证状态

### 1. 后端全模块测试（`mvn test`）

全 9 个模块，**94 个测试用例全部通过，0 失败，0 跳过**：

```
[INFO] Reactor Summary for magnet 0.1.0-SNAPSHOT:
[INFO] 
[INFO] magnet ............................................. SUCCESS
[INFO] download-engine-spi ................................ SUCCESS
[INFO] download-domain .................................... SUCCESS [38 tests]
[INFO] download-application ............................... SUCCESS [45 tests]
[INFO] download-infrastructure ............................ SUCCESS [ 8 tests]
[INFO] download-engine-libtorrent ......................... SUCCESS
[INFO] download-engine-aria2 .............................. SUCCESS
[INFO] download-scheduler ................................. SUCCESS
[INFO] download-api ....................................... SUCCESS [ 3 tests]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
```

模块级测试分布清单（合计 94）：

| 模块 | 测试类 | 用例数 | 状态 |
|---|---|---|---|
| `download-domain` | `MagnetParserTest` (10), `TorrentMetaParserTest` (13), `BencodeDecoderTest` (11), `FileExportTest` (2), `RuntimeStorageFieldsTest` (2) | 38 | ✅ 全绿 |
| `download-application` | `AuthServiceTest` (5), `ParseServiceTest` (14), `TaskServiceTest` (20), `ReferenceGcServiceTest` (3), `ExportServiceTest` (3) | 45 | ✅ 全绿 |
| `download-infrastructure` | `FileDownloadStorageTest` (6), `MyBatisRuntimeRepositoryTest` (2) | 8 | ✅ 全绿 |
| `download-api` | `ApiIntegrationTest` (3 个完整端到端场景：租户解析隔离、任务与安全导出物化/流式读取/删除隔离、双租户共享 Session 需求并集) | 3 | ✅ 全绿（Testcontainers MySQL 8.4 真实驱动） |

### 2. 前端构建（`npm run build`）

```powershell
cd web
npm run build
```

产物输出：`dist/` 生产 bundle，Vite 编译耗时 ~5.9s，TypeScript 类型检查通过无报错。

### 3. C++ libtorrent Worker 容器端到端 Smoke 验证（`verify_worker.ps1`）

```powershell
powershell -ExecutionPolicy Bypass -File native/test-fixtures/verify_worker.ps1
```

输出记录：
```
==> [Step 1] Generating deterministic V1 fixture...
Generated out\payload.txt (32768 bytes) and out\payload.torrent (118 bytes)
==> [Step 2] Building magnet-libtorrent-worker:test image...
==> [Step 3] Generating Python gRPC stubs...
==> [Step 4] Running RPC lifecycle & piece verification checks...
[PASS] Health: ok=True worker=local-worker-1 libtorrent=2.0.8
[PASS] Path boundary check: rejected /etc/escape (storage_path must stay under the Worker download root)
[PASS] GetSnapshot verified piece_granularity: state=ACTIVE verified=32768 file_verified_bytes=[32768]
[PASS] SaveResumeData: bytes=1068 version=1
[PASS] Pause / Resume lifecycle
==> [Step 5] Restarting Worker container to verify resume recovery and corruption detection...
[PASS] Recovery with resume_data: file_verified_bytes=[32768]
[PASS] Corruption detected on piece #1: verified_bytes dropped to [16384] (1 of 2 pieces valid)
[PASS] RemoveTorrent(remove_unreferenced_data=True) deleted underlying file
==> ALL WORKER SMOKE & RECOVERY VERIFICATIONS PASSED!
```

---

## 四、工作区变更清单（待提交）

所有变更均在工作区中，状态清洁、构建全绿：

### 新增文件
1. `README.md` — 根目录全景交付文档与本地启动指南
2. `native/test-fixtures/create_v1_fixture.py` — 确定性 32 KiB V1 夹具生成器
3. `native/test-fixtures/verify_worker.ps1` — 容器端到端生命周期与坏块重检验证脚本
4. `native/test-fixtures/README.md` — 夹具与验证文档
5. `server/download-application/src/test/java/com/magnetplatform/application/ExportServiceTest.java` — 导出物化、路径防逃逸与越权单测 (3 用例)
6. `server/download-application/src/main/java/com/magnetplatform/application/ReferenceGcService.java` — 引用计数与 GC 候选生命周期服务
7. `server/download-application/src/test/java/com/magnetplatform/application/ReferenceGcServiceTest.java` — GC 宽限期与二次核查单测 (3 用例)
8. `server/download-scheduler/src/main/java/com/magnetplatform/scheduler/GcSweeper.java` — GC 定时调度器

### 修改文件（按模块）
- **根配置与基础设施**：
  - `pom.xml`：为 `maven-surefire-plugin` 添加 `api.version=1.44` 兼容 Docker Desktop 29.x
  - `.gitignore`：忽略 `native/test-fixtures/out/` 与 `.codex/`
  - `infra/docker-compose.yml`：为 `libtorrent-worker` 增加 `build: context: ../native` 并注明宿主 `STORAGE_ROOT` 绑定关系
- **native Worker**：
  - `native/README.md`：更新夹具验证命令、目录树与支持的 RPC 表
- **server/download-api**：
  - `server/download-api/src/main/resources/application.yml`：增加 GC 宽限期与调度延迟配置
  - `server/download-api/src/test/java/com/magnetplatform/api/ApiIntegrationTest.java`：切换至 `JdkClientHttpRequestFactory`，新增多租户任务/导出物化流式读取隔离测试与双租户共享 Session 需求并集测试
- **server/download-application**：
  - `server/download-application/src/main/java/com/magnetplatform/application/ExportService.java`：增加选中文件校验未完成时的拦截检查
  - `server/download-application/src/main/java/com/magnetplatform/application/TaskService.java`：路径清洗使用 `PathSanitizer` 统一拦截目录穿越与绝对路径，抛出 `INVALID_PATH` (HTTP 422)
  - `server/download-application/src/test/java/com/magnetplatform/application/TaskServiceTest.java`：断言对应路径越界错误码为 `INVALID_PATH`
- **server/download-infrastructure**：
  - `server/download-infrastructure/src/main/resources/mappers/DownloadTaskMapper.xml`：修复 `<update id="updateFileProgress">` 中未转义的 `<= -> &lt;=`
- **web 前端**：
  - `web/src/pages/TaskDetailPage.tsx`：增加逐文件进度条渲染、根据 `allowedActions` 规则精确禁用操作按钮、展示错误信息
  - `web/src/pages/ParseDetailPage.tsx`：创建任务成功后平滑重定向至任务详情页

---

## 五、关键架构与工程决策总结

1. **`engineSessionId == resourceId`**：同一资源在 libtorrent 引擎中全局唯一。Java 控制面在调度轮次中聚合所有活跃用户任务的文件下标并集下发给 Worker。
2. **按文件精确进度而非比例估算**：Worker 通过 `file_progress(piece_granularity)` 获取真实通过哈希校验的字节数，更新至 `download_task_file.verified_bytes`；任务总进度等于选中文件的精确已校验字节之和。
3. **完成状态二次确认**：任务在所有选中文件校验完毕后进入 `VERIFYING`，在下一轮调度快照确认无误后方转入 `COMPLETED` 并触发 `ExportService.materializeCompleted` 物化用户专属导出目录。
4. **物化导出防逃逸**：导出文件只允许通过 `resolveResourceFile` 读取，且要求严格校验源文件大小与目标路径无软链接逃逸；下载接口 `GET /api/v1/tasks/{id}/exports/{exportId}/content` 严格鉴权。
5. **GC 宽限期与引用核查**：无活跃任务且无未完成导出的资源标记为候选，经过 300s 宽限期后再次核查；确认无新引用产生后，方调用 Worker 清除会话并从磁盘彻底删除。
6. **Docker Desktop 29.x API 契约兼容**：Docker 29 弃用了老旧的 Docker API 1.24/1.32，通过在 Maven surefire 配置中显式传入 `api.version=1.44`，保证了 Testcontainers 容器化测试在 Windows 宿主机上无缝执行。

---

## 六、下一步建议

1. **执行 Git Commit**：将工作区中通过全量测试的改动打包提交一条 Conventional Commit（如 `feat: complete P0-P4 delivery with integration tests, worker smoke and task detail page`）。
2. **生产容器部署**：在生产环境可通过 `infra/docker-compose.yml` 一键拉起基础设施，设置生产环境变量与强密钥后运行 API 与前端。
