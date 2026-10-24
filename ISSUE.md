# ISSUE.md — 已知问题与技术债

## ✅ 已修复 | Grafana 大盘 Workers & Errors 面板缺少指标显示 No data
- **发现日期**: 2026-10-23
- **严重级**: 中
- **现象**: Grafana 监控大盘中 `Active Workers & Leases` 与 `Engine Errors by Type` 两个监控面板显示 `No data`。
- **复现**: 访问 `http://localhost:3000/d/magnet-platform-overview` 展开 `Workers, Leases & Errors` 折叠行。
- **根因**: 后端未暴露 `magnet_active_workers` 与 `magnet_leases_active` Gauge，且 `magnet_lease_failures_total` 与 `magnet_engine_errors_total` 错误 Counter 未预热注册与常态化打点；Grafana 在 Counter 无增量或 rate 遇空时直接返回空结果，引发 `No data`。
- **修复**: 在 `MonitoringMetrics` 增加 `magnet_active_workers`、`magnet_leases_active`，并预热注册错误 Counter，提供打点方法；在 `TaskService` 调度与错误路径实时刷新活跃 worker/租约并记录租约失败与引擎错误；同时在大盘 JSON 中补充 `or vector(0)` 与 `"noValue": "0"` 双重防护。
- **验证**: Prometheus 端点返回 `magnet_active_workers 1.0`、`magnet_leases_active 2.0`、失败与错误 Counter 为 0.0；Grafana API 与大盘加载正常渲染数值与平稳基线。

## ✅ 已修复 | 调度 tick 串行获取快照导致高并发时调度抖动与任务延迟完成
- **发现日期**: 2026-10-23
- **严重级**: 中
- **现象**: 压测基准中当存在 40 个活跃下载任务时，单次调度 tick 耗时逼近 2 秒（p95 达 1418ms），60 秒限时内仅能完成 31 个任务，剩余 9 个任务由于调度网络 I/O 累加未能及时完成。
- **复现**: `python benchmarks/run_benchmark.py --tasks 40 --concurrency 10`
- **根因**: `TaskService.pushDemandAndRefreshProgress` 对所有活跃会话顺序执行同步网络 RPC（`renewLease`、`setDemand`、`snapshot`），网络 RTT 串行累加；且单个慢会话可能拖垮整个调度周期。
- **修复**: 将会话快照与 RPC 拉取解耦为虚拟线程并发模型，并设置 1000ms 超时熔断保护，状态机流转与 DB CAS 保持在调度主线程原子推进；调优 HikariCP 最大连接数至 25，aria2 增加分片并发与磁盘缓存参数。
- **验证**: `benchmarks/results/optimized_report.md`：40/40 任务全部成功在 49.81 秒内完成（完成率由 77.5% 跃升至 100%），`mvn test` 174 项全绿，单测包含 `slowOrStuckSnapshotDoesNotBlockOtherSessionsOrSchedulerTick` 超时熔断验证。

## ✅ 已修复 | Compose 环境变量语法错误导致容器启动崩溃 (invalid interpolation format)
- **发现日期**: 2026-10-23
- **严重级**: 高
- **现象**: 在 `infra` 目录执行 `docker compose up -d` 报错 `invalid interpolation format for services.aria2.environment.MAGNET_ARIA2_RPC_SECRET. You may need to escape any $ with another $. ${MAGNET_ARIA2_RPC_SECRET:155bba96a8223156e12979c304022ce8c0d6a3f48d3af605721fa1a949b705d7}`，Compose 预解析失败，所有服务无法启动。
- **复现**: 在 `infra/docker-compose.yml` 中将环境变量写为 `${MAGNET_ARIA2_RPC_SECRET:secret}`（漏掉 `-`），执行 `docker compose up -d`。
- **根因**: Compose 文件变量插值语法中提供默认值必须为 `${VAR:-default}`，漏掉 `-` 会导致 Compose 解析阶段语法报错；同时在 `infra` 目录下直接运行 Compose 时默认查找当前目录 `.env`，若无 `.env` 且写死密钥则既破坏语法又易导致密钥外泄进 Git。
- **修复**: 还原 `infra/docker-compose.yml` 中规范的 `${MAGNET_ARIA2_RPC_SECRET:-}` 插值语法，并在 `infra/.env`（已被 `.gitignore` 忽略）中补齐本地持久化密钥；在 `infra` 目录下运行 `docker compose up -d` 基础服务与 `--profile http` 启动 aria2 均成功。
- **验证**: `cd infra; docker compose up -d` 成功启动 mysql、redis、worker-1；`docker compose --profile http up -d aria2` 成功启动 aria2，JSON-RPC `aria2.getVersion` 返回 1.36.0；`mvn test` 170 项全绿，无跳过/失败；前端构建通过。

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

## ✅ 已修复 | aria2 镜像已构建但容器启动后反复退出
- **发现日期**: 2026-10-23
- **严重级**: 高
- **现象**: Compose 创建 aria2 后容器 Restarting，日志 `MAGNET_ARIA2_RPC_SECRET: Set MAGNET_ARIA2_RPC_SECRET before starting aria2`。
- **复现**: 本机 `.env` 未配置 RPC 密钥时执行 `docker compose -f infra/docker-compose.yml --profile http up -d --no-build --no-deps aria2`。
- **根因**: 之前仅验证二进制版本，未验证 Compose 带鉴权的实际启动；文档依赖临时进程环境变量，本机根目录与 API 子模块 `.env` 均缺少 RPC 配置。
- **修复**: `ac9843e`：本机生成随机 RPC 密钥并同步两个已忽略的 `.env`，仅重新创建/启动 aria2；README 改为持久化配置与显式 env-file，`.env.example` 补充 aria2 配置项。API Mock 测试显式关闭真实 RPC，真实 daemon 测试显式关闭 Mock 并清空本地密钥继承，避免启用本机服务后污染测试。
- **验证**: 容器 Running，已鉴权 JSON-RPC getVersion 返回 1.36.0，未鉴权请求被拒绝；两个配置文件密钥一致且被 Git 忽略，未输出或提交密钥。
- **回归**: 本机启用真实 aria2 后，旧 Mock API 测试复现 COMPLETED/DOWNLOADING 断言失败；显式隔离后 `mvn test` 170 项全部通过，无失败/错误/跳过；`cd web; npm run build` 成功。现有 aria2 服务保持运行、重启计数 0。

## ✅ 已修复 | Actuator 引入后排除 SecurityAutoConfiguration 的测试抛 HttpSecurity 依赖缺失
- **发现日期**: 2026-10-23
- **严重级**: 中
- **现象**: 引入 `spring-boot-starter-actuator` 后，执行 `SseDisconnectIntegrationTest` 时报 `UnsatisfiedDependencyException: Error creating bean with name 'managementSecurityFilterChain' ... No qualifying bean of type 'HttpSecurity' available`，应用上下文加载失败。
- **复现**: 在包含 Actuator 的依赖下，运行排除了 `SecurityAutoConfiguration` 的 Spring Boot 测试。
- **根因**: Spring Boot Actuator 的 `ManagementWebSecurityAutoConfiguration` 在检测到 Spring Security 在类路径时自动尝试注入 `HttpSecurity`；而测试由于排除了 `SecurityAutoConfiguration`，导致容器内没有 `HttpSecurity` Bean。
- **修复**: commit `5a8efdf`；在 `application.yml` 中显式排除 `ManagementWebSecurityAutoConfiguration`。项目本身的 Prometheus 抓取端点由 `@Order(1)` 的自定义 `metricsSecurityFilterChain` 严密保护，无需 Actuator 默认安全过滤器链。
- **验证**: `mvn -pl server/download-api -am -Dtest=SseDisconnectIntegrationTest test` 成功通过，全量 `mvn test` 173 项测试全部通过。

## ✅ 已修复 | MyBatis Record 构造器在 COUNT(*) 映射时抛 NoSuchMethodException
- **发现日期**: 2026-10-23
- **严重级**: 中
- **现象**: 调度 tick 执行按状态聚合任务统计 SQL 时，MyBatis 抛出 `ReflectionException: Error instantiating class cc.ivera.magnet.application.TaskStatusEngineCount with invalid types (..., Long) or values ... Cause: java.lang.NoSuchMethodException`。
- **复现**: 在带有聚合查询结果的数据库上调用 `DownloadTaskRepository.countByStatusAndEngine()`。
- **根因**: SQL `COUNT(*)` 返回 `java.lang.Long`，而 Java Record `TaskStatusEngineCount` 仅声明了原始类型 `long`，MyBatis 反射寻找构造函数时无法将 `java.lang.Long` 自动开箱为 `long` 匹配构造器签名。
- **修复**: commit `e86f018`；在 `TaskStatusEngineCount` Record 中将字段调整为 `Long` 并提供重载构造器，Mapper XML 显式声明 `javaType="java.lang.Long"`。
- **验证**: `TaskServiceTest`、`MetricsEndpointIntegrationTest` 与全量测试均成功执行聚合查询，0 失败。

## 范围边界

P5 仅实现同宿主机 Worker 进程/容器 HA。跨主机、宿主机/数据库/存储 HA 与 HTTP 入口切换未实现，
详见 HANDOFF 和 README。冻结旧进程持有锁时接管等待，不能以强行双写换取固定恢复时限。
P6 本轮按用户确认仅交付 HTTP/HTTPS，aria2 单 API 控制面；FTP/Metalink 及 aria2 物理 fencing 后续单列。
