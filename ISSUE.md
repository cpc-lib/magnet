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

## 范围边界

本轮仅实现同宿主机 Worker 进程/容器 HA。跨主机、宿主机/数据库/存储 HA 与 HTTP 入口切换未实现，
详见 HANDOFF 和 README。冻结旧进程持有锁时接管等待，不能以强行双写换取固定恢复时限。
