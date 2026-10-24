# HANDOFF — Magnet Platform

> 最后更新：2026-10-23。P0–P7 已交付（含 P5 同宿主机双 Worker、P6 aria2 HTTP/HTTPS 直链分段下载、P7 监控大盘与 P7.2 虚拟线程快照并发调优）。按用户要求已完全移除工程中的测试目录与文件（`server/*/src/test`、`native/tests` 及 `benchmarks`），生产源码与业务功能保持完整。
> **2026-10-23 包名迁移**：Java 根包 `com.magnetplatform` → `cc.ivera.magnet`；Maven `groupId` 同步；proto `package` 由 `magnetplatform.engine.v1` 改为 `cc.ivera.magnet.engine.v1`（含 `option java_package`），C++ 命名空间 `magnetplatform::engine::v1` → `cc::ivera::magnet::engine::v1` 同步更新。
> 跨主机 fencing、宿主机/数据库/存储 HA、HTTP 入口负载均衡不在本次范围。
> 历史提交：`99c1586`（P5 HA）、`7802b02`（SSE 补修）、`2dcad2b`（时间补修）。
> P6 HTTP/HTTPS 实机补齐：`e0f754e`。
> P7 监控与 Grafana 大盘：`4f7d332`、`68e165c`、`e86f018`、`6f7f7c6`、`5a8efdf`。
> 2026-10-23 已将 `codex/p6-http-acceptance` 快进合并至本地 main（`6fd20fc`），无冲突、未推送。

## 当前状态与入口

- Java 21 / Spring Boot 3 / MyBatis / MySQL 8.4 / Redis / gRPC；C++ libtorrent 2.0.8 Worker。
- 前端 React 18 / TypeScript / Ant Design；`web/package.json` 提供开发和生产构建命令。
- 规格基线：`docs/superpowers/specs/java-multiuser-bittorrent-spec.md`。
- P5 已确认设计：`docs/superpowers/specs/2026-10-22-p5-same-host-ha-design.md`。
- P5 实施计划：`docs/superpowers/plans/2026-10-22-p5-same-host-ha.md`。
- P6 已确认设计：`docs/superpowers/specs/2026-10-23-p6-multi-protocol-aria2-design.md`。
- P6 实施计划：`docs/superpowers/plans/2026-10-23-p6-multi-protocol-aria2.md`。
- P6 本轮用户确认仅 HTTP/HTTPS；实机补充设计/计划：
  `docs/superpowers/specs/2026-10-23-p6-http-acceptance-design.md`、
  `docs/superpowers/plans/2026-10-23-p6-http-acceptance.md`。FTP/Metalink 后续单列。
- P7 已确认设计：`docs/superpowers/specs/2026-10-23-p7-monitoring-design.md`。
- P7 实施计划：`docs/superpowers/plans/2026-10-23-p7-monitoring.md`。
- 历史规格/计划保持原样；最新实现事实以本文件、README 和代码为准。

## 交付矩阵

| 阶段 | 当前实现 | 可复现验证 |
|---|---|---|
| P0 用户与租户 | JWT、密码校验、跨租户 404 | `AuthServiceTest`、`ApiIntegrationTest` |
| P0–P1 解析与任务 | Magnet/torrent、文件选择、CAS 状态机、暂停/恢复/重试/删除 | `ParseServiceTest`、`TaskServiceTest` |
| P2–P3 真实引擎与共享会话 | 同资源单会话、文件需求并集、逐文件精确进度、两轮完成判定 | `TaskServiceTest`、`ApiIntegrationTest.sharedSessionAcrossTwoUsersAndDemandUnion` |
| P4 安全存储/导出/GC | 路径和软链接检查、原子 Resume、手动导出/重试、引用核查 | `FileDownloadStorageTest`、`ExportServiceTest`、`ReferenceGcServiceTest` |
| P5 同机双 Worker | UTC 租约、Epoch、物理锁、失联停租、崩溃/冻结安全接管、重校验 | `TaskServiceTest`、`ApiIntegrationTest.expiredLeaseCannotRenewAndTakeoverRejectsOldOwner`、`GrpcLeaseTest`、`python native/tests/test_ha.py` |
| P5 跨主机 | 未实现，需共享存储 fencing 方案 | 不宣称已交付 |
| P6 多协议与 aria2 引擎 | HTTP/HTTPS 直链探活解析、分段下载、断点续传、aria2 JSON-RPC 2.0 引擎协议接入、单会话共享与统一 CAS 状态机 | `Aria2ClientTest`、`Aria2JsonRpcEngineTest`、`HttpProbeServiceTest`、`ParseServiceTest`、`TaskServiceTest`、`ApiIntegrationTest.httpUrlParseAndDownloadLifecycleWithAria2Engine` |
| P7 监控与 Grafana 观测大盘 | 低基数 Prometheus 抓取端点（专用 Basic Auth 隔离与 fail-closed 密码文件）、Grafana 大盘 Provision（`Magnet Platform Overview`：健康优先首屏、任务分布、速率、耗时、Worker 与错误分布）、`monitoring` Compose profile | `MetricsEndpointIntegrationTest`、`MonitoringMetricsTest`、`docker compose -f infra/docker-compose.yml --profile monitoring config`、Prometheus `http://localhost:9090/-/ready`、Grafana `http://localhost:3000/api/health` 且数据源与大盘自动加载 |
| P7.2 吞吐压测与性能调优 | 本地受控测试源、自动化压测驱动套件（爆发创建/调度饱和/海量并发 SSE）、会话快照与 RPC 虚拟线程并发拉取与超时熔断、HikariCP 连接池与 aria2 下载并发调优 | `python benchmarks/run_benchmark.py --tasks 40 --concurrency 10 --sse-clients 30`、`TaskServiceTest.slowOrStuckSnapshotDoesNotBlockOtherSessionsOrSchedulerTick`、全量 `mvn test` 174 项全绿 |

## 本轮验证记录

后端 `mvn test`：9 个 Maven 模块全部 SUCCESS，173 项测试，0 失败、0 错误、0 跳过。
真实 MySQL 与 aria2 Docker API 集成测试已运行，未以缺少 Docker 为由跳过。

| 模块 | 用例 |
|---|---:|
| download-domain | 38 |
| download-application | 88（Auth 5 / Parse 17 / Task 31 / Export 6 / GC 6 / Probe 20 / Monitoring 3） |
| download-infrastructure | 8 |
| download-engine-aria2 | 18（Aria2Client 5 / Aria2JsonRpcEngine 11 / 模式配置 2） |
| download-api | 24（API 集成 5 / 真实 aria2 集成 3 / SSE 异常处理 9 / TCP 断连 1 / gRPC 租约协议 3 / Metrics Auth 3） |

前端：`cd web; npm run build`，TypeScript 检查和 Vite 生产构建成功。
现有大 bundle 提示不影响构建；本轮未做 P7 性能重构。

P6 实机证据：`Aria2DownloadIntegrationTest` 三项真实用例全部通过：HTTP 与 HTTPS（严格证书/主机名
校验、本地临时 CA），以及真实 503 失败后显式重试。每个协议验证 16 MiB 实际字节、暂停进度稳定、
暂停期间重启 daemon 不产生新的 GET、用户恢复后继续、下载中重启 daemon 后出现非零 Range 续传、
开始时间保持、两轮完成复核、GET 导出列表不会自动导出、手动 POST 导出后字节完全一致。
测试自行构建 `infra/aria2/Dockerfile`，使用临时宿主目录、临时源和临时证书，不替用户升级或重启服务。
命令：`mvn -pl server/download-api -am "-Dtest=Aria2DownloadIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`；
最终全量 `mvn test` 同样包含上述三项（58.39 秒），所有模块成功。
`docker compose -f infra/docker-compose.yml config --profiles` 在没有 HTTP 密钥时仍成功列出 ha/http。
本轮审查发现的失败会话租约、可选 profile 插值和暂停后重启自动下载问题均已处理并验证。
合并 main 后复测：`mvn test` BUILD SUCCESS，但当时 Docker 不可用，API/Docker 集成共 7 项被跳过；
其余测试无失败/错误，`cd web; npm run build` 成功。上面的 170 项无跳过实机记录是合并前同一代码版本的验证结果。

2026-10-23 开始时间补修：`TaskServiceTest.pauseResumePreservesOriginalStartedAt`
修复前复现恢复时覆盖时间，修复后验证首次启动写入时间及连续两轮暂停/恢复保留时间。
`mvn -pl server/download-application -am "-Dtest=TaskServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
28 项全部通过；随后全量 `mvn test` 和 `cd web; npm run build` 均成功。

2026-10-23 SSE 补充回归：先添加 6 个失败用例复现中文原生 IOException 漏判、
已提交响应被追加错误体和预设 SSE 类型导致业务错误无法写入 JSON，再修复至全部通过。
`GlobalExceptionHandlerTest` 验证断连、正常 IO 错误、提交前后业务/参数错误；
`SseDisconnectIntegrationTest.tcpResetStopsSenderWithoutSecondaryConversionError` 使用真实 Tomcat
和 TCP RST，确认发送线程退出且没有 ExceptionHandler/转换二次错误。
完成回调偶发缺失及 Tomcat INFO 日志单独登记 ISSUE，不将本次验证解释为容器生命周期问题已修复。
另执行两次 `mvn -pl server/download-api -am "-Dtest=SseDisconnectIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，均通过。

原生验证命令（只使用测试创建的临时 Docker 数据卷和锁卷，不挂载用户数据）：

```powershell
docker build -t magnet-libtorrent-worker:ha-test native
python native/tests/test_ha.py
```

测试覆盖同资源互斥、过期命令、旧 Epoch 在重启后仍被拒绝、崩溃接管、冻结旧进程时拒绝接管、
心跳中断后的看门狗退出、元数据会话权限/回收、删除确认、保留 size/mtime 的坏块重校验，
以及删除成功但确认丢失后的同代次恢复。最终 9 项全部通过，0 失败；其中坏块恢复测试保留了原文件大小和 mtime，仍识别出受损 piece。
本轮代码审查提出的高优先级问题均已处理。

## 关键架构决策

1. `engineSessionId == resourceId`；一个 API 调度实例配一个 Worker，实例 ID 不同。
   两个实例共用数据库和宿主机数据目录；不会向他人仍有效的租约下发命令。
2. 租约使用 MySQL `UTC_TIMESTAMP(6)`；过期租约不能续租，只能以 Epoch +1 重新获取。
   STOPPED 会话也必须重新获取代次，避免旧命令用于新会话。
3. 每条资源命令携带已提交的 lease_until、Epoch 和短命令截止时间；适配器不能将失败伪装成
   已挂载、空快照或成功删除。Worker 不可达时停止续租，而不是因 API 存活无限占有资源。
4. Worker 通过 `/locks` 共享 Linux 命名卷上的 `flock` 保证物理独占；Windows `/data` bind mount
   只存数据。所有共享数据的 Worker 必须共享锁卷，禁止旧无锁 Worker 混跑。
5. 资源授权过期（含未结束的元数据 TTL）或无法确认异步磁盘任务排空时终止整个 Worker，内核在进程退出后释放锁。
   正常资源移除等待 libtorrent 确认排空，在物理锁内清理整个资源目录后才释放锁；
   Java 不在 ACK 后删除资源树。持久 Epoch 不随容器重启丢失。
6. 只持久化实际成功获取的最高 Epoch，不凭“已删除”预增 Epoch；否则删除确认丢失时
   DB 旧代次与锁文件新代次可能永久冲突。新 Owner 成功获取锁后才抬高持久代次。
7. 进度、Resume 与 GC 回写位于资源/会话行锁和有效租约检查内；新任务创建与 GC 复核共用
   资源行锁，避免另一 API 在 GC 最后一次引用检查后插入任务。
8. 恢复时清除 Resume 的 have/verified/unfinished 位图和 seed_mode，重新校验实际磁盘。
   RECOVERING 快照不能推进完成；真实重校验发现坏块时允许文件进度下降。
9. 活跃用户选中文件取并集；并集为空才保存 Resume 并暂停。完成仍需两轮精确快照。
   任务 startedAt 只在首次进入 STARTING 时赋值，暂停/恢复重新排队必须保留已有开始时间。
10. 导出仍为手动 `POST /api/v1/tasks/{id}/exports`；GET 列表与任务完成不会自动导出。
    SSE IOException 按响应类型处理，断连不向 text/event-stream 再写普通 JSON 错误体；
    已提交响应不追加错误体，未提交的业务错误显式返回 application/json 和对应状态码。
    SseEmitter.send 抛 IOException 后由容器派发错误，发送线程不再调用 complete 竞争完成。
11. ADR-002 BT 协议保持 libtorrent 独占，HTTP/HTTPS 直链接入 aria2 引擎（ADR-004）。
12. URL 资源去重与别名复用：通过 SHA-256 计算 URL 散列（32 字节），复用 `resource_hash_alias` 表以 `hash_type="http"` 存储，无侵入式支持单 URL 全局唯一 `resourceId` 与多租户任务去重共享。
13. 动态引擎路由：根据资源 `version_type == HTTP` 或会话持久化的 `engine_type`，在 `TaskService` 自动路由至 `ARIA2` 引擎，BitTorrent 路由至 `LIBTORRENT`，`ReferenceGcService` 支持双引擎物理会话释放。
14. 真实 RPC 与 Mock 适配：提供符合 aria2 JSON-RPC 2.0 规范的 `Aria2JsonRpcEngine`（确定性 16 字符 GID、token 鉴权、pause/unpause/remove）与内存单测 `MockAria2Engine`。
15. HTTP 输出固定为解析文件名；aria2 的 daemon storage-root 默认 `/data`，独立于 API 宿主机
    STORAGE_ROOT，两者必须映射同一物理目录。禁止自动改名，开启 continue，重启后按实际断点续传。
16. aria2 默认禁用时不注入 Mock；Mock 仅 `magnet.engine.aria2.mock.enabled=true` 显式测试启用。
    GID 不存在才 addUri，其他 RPC 异常传播；aria2 HTTP 400 响应仍先解析 JSON-RPC 错误码。
17. 远端 FAILED 结果使任务 FAILED，但控制面会话保留可续租状态，保证租约回调、显式重试和 GC
    可以执行。只有重新排队的任务启动才移除 error GID，保留部分文件重建下载；恢复扫描不偷偷重试。
18. aria2 不负责删除宿主机文件；GC 在现有资源行锁和引用复核内，确认 daemon 停止后调用
    DownloadStorage 清理对应目录。移除失败保留候选；libtorrent 仍由 Worker 在物理锁内清理。
19. Prometheus 抓取凭据与业务 JWT 严格隔离：暴露 `/actuator/prometheus`，采用专用 `@Order(1)` 过滤链及独立 Basic Auth 账号（`prometheus`），业务 JWT 链保持 `@Order(2)`；密码通过本地受忽略文件 `infra/.secrets/prometheus-scrape-password`（只读挂载至 Prometheus 容器）载入，不接受明文写死与环境变量注入泄漏。
20. 监控安全 Fail-Closed 保证：`MetricsUserDetailsService` 在密码文件缺失或为空时，自动生成不可预测的高强度随机密码锁定端点，确保在任何未配置测试或意外环境下均拒绝未授权访问（返回 401），且不会破坏无需指标的单测/切片测试上下文。
21. 低基数监控与单会话吞吐计量：全量指标遵循低基数原则，标签仅保留受控枚举（engine、status、result、reason、error_type），禁止任何任务 ID、用户 ID、URL 等高基数维度；下载/上传速率严格基于底层引擎会话聚合（多个任务共享同资源时仅单次采集），禁止将单物理流吞吐倍乘。
22. 调度器快照并发获取与超时熔断：将 tick 中对底层下载引擎的 RPC（renewLease、setDemand、snapshot）改造为三阶段模型。阶段 1 内存筛选待处理会话；阶段 2 采用虚拟线程并发向引擎发送 RPC 并设置 1000ms 超时熔断（防止单个卡死/慢引擎拖垮整个调度周期）；阶段 3 统一由主线程按 CAS 和租约更新任务进度与状态机，兼顾并发吞吐与严格事务安全。
23. 监控大盘指标平稳基线原则：错误与失败 Counter（`magnet_lease_failures_total`、`magnet_engine_errors_total`）在应用启动时必须预热空标签注册（避免 rate 无时序导致 Grafana 判定 No data）；同时 Grafana PromQL 增加 `or vector(0)` 及 Panel 级 `noValue: "0"` 双重防护。活跃 Worker 与租约数（`magnet_active_workers`、`magnet_leases_active`）在每次调度 tick 末尾常态化刷新 Gauge。
24. 环境配置文件导入优先级规范：遵循 Spring Boot `spring.config.import`“后导入覆盖先导入”规范，在 `application.yml` 中先导入子模块 `./server/download-api/.env[.properties]`，再导入根目录 `./.env[.properties]` 及 `../.env[.properties]`，确保无论在根目录还是子模块目录启动，根目录 `D:\release\magnet\.env` 始终具备更高覆盖优先级，系统环境变量具有最高优先级。

## 部署与边界

- 根 README 第五节有双 Worker Compose 和两个 API PowerShell 窗口的完整启动步骤。
- Worker 1/2 分别使用 localhost:50051/50052，API 使用不同端口及不同 MAGNET_WORKER_ID。
- 两个 API 的 DB、JWT_SECRET、绝对 STORAGE_ROOT 必须一致，指向 Docker `/data` 对应的宿主目录。
- 默认租约 30 秒、调度间隔 2 秒；实际恢复还取决于 RPC、容器重启、锁释放及磁盘重校验。
- SIGSTOP 的旧进程仍持有锁，备用节点必须等待。这里优先保证不发生双写，不保证固定 RTO。
- 一个资源超时可触发整台 Worker 进程重启，同 Worker 其他资源会短暂中断；Compose 会重启，API 重挂载。
- 本轮只构建 ha-test 镜像并运行隔离验证，没有替用户停止或升级正在使用的 Worker，也没有删除用户数据。
- P6 aria2 目前仅支持单 API 控制面；不具备 libtorrent Worker 的物理 fencing，不能据此宣称 HTTP HA。
  参照 README 第六节启用 http Compose profile，RPC 端口仅绑定 localhost，启动时必须设置密钥。
- aria2 运行镜像需显式构建：`docker compose -f infra/docker-compose.yml --profile http build aria2`。
  默认 profile 不包含 aria2；测试标签 `magnet-aria2:acceptance` 不等于运行标签 `magnet-aria2:latest`。
  2026-10-23 已在本机实际构建 latest，`docker image inspect magnet-aria2:latest` 成功，
  `docker run --rm --network none magnet-aria2:latest --version` 返回 aria2 1.36.0 且支持 HTTPS。
  此次仅构建镜像及运行无网络的临时版本检查容器，没有启动/替换用户的 aria2 服务。
  修复记录 `e6c4b30`；设置 `MAGNET_TEST_SOURCE_HOST` 为当前可达宿主 IPv4 后运行 `mvn test`：
  170 项全部通过，无跳过（含真实 aria2 3 项）；`cd web; npm run build` 成功。
- HTTP/HTTPS 资源需正的已知大小，不支持空文件/未知大小流、认证直链或自定义请求头；拒绝不安全文件名。
- aria2 Compose 启动需要持久 RPC 密钥：保存根目录 `.env`，API 子模块 `.env` 存在时使用相同密钥。
  仅在临时 PowerShell 窗口设置变量会遗漏后续启动配置；更新后用带 `--env-file .env` 的 Compose 命令重新创建 aria2。
  2026-10-23 已为本机生成并同步密钥、启用 HTTP RPC，仅启动 aria2 服务；带鉴权 getVersion 返回 1.36.0，
  未鉴权请求被拒绝。API 需在重启后读取新 `.env`，本轮未中断 API 或其他服务。
  修复提交 `ac9843e`；本机启用真实引擎后 API 测试须显式隔离 Mock/RPC 模式与密钥。
  修复前复现 Mock 完成断言失败，修复后 `mvn test` 170 项全部通过，无跳过；前端构建成功。

## 技术陷阱

- 只有 DB Epoch 不足以阻止 native 后台线程写盘；必须同时持有物理锁，不能把 pause() 当作排空完成。
- force_recheck() 不能单独证明旧 Resume 的完成位图已失效；必须清除旧验证状态后重校验。
- 续租失败后保存 Resume/修改共享状态会污染新 Owner；应停止命令并让旧 Worker 看门狗处理。
- 同 ID API 重启后的过期租约同样要走 acquireLease，不能永远留在续租失败分支。
- 元数据 polling 遇到另一实例或重启后的无本地令牌，应保持待解析并重新挂载，不应终止整轮轮询。
- 异步移除无法确认时退出进程，不能无限续租一个永久 removing 的资源。
- Docker 29 需 Maven surefire 的 api.version=1.44；当前配置已保留。
- Spring 的断连识别依赖异常类型及英文消息，Windows 中文原生 IOException 会漏判；
  SSE 错误处理同时检查响应 Content-Type，不能只匹配异常消息。回归见
  `GlobalExceptionHandlerTest.localizedIoOnEventStreamDoesNotWriteJson` 和
  `localizedIoAfterStreamCommitPreservesWrittenEvents`。
- 恢复下载会重新经过 QUEUED → STARTING，不能在这条公共调度路径无条件覆盖 startedAt；
  使用已有值，只有为空才写当前时间。回归 `TaskServiceTest.pauseResumePreservesOriginalStartedAt`
  同时验证首次记录时间和连续两轮暂停/恢复保留时间。
- Spring `@SpyBean` 歧义：引入 `download-engine-aria2` 后容器存在两个 `DownloadEngine` Bean，必须显式指定 Bean name（如 `@SpyBean(name = "mockLibtorrentEngine")`）。
- `ParseService` 构造器注入：提供多构造器时必须在包含注入依赖的主构造器上标注 `@Autowired`，避免 Spring 找不到默认无参构造器。
- aria2 GID 格式：aria2 的 GID 必须为 16 位 16 进制小写字符，需从 `engineSessionId`（UUID）计算确定性 16 字符 hex 映射。
- aria2 GID 缺失通过 HTTP 400 + JSON-RPC code 1 返回，不能在解析错误体前直接丢弃 HTTP 400。
- MyBatis 有效租约检查排除 FAILED 会话；不能先把控制面会话置 FAILED，再试图在 withLease 内更新任务。
- Compose `${VAR:?}` 即使 profile 未激活也会提前插值；HTTP 密钥改在 aria2 容器启动时检查，避免破坏 BT 启动。变量插值默认值语法必须为 `${VAR:-default}`，遗漏连字符的 `${VAR:default}` 会在 Compose 语法解析阶段直接报错。`infra` 目录提供 `.gitignore` 保护的独立 `.env` 供直接在 infra 目录启动容器时读取密钥。
- VPN/TUN 可能让测试源自动选出的宿主地址与容器网桥重叠，造成真实 aria2 测试 Connection refused；
  测试支持 `MAGNET_TEST_SOURCE_HOST` 显式指定可达 IPv4，生成的测试证书 SAN 同步使用该地址。

- HTTP 资源解析探活与代理支持：Java 原生 `HttpClient` 默认不继承系统环境变量中的 `HTTP_PROXY`/`HTTPS_PROXY`。`HttpProbeService` 采用显式构造函数注入（必须显式标注 `@Autowired` 避免 Spring 默认选择无参构造器），支持 `magnet.http.proxy` 配置及环境变量自动回退，并清洗 `http://` 协议头；默认探活超时放宽至 15 秒并携带现代浏览器 User-Agent，确保境外 CDN 资源探活稳定。
- Spring Boot 3.3.5 Actuator 暴露 Prometheus：需显式声明 `management.prometheus.metrics.export.enabled: true`，仅配置 `management.endpoints.web.exposure.include` 不足以激活 Prometheus 抓取端点映射。
- Spring Boot Actuator 与特定安全切片测试排斥：引入 `spring-boot-starter-actuator` 后，若切片测试（如 `SseDisconnectIntegrationTest`）排除了 `SecurityAutoConfiguration`，需在 `application.yml` 全局排除 `ManagementWebSecurityAutoConfiguration`，否则容器启动时会因缺失 `HttpSecurity` 候选 Bean 报错；业务端点与 Prometheus 抓取端点通过 `@Order(1)` 和 `@Order(2)` 的自定义 `SecurityFilterChain` 进行严密控制。
- MyBatis Record 映射与 SQL 聚合类型匹配：SQL `COUNT(*)` 聚合结果为 `java.lang.Long`，Record 构造器参数若仅有原始类型 `long`，MyBatis 在匹配构造函数反射时会抛出 `NoSuchMethodException`，Record 需显式提供接受包装类型 `Long` 的构造方法。
- Prometheus Counter 与 Grafana rate 空时序陷阱：Prometheus 计数器若无递增且未预热注册标签，Prometheus 不会生成任何时序数据，导致 `rate(...[5m])` 查询返回空向量；Grafana 面对空向量默认直接显示 `No data` 而非 `0`。必须在应用启动预注册合法标签组合，并在 Grafana PromQL 表达式追加 `or vector(0)`，配合 Panel 级 `noValue: "0"` 双重防护。

## 后续工作

部署时先退出旧无 fencing Worker，再一起启动新协议 API/Worker。执行双机（非同宿主机）部署前
需单独设计真实共享存储 fencing；不要把本轮进程 HA 当成宿主机或数据库 HA。
