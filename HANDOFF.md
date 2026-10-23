# HANDOFF — Magnet Platform

> 最后更新：2026-10-23。P0–P4 已交付；P5 同宿主机双 Worker 故障接管已实现。
> 跨主机 fencing、宿主机/数据库/存储 HA、HTTP 入口负载均衡不在本次范围。
> 实现提交：`99c1586`。
> SSE 中文断连补修：`7802b02`。

## 当前状态与入口

- Java 21 / Spring Boot 3 / MyBatis / MySQL 8.4 / Redis / gRPC；C++ libtorrent 2.0.8 Worker。
- 前端 React 18 / TypeScript / Ant Design；`web/package.json` 提供开发和生产构建命令。
- 规格基线：`docs/superpowers/specs/java-multiuser-bittorrent-spec.md`。
- P5 已确认设计：`docs/superpowers/specs/2026-10-22-p5-same-host-ha-design.md`。
- P5 实施计划：`docs/superpowers/plans/2026-10-22-p5-same-host-ha.md`。
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
| P6/P7 | aria2 和监控/吞吐优化未纳入 | 未开工 |

## 本轮验证记录

后端 `mvn test`：9 个 Maven 模块全部 SUCCESS，120 项测试，0 失败、0 错误、0 跳过。
真实 MySQL Testcontainers API 集成测试已运行，未以缺少 Docker 为由跳过。

| 模块 | 用例 |
|---|---:|
| download-domain | 38 |
| download-application | 57（Auth 5 / Parse 15 / Task 27 / Export 6 / GC 4） |
| download-infrastructure | 8 |
| download-api | 17（API 集成 4 / SSE 异常处理 9 / TCP 断连 1 / gRPC 租约协议 3） |

前端：`cd web; npm run build`，TypeScript 检查和 Vite 生产构建成功。
现有大 bundle 提示不影响构建；本轮未做 P7 性能重构。

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
10. 导出仍为手动 `POST /api/v1/tasks/{id}/exports`；GET 列表与任务完成不会自动导出。
    SSE IOException 按响应类型处理，断连不向 text/event-stream 再写普通 JSON 错误体；
    已提交响应不追加错误体，未提交的业务错误显式返回 application/json 和对应状态码。
    SseEmitter.send 抛 IOException 后由容器派发错误，发送线程不再调用 complete 竞争完成。

## 部署与边界

- 根 README 第五节有双 Worker Compose 和两个 API PowerShell 窗口的完整启动步骤。
- Worker 1/2 分别使用 localhost:50051/50052，API 使用不同端口及不同 MAGNET_WORKER_ID。
- 两个 API 的 DB、JWT_SECRET、绝对 STORAGE_ROOT 必须一致，指向 Docker `/data` 对应的宿主目录。
- 默认租约 30 秒、调度间隔 2 秒；实际恢复还取决于 RPC、容器重启、锁释放及磁盘重校验。
- SIGSTOP 的旧进程仍持有锁，备用节点必须等待。这里优先保证不发生双写，不保证固定 RTO。
- 一个资源超时可触发整台 Worker 进程重启，同 Worker 其他资源会短暂中断；Compose 会重启，API 重挂载。
- 本轮只构建 ha-test 镜像并运行隔离验证，没有替用户停止或升级正在使用的 Worker，也没有删除用户数据。

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

## 后续工作

部署时先退出旧无 fencing Worker，再一起启动新协议 API/Worker。执行双机（非同宿主机）部署前
需单独设计真实共享存储 fencing；不要把本轮进程 HA 当成宿主机或数据库 HA。
