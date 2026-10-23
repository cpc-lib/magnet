# ISSUE.md — 已知问题与技术债

## ✅ 已修复 | P5 缺少真实物理 fencing 与可靠接管
- **发现日期**: 2026-10-22
- **严重级**: 高
- **现象**: 多 Worker 可同时接受同一资源；旧命令、失联控制面和 Resume 旧计数使接管不安全。
- **复现**: `TaskServiceTest.leaseRenewFailureFencesSessionStopsWriting`；旧镜像运行 `python native/tests/test_ha.py WorkerHaTest.test_two_workers_cannot_write_same_resource` 会失败（两个 Worker 均接受）。
- **根因**: DB 租约草稿没有 native 磁盘隔离；过期续租和失败 RPC 仍被当成成功；异步磁盘删除未经确认。
- **修复**: `99c1586`（`feat(ha): fence same-host workers and recover expired leases`）：MySQL UTC 租约与 Epoch、共享 Linux flock 和持久代次、看门狗退出、RPC 错误传播、租约内回写/GC、物理锁内完成目录清理、恢复位图失效与重校验。
- **验证**: `mvn test`：113 项，0 失败/错误/跳过；`cd web; npm run build` 成功。`docker build -t magnet-libtorrent-worker:ha-test native` 后运行 `python native/tests/test_ha.py`：9 项真实双 Worker 回归全部通过（含删除确认丢失、冻结、崩溃与坏块恢复）。审查中发现的高优先级问题均已修复。

## ✅ 已修复 | 中文 SSE 断连仍触发 ErrorResponse 转换异常
- **发现日期**: 2026-10-23
- **严重级**: 中
- **现象**: 日志出现 `Failure in @ExceptionHandler` 和 `No converter for ErrorResponse with preset Content-Type text/event-stream`。
- **复现**: `GlobalExceptionHandlerTest.localizedIoOnEventStreamDoesNotWriteJson`、`localizedIoAfterStreamCommitPreservesWrittenEvents`；改动前新增的 6 个回归用例均失败。
- **根因**: 原实现依赖 Spring 对特定异常类型/英文消息的断连识别，漏掉 Windows 中文原生 IOException；统一错误体未显式设置 JSON 类型，也未检查响应已提交。
- **修复**: `7802b02`：SSE IO 按响应类型处理；已提交响应不追加错误体；未提交的业务错误显式指定 JSON；send IOException 后不再竞争调用 complete。
- **验证**: `mvn test` 共 120 项全部通过，无跳过；`cd web; npm run build` 成功。真实 Tomcat TCP RST 测试在全量及额外两次定向执行均通过，确认发送线程退出且没有转换/ExceptionHandler 二次错误。下列容器回调行为仍单独跟踪。

## ✅ 已修复 | 暂停后恢复下载覆盖开始时间
- **发现日期**: 2026-10-23
- **严重级**: 中
- **现象**: 用户恢复下载后，任务开始时间变成此次恢复的时间。
- **复现**: `TaskServiceTest.pauseResumePreservesOriginalStartedAt`；修复前断言原开始时间失败，实际值被替换成调度时刻。
- **根因**: 恢复下载重新入队，公共 QUEUED → STARTING 调度路径无条件调用 withStartedAt(now, now)。
- **修复**: `2dcad2b`：开始时间为空时才写入当前时间，否则保留已有值。不回写历史上已被覆盖且无法确定的首次时间。
- **验证**: 复现测试先失败后通过；`TaskServiceTest` 28 项全部通过（含首次启动及连续两轮暂停/恢复）；全量 `mvn test` 121 项通过，0 失败/错误/跳过；`cd web; npm run build` 成功。

## 🟡 调查中 | Tomcat TCP reset 后完成回调偶发缺失
- **发现日期**: 2026-10-23
- **严重级**: 低
- **现象**: 真实 TCP RST 测试中，发送已抛 IOException 并退出，但 onCompletion 偶发不触发，同时 Tomcat 输出 INFO 级 RecycleRequiredException；没有出现 JSON 转换二次异常。
- **复现**: `SseDisconnectIntegrationTest.tcpResetStopsSenderWithoutSecondaryConversionError`；额外等待 onCompletion 8 秒曾失败。
- **根因**: 待查；当前 Spring 6.1.14 / Tomcat 10.1.31 的断连生命周期行为，不能仅靠 onCompletion 清理发送线程。类似上游报告见 https://github.com/spring-projects/spring-framework/issues/31140 ，尚未确认同一根因。
- **修复**: 未修复容器回调行为；当前任务发送线程在 IOException/onError/onCompletion 三条路径均停止，不依赖完成回调退出。依赖升级应单独验证。

## ✅ 已修复 | P6 初版缺少真实 aria2 下载与导出验收
- **发现日期**: 2026-10-23
- **严重级**: 高
- **现象**: Mock 生命周期通过但真实首次启动失败；Docker 宿主路径/容器路径混用，输出名可能与导出元数据不同；RPC 失败被忽略或变为空快照，默认 Mock 会模拟进度。
- **复现**: `Aria2DownloadIntegrationTest` 首次真实运行无法创建下载；`Aria2ClientTest.http400PreservesStructuredRpcErrorCode` 等新增失败回归。
- **根因**: 仅 Mock 验证遗漏 aria2 HTTP 400 JSON-RPC 错误；未区分 daemon 路径，未指定 out，默认自动改名；未确认停止即记录成功；控制面 FAILED 状态与有效租约 SQL 互斥。
- **修复**: `e0f754e`：补齐路径/文件名、实际断点、显式 Mock、RPC 错误传播、可续租失败处理/显式重试和确认停止后的宿主机 GC；新 GID 初始暂停，只有活跃需求才恢复。
- **验证**: 新增回归先失败后通过；`mvn test` 共 170 项通过，0 失败/错误/跳过，包含真实 HTTP/HTTPS 的暂停、暂停/下载中重启 daemon、非零 Range 续传、源文件/手动导出 16 MiB 字节一致及真实 503 后显式重试。`cd web; npm run build` 成功；无 HTTP 密钥时 `docker compose -f infra/docker-compose.yml config --profiles` 成功。只操作隔离测试容器和临时存储。
- **合并复测**: 2026-10-23 已快进合并至本地 main（`6fd20fc`）；`mvn test` 与前端构建成功，本次 Docker 不可用导致 7 项集成测试跳过，未宣称此次重复执行了实机用例。

## ✅ 已修复 | aria2 运行镜像未实际构建
- **发现日期**: 2026-10-23
- **严重级**: 中
- **现象**: 本地缺少 Compose 所需的 `magnet-aria2:latest`，aria2 无法直接使用运行镜像。
- **复现**: `docker image ls` 无 `magnet-aria2:latest`；默认 `docker compose -f infra/docker-compose.yml config --services` 不包含 aria2。
- **根因**: aria2 在可选 http profile 中，默认构建不会包含；此前构建的 acceptance 测试标签不能替代 latest 运行标签。
- **修复**: `e6c4b30`：已通过 `docker compose -f infra/docker-compose.yml --profile http build aria2` 构建真实 latest，README 增加独立构建与版本检查步骤；测试增加 `MAGNET_TEST_SOURCE_HOST`，解决当前 VPN/TUN 地址与 Docker 网桥冲突导致的夹具连接失败。
- **验证**: `docker image inspect magnet-aria2:latest` 成功，镜像 ID `sha256:e133cca40e01d5df61a0c8a828d3636cb679f5c6b4f09a44c16f6154d82a49e8`；无网络临时容器 `--version` 成功，aria2 1.36.0 支持 HTTPS。
- **回归**: 指定当前可达宿主 IPv4 后 `mvn test` 170 项全部通过，0 失败/错误/跳过；真实 aria2 3 项通过（87.92 秒）；`cd web; npm run build` 成功。未修改系统网络、应用代理或证书验证。

## 范围边界

P5 仅实现同宿主机 Worker 进程/容器 HA。跨主机、宿主机/数据库/存储 HA 与 HTTP 入口切换未实现，
详见 HANDOFF 和 README。冻结旧进程持有锁时接管等待，不能以强行双写换取固定恢复时限。
P6 本轮按用户确认仅交付 HTTP/HTTPS，aria2 单 API 控制面；FTP/Metalink 及 aria2 物理 fencing 后续单列。
