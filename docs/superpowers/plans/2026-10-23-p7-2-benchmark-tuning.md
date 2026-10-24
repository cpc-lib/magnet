# P7.2 全链路吞吐压测与性能调优实施计划

> 依据：`docs/superpowers/specs/2026-10-23-p7-2-benchmark-tuning-design.md`
> 目标：构建本地确定性压测体系，基准测试定位瓶颈，实施外科式调优，并提供量化指标对比。

---

### Task 1: 压测驱动器与本地测试源夹具 (Benchmark Driver & Test Bed)

**Files:**
- Create: `benchmarks/run_benchmark.py`（自动化多场景压测驱动脚本，支持并发提交任务、SSE 长连接压测与指标抓取）
- Create: `benchmarks/test_http_server.py`（轻量本地受控测试服务器，提供固定 10MB/100MB 确定性二进制流与 HTTP Range 支持）
- Modify: `.gitignore`（确保 `benchmarks/data` 或压测临时产物不进版本库）

- [ ] **Step 1: 编写本地可控 HTTP 测试服务器**
  实现支持 `Range` 请求、固定内容校验和高吞吐的轻量本地测试源（监听 `127.0.0.1:18080`），不依赖外网。
- [ ] **Step 2: 编写多场景压测执行器 `run_benchmark.py`**
  - 支持并发用户注册/登录。
  - 场景 1：并发提交 100~200 个解析/下载任务（测量 API 响应时间与创建吞吐）。
  - 场景 2：并发 50 个会话运行（观测调度器 tick 耗时与快照耗时）。
  - 场景 3：并发 50~100 个 SSE 长连接监听（验证连接存活与内存/断开率）。
  - 自动从 `http://localhost:9090/api/v1/query` 抓取关键指标（`magnet_scheduler_tick_duration_seconds`、`magnet_tasks`、`magnet_engine_download_rate_bytes_per_second`）。
- [ ] **Step 3: 运行小规模冒烟验证**
  验证压测脚本能够正常发起请求、获取 token、并在任务完成后清理。

---

### Task 2: 初始基准压测与瓶颈指标采撷 (Baseline Benchmark Run)

**Files:**
- Create: `benchmarks/results/baseline_report.md`（记录调优前的原始性能基准数据）

- [ ] **Step 1: 运行 SCN-1 (Burst Submission) 并采撷基准**
  记录 API 响应延迟、数据库连接池等待状态与任务排队深度。
- [ ] **Step 2: 运行 SCN-2 (Scheduler Saturation) 并采撷基准**
  观测 `magnet_scheduler_tick_duration_seconds` 的 p50/p95/p99 延迟分布与长耗时 tick。
- [ ] **Step 3: 运行 SCN-3 (Massive SSE) 并采撷基准**
  记录并发连接下的连接稳定性和 CPU/线程占用。
- [ ] **Step 4: 汇总生成 `baseline_report.md`**
  显式陈述发现的真实瓶颈点（如 HikariCP 连接等待、串行快照拖慢调度等）。

---

### Task 3: 外科式定向调优 (Surgical Performance Tuning)

**Files:**
- Modify: `server/download-api/src/main/resources/application.yml`（数据库连接池与调度参数微调）
- Modify: `server/download-application/src/main/java/com/magnetplatform/application/TaskService.java`（快照并行/超时抓取与调度批处理优化）
- Modify: `infra/docker-compose.yml`（aria2 并发分片与 libtorrent 缓存优化）

- [ ] **Step 1: 优化调度器快照收集（并发 + 超时熔断）**
  在 `TaskService` 中对活跃会话的快照调用增加超时保护与并发拉取（CompletableFuture/虚拟线程），防止单个卡顿引擎会话拖垮整个调度周期；保持状态更新的串行原子性与 CAS 一致。
- [ ] **Step 2: 调优数据库连接池与异步执行器**
  微调 `HikariCP` 最大连接数（从 10 优化至 20~30）以消除高并发下的借用等待；优化线程池队列。
- [ ] **Step 3: 调优下载引擎并发参数**
  针对 `aria2` 调优 `max-connection-per-server=8`、`min-split-size=1M`、`disk-cache=32M`。

---

### Task 4: 优化后压测对比与基线全绿验证 (Verification & Comparison)

**Files:**
- Create: `benchmarks/results/optimized_report.md`（优化后对比报告）

- [ ] **Step 1: 重新运行全套压测场景 SCN-1 ~ SCN-4**
  在相同硬件和测试件下运行相同压测驱动，记录优化后数据。
- [ ] **Step 2: 输出性能对比报告**
  对比调度 tick p95 延迟（目标降低 50%+）、API 吞吐、下载速度与 SSE 承载力。
- [ ] **Step 3: 运行全量回归验证**
  - 执行 `mvn test`：必须保持 173 项测试 100% SUCCESS，0 失败、0 错误。
  - 执行 `cd web; npm run build; cd ..`：前端生产打包绿。

---

### Task 5: 文档收敛与版本交接 (Handoff & Review)

**Files:**
- Modify: `HANDOFF.md`（交付矩阵更新为 P7.2 已交付，记录压测数据与关键调优决策）
- Modify: `ISSUE.md`（更新相关性能调优记录）
- Modify: `README.md`（补充压测工具运行说明）

- [ ] **Step 1: 更新 HANDOFF.md 与 ISSUE.md**
- [ ] **Step 2: 提交代码与压测基准文档**
