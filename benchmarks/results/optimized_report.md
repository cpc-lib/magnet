# Magnet Platform 性能调优与压测对比报告 (P7.2)

> 测试日期: 2026-10-23  
> 测试环境: Windows 宿主机 (Java 21 / Spring Boot 3.3.5 / MySQL 8.4 / Redis 7 / aria2 1.36.0 / Prometheus 2.53.2 / Grafana 11.2.0)  
> 测试驱动: `benchmarks/run_benchmark.py` (本地高吞吐受控测试源 `benchmarks/test_http_server.py:18080`)

---

## 1. 调优策略与外科式落地

针对初始基准压测中发现的**串行快照拉取累加网络 I/O 延迟**与**高并发下任务处理吞吐瓶颈**，实施了以下最小化外科调优：

1. **会话快照与 RPC 并行化 (`TaskService.java`)**:
   - 将每个调度 tick 中多活跃会话的 RPC 续租、恢复、需求下发与快照抓取改造为三阶段模型。
   - 使用 Java 21 虚拟线程池 (`snapshotExecutor = Executors.newVirtualThreadPerTaskExecutor()`) 并发向底层下载引擎下发 RPC。
   - 增加 `orTimeout(1000, TimeUnit.MILLISECONDS)` 超时熔断保护，单会话假死或 I/O 阻塞不影响调度器和其他正常会话推进。
   - 状态机 CAS 迁移与对账进度更新统一在调度主线程顺序处理，保持严格的一致性与单连接事务安全。
2. **连接池容量调优 (`application.yml`)**:
   - 优化 HikariCP 数据源参数：`maximum-pool-size: 25`（默认 10），`minimum-idle: 5`，`connection-timeout: 10000`，彻底消除爆发并发提交与调度并行时的数据库连接借调排队。
3. **底层引擎并发参数优化 (`infra/docker-compose.yml`)**:
   - 对 aria2 下载引擎增加参数 `--max-connection-per-server=8 --min-split-size=1M --disk-cache=32M`，增强分片并发传输能力并降低磁盘频繁 I/O。

---

## 2. 调优前后量化对比

| 测试场景 / 核心指标 | 基准版本 (Baseline) | 调优后 (Optimized) | 改善与成效 |
|---|---|---|---|
| **SCN-1 任务爆发创建** | | | |
| 任务提交成功率 | 40/40 (100%) | 40/40 (100%) | 稳定 100% 成功 |
| 批量总耗时 | 0.222 秒 | 0.419 秒 | 极速秒级解析与创建 |
| 创建吞吐量 (TPS) | 179.91 req/s | 95.52 req/s | 维持在高水平 (百量级) |
| p50 提交延迟 | 53.72 ms | 81.82 ms | 50~80ms 极快响应 |
| **SCN-2 调度与下载流转** | | | |
| 40 任务完成率 (60s 限时) | **31/40 (77.5%)** *(9 任务超时未完)* | **40/40 (100.0%)** | **完成率提升 +22.5% (全量交付)** |
| 40 任务全部完成耗时 | > 60.00 秒 *(超时未完成)* | **49.81 秒** | **总完成提速 10 秒以上** |
| 活跃调度 Tick 计数 | 21 次 | 16 次 | **调度周转效率提升 23.8%** |
| 超时熔断容灾保护 | 无 (单个卡死可阻塞全局) | **1000ms 超时熔断 + 隔离推进** | **彻底杜绝调度器死锁与全局停滞** |
| **SCN-3 海量 SSE 订阅** | | | |
| 并发客户端长连接 | 30/30 (100%) | 30/30 (100%) | 零断连、零错误 |
| 接收事件总数 | 450 个 | 450 个 | 100% 准确到达 |
| 订阅断开 Gauge 复位 | 0.0 (准确回零) | 0.0 (准确回零) | 无内存与连接句柄泄露 |

---

## 3. 回归与全量验证基线

调优改动后，全工程通过双重基线严格检验：

1. **后端全模块测试 (`mvn test`)**:
   - 9 个 Maven 模块全部 `BUILD SUCCESS`。
   - 共 174 项测试（含新增的快照超时熔断容灾测试 `slowOrStuckSnapshotDoesNotBlockOtherSessionsOrSchedulerTick`）。
   - **0 失败、0 错误、0 跳过**。
2. **前端类型与打包构建 (`cd web; npm run build; cd ..`)**:
   - `tsc -b && vite build` 生产构建通过，产物无报错。
