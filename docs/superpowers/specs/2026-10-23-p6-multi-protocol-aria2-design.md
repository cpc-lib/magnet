# P6: 多协议与 aria2 引擎集成设计说明

> 基线：`docs/superpowers/specs/java-multiuser-bittorrent-spec.md` §2、§7、§17、ADR-002。
> 范围：HTTP/HTTPS 直链下载扩展，接入 aria2 JSON-RPC 引擎；BT 协议保持 libtorrent 独占（不与 aria2 混用）。

---

## 一、架构定位与职责边界

1. **引擎分工（ADR-002）**：
   - `LIBTORRENT`（C++ gRPC Sidecar）：独占所有 BitTorrent 协议（V1、V2、Hybrid、Magnet、.torrent）。
   - `ARIA2`（JSON-RPC Sidecar / Java HTTP Client）：负责 HTTP/HTTPS 单/多连接分段下载与断点续传。
   - 严禁让 aria2 与 libtorrent 对同一 BitTorrent 资源混用或写入同一目录。

2. **统一用户交互与调度流（规格 §4.2、§7）**：
   - 统一入口：`POST /api/v1/parses` 接收 `http://` 或 `https://` 链接，`InputType` 增加 `HTTP`。
   - 统一资源与文件元数据：探活（HEAD / Range Probe）获取文件名与 Content-Length，保存为 `TorrentResource`（`version_type = "HTTP"`, `canonical_key = "http:" + sha256(url)`）与 1 个 `TorrentFileInfo`（`file_index = 0`），解析状态直接为 `READY`。
   - 统一任务状态机：创建任务（`POST /api/v1/tasks`）、暂停、继续、重试、删除、SSE 事件流、完成对账、手动导出（`POST /tasks/{id}/exports`）与 GC 引用回收完全复用现有模型。
   - 动态引擎路由：`TaskService` 与 `ReferenceGcService` 根据资源版本（`versionType == VersionType.HTTP`）或 Session 记录的 `engine_type`（`ARIA2` vs `LIBTORRENT`）分发给对应 `DownloadEngine` 实现。

---

## 二、aria2 引擎集成（download-engine-aria2）

1. **接口契约（DownloadEngine SPI）**：
   - `kind()`: `EngineKind.ARIA2`。
   - `capabilities()`: `EngineCapabilities(bittorrentV1=false, bittorrentV2=false, hybrid=false, http=true, selectiveFiles=false, resumable=true)`。
   - 会话 ID 与 GID 映射：`engineSessionId == resourceId`（UUID 字符串）。aria2 GID 为 16 位 16 进制字符串，采用 `resourceId` 的前 16 位十六进制（`resourceId.replace("-", "").substring(0, 16)`）确定性映射，支持重启幂等识别。

2. **JSON-RPC 通信协议**：
   - 传输层：Java 21 原生 `java.net.http.HttpClient` 发送 POST JSON 请求到 `magnet.engine.aria2.rpc.endpoint`（默认 `http://localhost:6800/jsonrpc`）。
   - 鉴权：支持配置 `magnet.engine.aria2.rpc.secret`，携带 `token:<secret>` 参数。
   - 核心 RPC 方法映射：
     - `createOrAttach`: 若 aria2 中已存在对应 GID 则 attach，否则调用 `aria2.addUri([url], { dir, out, gid, pause: false })`。
     - `pauseSession`: 调用 `aria2.pause(gid)`。
     - `resumeSession`: 调用 `aria2.unpause(gid)`。
     - `snapshot`: 调用 `aria2.tellStatus(gid)`，解析 `completedLength`, `totalLength`, `downloadSpeed`，映射到 `EngineSnapshot`。
     - `removeSession`: 调用 `aria2.remove(gid)` 与 `aria2.removeDownloadResult(gid)`。
     - `available()`: 健康检查 `aria2.getVersion`。

3. **双模式支持与测试隔离**：
   - `Aria2JsonRpcEngine`：当 `magnet.engine.aria2.rpc.enabled=true` 时激活，连接真实 aria2 守护进程。
   - `MockAria2Engine`：当 `magnet.engine.aria2.rpc.enabled=false` 时激活（默认），内存中模拟任务流转与进度推进，保障单元测试和 CI 环境不依赖外部进程即可全绿。

---

## 三、多用户复用与数据一致性

1. **多用户同 URL 复用**：
   - 统一资源标识：相同 HTTP URL 生成一致的 `canonical_key = "http:" + sha256(normalizedUrl)`。
   - 多个用户提交相同 URL 获得同一 `resource_id`。
   - 多个用户各自创建 `download_task`，共享同一个 `engine_session(resource_id, ARIA2)`。
   - 需求聚合：只有所有活跃用户的需求均清空时，才调用 `aria2.pause`；任一用户恢复时恢复下载。

2. **完成与两轮快照复核**：
   - 当 aria2 报告已完成（`completedLength == totalLength`），第一 tick 推进任务状态到 `VERIFYING`；
   - 第二 tick 快照复核一致后，正式推进到 `COMPLETED` 并写入 `completed_at`。

3. **安全存储与导出**：
   - 文件下载至 `STORAGE_ROOT/resources/<resourceId>/<filename>`。
   - 导出通过 `FileDownloadStorage.resolveResourceFile` 访问，严格执行路径防穿越与软链接检查。
