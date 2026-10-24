# P7.1 监控与 Grafana 大盘设计

> 状态：已确认，2026-10-23
>
> 本文只覆盖 P7 第一部分“监控”。P7 第二部分“全链路吞吐压测与调优”另行设计、另行实施。

## 1. 目标

为 Magnet Platform 提供面向管理员/运维的全局监控能力：

1. API 通过标准 Prometheus exposition format 暴露应用与下载引擎指标。
2. Prometheus 采集并保存时间序列。
3. Grafana 预置一个健康优先的平台总览 Dashboard。
4. 监控栈通过 `infra/docker-compose.yml` 的 `monitoring` profile 启动，不改变现有基础服务默认启动行为。
5. 监控指标遵守共享 Session 语义：同一资源的真实引擎吞吐只计一次，不按租户任务重复累加。

## 2. 范围

### 2.1 本次实现

- Spring Boot Actuator 与 Micrometer Prometheus registry。
- 任务、队列、调度、引擎会话、租约、解析、导出、GC、SSE 和 HTTP 请求指标。
- Prometheus Compose 服务与 scrape 配置。
- Grafana Compose 服务、Prometheus 数据源 provisioning 和平台总览 Dashboard JSON。
- 指标抓取凭据、Grafana 运维账号及本地端口绑定配置。
- 单元测试、API 集成测试、Compose 配置验证和全量基线验证。

### 2.2 明确不做

- 不新增 React 监控页面。
- 不接入 MySQL exporter、Redis exporter、Node exporter、cAdvisor 或宿主机监控。
- 不把 `userId`、`taskId`、`resourceId`、文件路径、URL 等高基数值作为 Prometheus label。
- 不在本轮实现压测场景、吞吐调优、限流策略或配额策略；这些属于 P7.2。
- 不修改既有数据库迁移历史，不删除用户数据，不改变 P0–P6 状态机语义。

## 3. 方案与数据流

```text
TaskService / ParseService / ExportService / GC / SSE / HTTP
                         │
                         ▼
             Micrometer MeterRegistry
                         │
                         ▼
             GET /actuator/prometheus
                         │  (dedicated Basic Auth)
                         ▼
        Prometheus (Compose monitoring profile)
                         │
                         ▼
        Grafana (pre-provisioned dashboard)
```

应用继续以当前方式在宿主机运行，Prometheus 容器通过 Docker Desktop 的
`host.docker.internal:8080` 抓取默认端口上的 API。API 端点使用独立 Basic Auth，
用户名固定为受控的监控用户，密码从本地被 Git 忽略的密码文件读取。API 端口可通过现有
`PORT` 配置覆盖；监控 profile 的默认配置与仓库默认开发启动方式保持一致。

Prometheus 和 Grafana 只在 `monitoring` profile 中启动：

```powershell
cd infra
docker compose --profile monitoring up -d prometheus grafana
```

默认端口绑定到宿主机回环地址：Prometheus 为 `127.0.0.1:9090`，Grafana 为
`127.0.0.1:3000`。Grafana 使用独立运维登录，不复用业务 JWT。

## 4. 指标模型

### 4.1 命名与标签

业务指标使用 `magnet_` 前缀。标签仅使用低基数、稳定枚举：

- `engine`: `LIBTORRENT`、`ARIA2`。
- `status`: 任务或会话的有限状态枚举。
- `operation`: `parse`、`export`、`gc`、`snapshot` 等有限操作名。
- `result`: `success`、`failure` 等有限结果枚举。
- `error_type`: 受控错误类别，不写入异常消息和资源标识。

禁止的标签包括用户、任务、资源、文件、URL、磁力链接、路径和异常原文。

### 4.2 任务与队列

- `magnet_tasks{status,engine}`：当前任务状态 Gauge。
- `magnet_task_transitions_total{from,to,engine}`：状态迁移 Counter。
- `magnet_task_queue_length{engine}`：当前可调度队列长度 Gauge。
- `magnet_task_operations_total{operation,result,error_type}`：创建、重试、失败等受控操作计数。

状态 Gauge 在调度 tick 中按当前数据库/会话事实重算；进程重启不恢复旧内存计数。

### 4.3 引擎与租约

- `magnet_engine_sessions{engine,status}`：当前引擎会话数 Gauge。
- `magnet_engine_peers{engine}`：当前 peer 数 Gauge。
- `magnet_engine_lease_renewals_total{engine,result}`：租约续期计数。
- `magnet_engine_errors_total{engine,error_type}`：引擎错误计数。
- `magnet_engine_unavailable_total{engine}`：引擎不可用计数。

会话使用既有约束 `engineSessionId == resourceId`；监控埋点不得创建第二套会话标识。

### 4.4 吞吐与调度

- `magnet_engine_download_rate_bytes_per_second{engine}`：当前下载速率 Gauge。
- `magnet_engine_upload_rate_bytes_per_second{engine}`：当前上传速率 Gauge。
- `magnet_scheduler_ticks_total{result}`：调度 tick 次数 Counter。
- `magnet_scheduler_tick_duration_seconds`：调度 tick Timer/Histogram。
- `magnet_engine_snapshot_duration_seconds{engine}`：快照调用 Timer/Histogram。

速率直接使用 `EngineSnapshot.downloadRateBytesPerSec` 和
`EngineSnapshot.uploadRateBytesPerSec`。速率按引擎 Session 聚合；多个用户共享同一
资源时只采集一次，不能把同一真实吞吐乘以任务数。本阶段不凭快照字段推导累计下载字节，
避免把可回退的校验进度误当成单调累计吞吐。

进度继续遵守仓库既有规则：业务任务进度使用
`EngineSnapshot.fileVerifiedBytes[index]`，按文件声明大小 clamp；监控指标不得用比例估算
替代业务进度。

### 4.5 业务链路与连接

- `magnet_parse_jobs_total{result,error_type}` 与 `magnet_parse_duration_seconds`。
- `magnet_exports_total{result,error_type}` 与 `magnet_export_duration_seconds`。
- `magnet_gc_runs_total{result}` 与 `magnet_gc_duration_seconds`。
- `magnet_sse_connections`：当前 SSE 连接数 Gauge。
- `magnet_sse_disconnects_total{reason}`：SSE 断开计数。
- Spring Boot HTTP server request 指标：保留标准 HTTP 方法、状态码和路由模板标签。

SSE 的断连错误继续遵守现有实现：断连不二次写入 JSON，监控只记录受控断开原因。

## 5. Grafana Dashboard

Dashboard 名称：`Magnet Platform Overview`。

### 5.1 首屏布局：健康优先

第一行 Stat 面板：

- 平台健康度。
- 活跃任务数。
- 活跃引擎会话数。
- 最近窗口错误数。

第二行 Time series/Stat 面板：

- 任务状态分布与队列长度。
- 下载/上传速率，按引擎分组。
- 调度 tick P50/P95/P99 耗时。

第三行 Table/Time series 面板：

- Worker/租约状态。
- 引擎错误类型和最近错误趋势。
- 解析、导出、GC、SSE 链路成功率与耗时。

所有面板使用 Prometheus 查询和有限枚举筛选，不显示租户隐私字段。

## 6. 安全与配置

### 6.1 应用指标端点

只暴露 `health` 和 `prometheus` 管理端点；不暴露环境、配置、Beans 等敏感端点。

Prometheus 抓取使用专用 Basic Auth 凭据，不接受业务 JWT 作为 scrape 认证。API 读取
`MAGNET_METRICS_SCRAPE_PASSWORD_FILE` 指向的本地密码文件；Prometheus 以只读方式挂载
同一密码文件并使用 `password_file` 配置。密码文件放在被 Git 忽略的
`infra/.secrets/prometheus-scrape-password`，示例配置只记录路径，不保存密码明文。

### 6.2 Grafana

Grafana 使用 `GF_SECURITY_ADMIN_USER` 和 `GF_SECURITY_ADMIN_PASSWORD`；默认只绑定
`127.0.0.1:3000`。Grafana 数据源 provisioning 不在仓库中保存密码明文，使用容器环境变量
或 Docker secret 传递。

### 6.3 Prometheus

Prometheus 默认只绑定 `127.0.0.1:9090`，scrape 配置只包含本项目 API 目标。
监控 profile 不应改变 MySQL、Redis、Worker 现有端口和卷。

## 7. 文件边界

预计修改或新增以下文件，实施时仍遵守外科式变更：

- 修改 `server/download-api/pom.xml`：加入 Actuator、Prometheus registry 和必要测试依赖。
- 修改 `server/download-api/src/main/resources/application.yml`：管理端点与抓取配置。
- 修改 `server/download-api/src/main/java/com/magnetplatform/api/security/SecurityConfig.java`：指标抓取认证边界。
- 新增指标 Basic Auth 配置/用户组件：只保护 `/actuator/prometheus`，不改变业务 JWT 链路。
- 新增应用监控埋点组件及其测试；组件只负责指标，不改变业务状态机。
- 修改任务调度、任务服务、解析、导出、GC、SSE 相关路径，仅加入指标记录。
- 新增 `infra/prometheus/prometheus.yml`。
- 新增 `infra/grafana/provisioning/datasources/prometheus.yml`。
- 新增 `infra/grafana/provisioning/dashboards/dashboards.yml`。
- 新增 `infra/grafana/dashboards/magnet-platform-overview.json`。
- 修改 `infra/docker-compose.yml`，加入 `monitoring` profile 的 Prometheus/Grafana 服务。
- 修改 `.env.example`、`README.md` 或 `infra/README.md`，补充启动与凭据说明。
- 新增/修改 `HANDOFF.md` 和 `ISSUE.md`，记录交付事实与验证证据。

不修改现有历史迁移文件，不删除已有测试或用户工作区文件。

## 8. 测试与验收

### 8.1 测试先行

先新增失败测试，至少覆盖：

1. 受控指标名称和标签，不包含高基数标识。
2. 两个用户共享一个资源时，真实引擎吞吐只产生一份聚合值。
3. 指标端点只接受专用 Basic Auth 凭据；业务 JWT 和未认证请求不能抓取。
4. 调度/引擎/解析/导出/GC/SSE 指标在成功和失败路径均不改变原有业务结果。

### 8.2 配置与集成验收

```powershell
docker compose --profile monitoring config
```

必须成功解析，并包含 `prometheus`、`grafana` 两个服务；默认无 monitoring profile 时，
现有基础服务配置保持可用。

启用 monitoring profile 后，验证：

- Prometheus 可以抓取 API 指标端点。
- Grafana 自动发现 Prometheus 数据源。
- `Magnet Platform Overview` Dashboard 可加载且面板有查询结果。
- 无效抓取凭据被拒绝。

### 8.3 仓库基线

```powershell
mvn test
cd web; npm run build; cd ..
```

后端集成测试若因 Docker 不可用而 skip，必须在交付记录中明确说明；不能把 skip 解释为通过。

## 9. 后续 P7.2 边界

P7.2 单独建立设计与计划，基于本轮已落地的指标开展：

- 全链路压测场景与可重复数据集。
- API、调度器、数据库、Redis、Worker/aria2 的瓶颈定位。
- 吞吐、延迟、错误率和资源占用的目标值。
- 只在有测试和指标证据的前提下实施性能改动。
