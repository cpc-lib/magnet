# Java 多用户 Magnet / BitTorrent 下载管理平台

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
│   ├── download-engine-aria2/      # aria2 HTTP/HTTPS JSON-RPC 引擎
│   ├── download-scheduler/         # 定时调度（任务分发、超时清扫、GC 清扫）
│   └── download-api/               # REST 控制器、JWT 安全过滤器与启动入口
├── native/
│   ├── proto/download_engine.proto # gRPC 契约接口
│   └── libtorrent-worker/          # C++20 Sidecar 源码（libtorrent 2.0.8）
├── web/                            # React 18 + TypeScript + Ant Design 前端
└── infra/
    └── docker-compose.yml          # MySQL 8.4 + Redis 7 + libtorrent-worker
```

---

## 二、P0–P7 交付与验收矩阵

| 阶段 | 核心规格与范围 | 交付状态 | 验证手段与依据 |
|---|---|---|---|
| **P0** | 用户注册/登录、无状态 JWT、租户数据隔离 | ✅ 已交付 | `AuthServiceTest` (5 用例), `ApiIntegrationTest` 跨租户 404 |
| **P1** | Magnet 与 .torrent 解析、V1/V2/Hybrid 校验、解析历史与配额 | ✅ 已交付 | `MagnetParserTest` (10), `TorrentMetaParserTest` (13), `ParseServiceTest` (15) |
| **P2** | C++ libtorrent 真实 Worker、任务生命周期、Piece 粒度校验 | ✅ 已交付 | Worker 容器端到端验证：GetSnapshot `file_verified_bytes` 精确匹配 |
| **P3** | 共享 Session (单资源单会话)、活跃需求并集、断点恢复 | ✅ 已交付 | `TaskServiceTest` (27 用例), `ApiIntegrationTest.sharedSessionAcrossTwoUsersAndDemandUnion` |
| **P4** | Resume 崩溃恢复、坏块重检、安全导出物化 (防穿越/软链接逃逸)、引用计数宽限期 GC | ✅ 已交付 | `ExportServiceTest` (6), `FileDownloadStorageTest` (6), `ReferenceGcServiceTest` (4), Worker 容器坏块重检验证 |
| **P5** | 同宿主机双 Worker 租约、Epoch、磁盘 fencing 与故障接管 | ✅ 同机范围交付；跨主机未实现 | `TaskServiceTest`、`ApiIntegrationTest`、`GrpcLeaseTest`、`native/tests/test_ha.py` |
| **P6** | aria2 HTTP/HTTPS 下载引擎；FTP/Metalink 后续单列 | ✅ HTTP/HTTPS 已通过实机验收 | `Aria2DownloadIntegrationTest` 真实下载、暂停恢复、重启续传与手动导出 |
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

### 2. 启动 Java Spring Boot API 服务

后端启动时会自动执行 Flyway V1~V4 数据库迁移并连接 Worker：

```powershell
cd server/download-api
mvn spring-boot:run
```

API 默认监听端口 `8080`，健康探针与接口根路径 `/api/v1`。

### 3. 启动 React 前端 Web 服务

```powershell
cd web
npm install
npm run dev
```

前端默认监听 `http://localhost:5173`，通过 Vite 代理反向转发 `/api/v1` 请求至后端 `8080` 端口。

---

## 四、全量验证命令集

```powershell
# 1. 后端全模块测试（包含 MySQL Testcontainers 与全部单元/集成测试）
mvn test

# 2. 前端 TypeScript 编译与生产打包
cd web
npm run build
cd ..
```

## 五、P5 同宿主机双 Worker 高可用

两个 API 实例各自连接一个 Worker，共用 MySQL 和同一宿主机数据目录。
这是进程/容器故障接管，不包含宿主机、MySQL 或共享存储本身的高可用。

从旧版升级时先停止旧 Worker，再启动两个新版 Worker；未实现 fencing 的旧进程不能参与混跑。
`worker-locks` 是两个 Worker 必须共用的 Linux Docker 卷；运行期间不要删除或更换该卷，
也不能给两个 Worker 分配不同锁卷却共享同一数据目录。

```powershell
docker compose -f infra/docker-compose.yml stop libtorrent-worker
docker compose -f infra/docker-compose.yml --profile ha up -d --build
```

Worker 1 / Worker 2 的 gRPC 端口分别为 `localhost:50051` / `localhost:50052`，仅绑定环回地址。
在两个独立 PowerShell 窗口中，从仓库根目录运行 API（先执行一次 `mvn install "-DskipTests"`）：

```powershell
# 窗口 1
$env:MAGNET_WORKER_ID='local-worker-1'
$env:MAGNET_GRPC_ENDPOINT='localhost:50051'
$env:PORT='8080'
$env:STORAGE_ROOT=(Resolve-Path './server/download-api/data').Path
mvn -pl server/download-api spring-boot:run
```

```powershell
# 窗口 2；DB、JWT_SECRET、STORAGE_ROOT 必须与窗口 1 一致
$env:MAGNET_WORKER_ID='local-worker-2'
$env:MAGNET_GRPC_ENDPOINT='localhost:50052'
$env:PORT='8081'
$env:STORAGE_ROOT=(Resolve-Path './server/download-api/data').Path
mvn -pl server/download-api spring-boot:run
```

API 实例 ID 必须不同，且与各自 Worker 配对。默认租约为 30 秒，调度周期为 2 秒。
Worker 不可达时该 API 停止续租；备用实例在数据库租约过期、且旧进程释放磁盘锁后接管，
重新校验磁盘。冻结进程仍持锁时接管会等待；没有安全的硬性故障恢复时限。
租约超时或无法确认磁盘任务已排空时 Worker 会退出并由 Compose 重启，
同一 Worker 的其他会话会短暂中断后重新挂载。导出仍需用户手动点击。

## 六、P6 HTTP/HTTPS 下载（单 API 控制面）

BT 仍使用 libtorrent，HTTP/HTTPS 直链使用 aria2。FTP、Metalink 和 aria2 多控制面物理
fencing 不属于本轮范围。启用 HTTP 时只运行一个 API 调度实例，不将其纳入双 Worker HA。

在仓库根目录的 PowerShell 中设置同一 RPC 密钥并启动：

```powershell
$env:MAGNET_ARIA2_RPC_SECRET = '替换为自己的随机密钥'
docker compose -f infra/docker-compose.yml --profile http up -d --build aria2
$env:MAGNET_ARIA2_RPC_ENABLED = 'true'
$env:MAGNET_ARIA2_RPC_ENDPOINT = 'http://localhost:6800/jsonrpc'
$env:MAGNET_ARIA2_STORAGE_ROOT = '/data'
$env:STORAGE_ROOT = (Resolve-Path server/download-api/data).Path
mvn install "-DskipTests"
mvn -pl server/download-api spring-boot:run
```

API 启动命令沿用第三节的模块启动方式；上面的变量必须在 API 进程所在窗口中设置。
容器 `/data` 与宿主机 `server/download-api/data` 必须指向同一物理目录。
aria2 收到 `/data/resources/<resourceId>`；API 从宿主机 STORAGE_ROOT 读取并安全导出。
文件名以解析结果为准，不允许 aria2 自动改名。默认禁用 aria2 时 HTTP 任务保持排队，
不会以 Mock 模拟完成；Mock 仅测试显式启用。

在解析页面提交 HTTP/HTTPS URL，选择文件创建任务；完成后点击“导出文件”，再下载导出副本。
未知文件大小、空文件或不安全的服务端文件名会被拒绝。本轮不支持需要登录或自定义请求头的直链。

真实验收（Docker 可用时自动构建测试 aria2 镜像，使用临时存储及本地可控源）：

```powershell
mvn -pl server/download-api -am "-Dtest=Aria2DownloadIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

aria2 的 `out`、`continue` 和 RPC 行为依据 [aria2 官方手册](https://aria2.github.io/manual/en/html/aria2c)。

双 Worker 验证使用独立临时 Docker 卷，不访问上述用户数据目录：

```powershell
docker build -t magnet-libtorrent-worker:ha-test native
python -m pip install grpcio grpcio-tools
python native/tests/test_ha.py
```
