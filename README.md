# Java 多用户 Magnet / BitTorrent 下载管理平台 (P0–P4)

基于 Java 21 + Spring Boot 3 控制面与 C++ libtorrent-rasterbar 2.0.8 gRPC Sidecar 的多用户 BitTorrent (V1/V2/Hybrid) 下载管理平台。

---

## 一、系统架构与模块

```
magnet/
├── server/
│   ├── download-engine-spi/        # 下载引擎 SPI 契约与快照模型
│   ├── download-domain/            # 种子元数据、Bencode、任务状态机与导出领域模型
│   ├── download-application/       # 解析、任务编排、文件导出与引用计数 GC 应用服务
│   ├── download-infrastructure/   # MyBatis、Flyway (V1-V4)、Redis 配额与下载物理存储
│   ├── download-engine-libtorrent/ # C++ Worker gRPC/Protobuf 适配器与 Mock
│   ├── download-engine-aria2/      # aria2 多协议引擎占位（P6 范围）
│   ├── download-scheduler/         # 定时调度（任务分发、超时清扫、GC 清扫）
│   └── download-api/               # REST 控制器、JWT 安全过滤器与启动入口
├── native/
│   ├── proto/download_engine.proto # gRPC 契约接口
│   ├── libtorrent-worker/          # C++20 Sidecar 源码（libtorrent 2.0.8）
│   └── test-fixtures/              # 确定性 V1 夹具与端到端验证脚本
├── web/                            # React 18 + TypeScript + Ant Design 前端
└── infra/
    └── docker-compose.yml          # MySQL 8.4 + Redis 7 + libtorrent-worker
```

---

## 二、P0–P7 交付与验收矩阵

| 阶段 | 核心规格与范围 | 交付状态 | 验证手段与依据 |
|---|---|---|---|
| **P0** | 用户注册/登录、无状态 JWT、租户数据隔离 | ✅ 已交付 | `AuthServiceTest` (5 用例), `ApiIntegrationTest` 跨租户 404 |
| **P1** | Magnet 与 .torrent 解析、V1/V2/Hybrid 校验、解析历史与配额 | ✅ 已交付 | `MagnetParserTest` (10), `TorrentMetaParserTest` (13), `ParseServiceTest` (14) |
| **P2** | C++ libtorrent 真实 Worker、任务生命周期、Piece 粒度校验 | ✅ 已交付 | `verify_worker.ps1` 端到端验证：GetSnapshot `file_verified_bytes` 精确匹配 |
| **P3** | 共享 Session (单资源单会话)、活跃需求并集、断点恢复 | ✅ 已交付 | `TaskServiceTest` (20 用例), `ApiIntegrationTest.sharedSessionAcrossTwoUsersAndDemandUnion` |
| **P4** | Resume 崩溃恢复、坏块重检、安全导出物化 (防穿越/软链接逃逸)、引用计数宽限期 GC | ✅ 已交付 | `ExportServiceTest` (3), `FileDownloadStorageTest` (6), `ReferenceGcServiceTest` (3), `verify_worker.ps1` 坏块重检 |
| **P5** | 跨主机 Worker 租约 fencing 与集群高可用 | ❌ 未纳入本次交付 | 明确排除于 P0–P4 交付范围 |
| **P6** | aria2 多协议 (HTTP/HTTPS/FTP/Metalink) 下载引擎 | ❌ 未纳入本次交付 | 保持 SPI 占位接口未接入 |
| **P7** | Prometheus 监控大盘与全链路吞吐压测调优 | ❌ 未纳入本次交付 | 明确排除于 P0–P4 交付范围 |

---

## 三、本地环境快速启动

### 1. 启动基础设施与 Worker (Docker)

```powershell
cd infra
docker compose up -d
cd ..
```

启动包含：
- **MySQL 8.4**：端口 `3306`（用户名 `magnet`，密码 `magnet`，库名 `magnet`）
- **Redis 7**：端口 `6379`
- **libtorrent-worker**：端口 `50051` (gRPC), `6881` (DHT)，数据挂载至 `../server/download-api/data`

Worker 的 `/data` 与宿主机 `<仓库目录>/server/download-api/data` 是同一份存储。
在宿主机运行 API 时，将 `.env` 的 `STORAGE_ROOT` 设为该宿主机目录的绝对路径
（本机为 `D:/release/magnet/server/download-api/data`），避免从不同工作目录启动后读取错误位置。
不要将宿主机 API 的 `STORAGE_ROOT` 设为容器内的 `/data`。

任务完成和查询导出列表不会自动导出。进入任务详情点击“导出文件”后，
`POST /api/v1/tasks/{taskId}/exports` 创建所选文件的导出副本；失败后可点击“重试导出”，
成功后点击“下载到本地”。已有可下载副本会保留。

### 2. 检查 Worker 健康状态

```powershell
powershell -ExecutionPolicy Bypass -File native/test-fixtures/verify_worker.ps1
```

### 3. 启动 Java Spring Boot API 服务

后端启动时会自动执行 Flyway V1~V4 数据库迁移并连接 Worker：

```powershell
cd server/download-api
mvn spring-boot:run
```

API 默认监听端口 `8080`，健康探针与接口根路径 `/api/v1`。

### 4. 启动 React 前端 Web 服务

```powershell
cd web
npm install
npm run dev
```

前端默认监听 `http://localhost:5173`，通过 Vite 代理反向转发 `/api/v1` 请求至后端 `8080` 端口。

---

## 四、全量验证命令集

```powershell
# 1. 后端全模块测试（包含 MySQL Testcontainers 与全部单元/集成测试，共 94 个用例）
mvn test

# 2. 前端 TypeScript 编译与生产打包
cd web
npm run build
cd ..

# 3. C++ libtorrent Worker 真实容器 Smoke 与恢复/坏块检测
powershell -ExecutionPolicy Bypass -File native/test-fixtures/verify_worker.ps1
```
