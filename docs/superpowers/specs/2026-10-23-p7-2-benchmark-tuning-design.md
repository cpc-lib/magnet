# P7.2 全链路吞吐压测与性能调优设计

> 状态：待确认，2026-10-23
>
> 依赖：P7.1 监控与 Grafana 大盘已交付，提供量化指标基准（Prometheus + Grafana）。

---

## 1. 目标

基于 P7.1 已落地的生产级监控指标体系，为 Magnet Platform 建立端到端可复现的压测体系与定向性能调优方案：

1. **可复现基准环境**：不依赖易波动的公网网络，提供完全受控的本地测试源（本地 HTTP 高速模拟源与本地 BT 种子测试件），支持持续、确定性地重复运行。
2. **全链路负载场景**：
   - **高并发任务提交与解析**：模拟多用户同时提交 HTTP/BT 资源，验证 API 与解析链路吞吐（TPS）。
   - **调度器高负载与批量 Session 处理**：在上百个活跃任务/会话同时存在时，评估调度 tick 延迟（p50/p95/p99）及会话快照收集效率。
   - **并发 SSE 实时状态推送**：评估多租户高并发客户端长连接监听时的系统 CPU、内存与连接稳定性。
   - **大文件与多任务极限吞吐**：压测 aria2 与 libtorrent 在本地磁盘与容器环境下的并发下载带宽与导出性能。
3. **指标量化评估**：以 Prometheus 指标（`magnet_scheduler_tick_duration_seconds`、`magnet_tasks`、`magnet_engine_download_rate_bytes_per_second`、`magnet_sse_connections` 等）作为调优唯一客观评判标准。
4. **定向外科式调优**：针对压测暴露的真实瓶颈实施调优，拒绝无量化证据的过度设计或破坏性重构。

---

## 2. 压测场景与可重复数据集

### 2.1 本地测试源（Test Bed）

为消除公网抖动对基准测试的影响：

- **HTTP 本地压测服务**：
  - 基于轻量内置 HTTP 模拟源（或现有容器网桥支持），提供固定 10MB、100MB 的合成文件下载端点，支持严格的 `Content-Length`、`Range` 分段与高并发吞吐。
- **BT 本地测试种子**：
  - 复用/扩展现有的本地测试夹具（如 `payload.torrent`），在本地 Docker 容器环境内实现本地 Seeder 与本地 Tracker 互通，实现确定性 BT 高速下载压测。

### 2.2 核心测试场景

| 场景编号 | 场景名称 | 模拟行为 | 观测目标指标 |
|---|---|---|---|
| **SCN-1** | 任务爆发提交 (Burst Submission) | 20 并发用户瞬时提交 200 个解析与下载任务 | API 响应时间、`magnet_task_queue_depth`、MyBatis/连接池等待耗时 |
| **SCN-2** | 调度器饱和 tick (Scheduler Saturation) | 50 个会话同时处于 DOWNLOADING/ACTIVE 状态 | `magnet_scheduler_tick_duration_seconds` (p95 < 200ms)、快照收集耗时 |
| **SCN-3** | 海量 SSE 广播 (Massive SSE Streaming) | 100 个客户端并发订阅任务进度 SSE 流 | `magnet_sse_connections`、内存开销、连接断开率 (`magnet_sse_disconnects_total`) |
| **SCN-4** | 极限下载吞吐与导出 (Throughput & Export) | 并发跑满本地网卡带宽进行下载并立即执行导出 | `magnet_engine_download_rate_bytes_per_second`、`magnet_export_duration_seconds` |

---

## 3. 潜在性能瓶颈与调优预案

### 3.1 调度器单线程 Tick 串行 RPC 瓶颈
- **现状**：`TaskDispatcher.tick()` 每 2 秒在单调度线程中同步轮询所有活跃会话的 `engine.getSnapshot()`。
- **风险**：当会话数增加到数十个时，多个串行网络/RPC 调用耗时累加可能突破 2 秒，造成调度 tick 周期性堆积。
- **调优方案**：
  - 引入可控上限的虚拟线程或线程池执行并发快照抓取（每个快照调用设独立短超时），并将快照结果聚合回写。
  - 保持单会话所有权与 CAS 状态更新依然串行安全，不破坏既有租约与状态机一致性。

### 3.2 数据库连接池与查询批处理
- **现状**：HikariCP 默认 `maximum-pool-size: 10`。
- **风险**：调度器、导出物化、SSE 事件推送与 API 请求并发时，连接池可能耗尽排队。
- **调优方案**：
  - 根据核心并发度微调 HikariCP 连接池大小（如调整为 20~30）与 `minimum-idle`。
  - 针对高频的活跃会话查询与任务状态回写优化索引与批量操作。

### 3.3 aria2 与 libtorrent 参数配置调优
- **现状**：aria2 使用基础启动参数；Worker gRPC 保持默认配置。
- **调优方案**：
  - 微调 aria2 单服务器连接数（`max-connection-per-server`）、分片大小与磁盘写入缓冲。
  - 优化 libtorrent-worker 的磁盘缓存大小与活动连接上限。

---

## 4. 落地步骤与计划分阶段

- **Phase 1: 压测工具与可重复数据集构建**
  - 构建本地压测脚本与自动化驱动器（支持一键发起 SCN-1 ~ SCN-4）。
  - 输出压测前的初始基准报告（Baseline Benchmark Report）。
- **Phase 2: 瓶颈分析与定向调优**
  - 基于初始压测暴露的具体指标瓶颈实施最小化代码/配置优化。
  - 保持全量单元测试与集成测试 100% 绿色。
- **Phase 3: 优化后回归与压测对比**
  - 运行相同压测脚本，对比前后指标数据（tick 延迟、吞吐上限、连接承载量）。
  - 更新 `HANDOFF.md` 记录最终性能规格与调优结论。

---

## 5. 红线与约束

1. **指标驱动**：任何性能优化必须有前置失败/瓶颈数据支撑，优化后有量化对比数据，严禁无证据盲目调优。
2. **状态机与安全性不变**：调优不得破坏多租户隔离、租约 fencing、两轮完成确认与手动导出安全检查。
3. **环境隔离**：压测脚本与临时测试数据不得污染用户实际生产数据目录与持久数据库记录。
