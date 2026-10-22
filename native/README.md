# libtorrent gRPC Sidecar Worker

C++ 实现的 BitTorrent 下载引擎 Sidecar，通过 gRPC 向 Java 控制面（`GrpcLibtorrentEngine`）提供下载能力。
基于 Debian bookworm 原生包：libtorrent-rasterbar 2.0.8 + gRPC + protobuf，cmake + ninja 构建。

## 目录结构

```
native/
├── Dockerfile                  # 构建 + 运行镜像（debian:bookworm-slim）
├── libtorrent-worker/
│   ├── CMakeLists.txt          # protoc 生成链 + 链接配置
│   └── src/main.cpp            # DownloadEngine 服务实现
└── proto/
    └── download_engine.proto   # Java ↔ C++ 契约（package magnetplatform.engine.v1）
```

## 构建镜像

```powershell
cd d:\release\magnet\magnet-platform\native
docker build -t magnet-libtorrent-worker:test .
```

> build context 必须是 `native/`（Dockerfile 需要同时 COPY `proto/` 和 `libtorrent-worker/`）。
> 编译产物固定为 `/workspace/build/libtorrent-worker`。

## 运行

### 方式一：docker compose（推荐，与 MySQL/Redis 一起）

```powershell
docker compose -f d:\release\magnet\magnet-platform\infra\docker-compose.yml up -d libtorrent-worker
```

### 方式二：docker run（临时调试）

```powershell
docker run -d --name magnet-worker `
  -p 50051:50051 `
  -p 6881:6881 -p 6881:6881/udp `
  -v worker-data:/data `
  magnet-libtorrent-worker:test
```

`/data` 必须挂载为持久化 Worker 下载根；容器内 `--download-root=/data` 由镜像默认命令传入。Java 的 `AddTorrentRequest.storage_path` 必须是 `/data` 下的绝对路径，或相对于该根的路径；空路径、根外绝对路径、软链接逃逸路径和规范化后逃逸的 `..` 路径都会被拒绝。若使用 bind mount，宿主目录必须映射到 `/data`，例如 `-v d:\magnet-data:/data`。

端口说明：

| 端口 | 用途 |
|---|---|
| 50051 | gRPC（Java 控制面连接） |
| 6881 TCP/UDP | DHT / BT peer 通信 |

启动成功的日志标志：

```
[worker] 启动 gRPC 服务监听 0.0.0.0:50051 download-root="/data"
[worker] gRPC 服务已就绪，等待 Java 控制面连接
```

## 验证 Health RPC

```powershell
python -c "
import sys, os; sys.path.insert(0, os.environ['TEMP'] + '\grpc-test')
import grpc, download_engine_pb2, download_engine_pb2_grpc
ch = grpc.insecure_channel('localhost:50051')
stub = download_engine_pb2_grpc.DownloadEngineStub(ch)
r = stub.Health(download_engine_pb2.HealthRequest())
print(f'ok={r.ok} worker={r.worker_id} libtorrent={r.libtorrent_version}')
"
```

预期输出：`ok=True worker=local-worker-1 libtorrent=2.0.8`

（Python stub 生成：`python -m grpc_tools.protoc --proto_path=proto --python_out=%TEMP%\grpc-test --grpc_python_out=%TEMP%\grpc-test proto/download_engine.proto`）

## Java 侧接入

`server/download-api/.env` 中：

```properties
MAGNET_GRPC_ENABLED=true          # true=本 Worker，false=MockLibtorrentEngine
MAGNET_GRPC_ENDPOINT=localhost:50051
MAGNET_GRPC_INSECURE=true
```

Spring Boot 启动日志出现 `[grpc-libtorrent] Worker 就绪 ok=true` 即全链路贯通。

## 已实现 RPC

| RPC | 状态 |
|---|---|
| Health | ✅ |
| ResolveMetadata | ✅（元数据就绪时返回 .torrent 字节，未就绪返回 `PENDING`） |
| AddTorrent | ✅（torrent bytes / info hash / resume data；校验 `storage_path`） |
| UpdateFilePriorities | ✅（按文件下标下发优先级；缺失 `resource_id` 返回 `accepted=false`） |
| GetSnapshot | ✅（状态、总进度和每个文件的 `file_verified_bytes`） |
| SaveResumeData | ✅（返回非空 resume bytes 和递增 `resume_version`） |
| Pause | ✅（缺失 resource_id 幂等成功） |
| Resume | ✅（缺失 resource_id 幂等成功） |
| RemoveTorrent | ✅（缺失 resource_id 幂等成功；`remove_unreferenced_data=true` 删除文件） |
| StreamAlerts | ⚠️ 当前不在本 Task 3 范围内 |

## Smoke checklist

以下步骤覆盖 Task 3 的 native RPC 合同。先在 `native/` 目录执行：

```powershell
$fixture = Join-Path $env:TEMP ("magnet-worker-" + [guid]::NewGuid())
python -c "import hashlib, pathlib, sys; d = pathlib.Path(sys.argv[1]); d.mkdir(parents=True); p = bytes(range(256)) * 128; (d / 'payload.txt').write_bytes(p); h = b''.join(hashlib.sha1(p[i:i+16384]).digest() for i in (0, 16384)); (d / 'payload.torrent').write_bytes(b'd4:infod6:lengthi32768e4:name11:payload.txt12:piece lengthi16384e6:pieces40:' + h + b'ee')" $fixture
docker run -d --name magnet-worker-smoke `
  -p 50051:50051 `
  -v "${fixture}:/data" `
  magnet-libtorrent-worker:test
```

然后用现有 Python `grpcio`/`grpcio-tools` 生成 `download_engine_pb2*.py`，对 `localhost:50051` 依次调用：

1. `Health`：`ok=true`。
2. `AddTorrent`：传入 `payload.torrent`，`storage_path=/data`，预期 `accepted=true`。
3. `UpdateFilePriorities`：传入 `[1]`，预期 `accepted=true`。
4. `GetSnapshot`：预期 `file_verified_bytes` 恰好 1 个值（V1 fixture 只有一个文件）。
5. `SaveResumeData`：预期 `accepted=true`、`resume_data` 非空、`resume_version >= 1`。
6. `Pause`、`Resume`：都预期 `accepted=true`。
7. `RemoveTorrent(remove_unreferenced_data=true)`：预期 `accepted=true`；等待挂载目录的异步文件删除完成后，确认其中的 `payload.txt` 已被删除。

清理 smoke 容器：

```powershell
docker rm -f magnet-worker-smoke
```

## 已知简化项

- `engineSessionId = resourceId`（UUID 字符串），会话存进程内 map，进程 crash 丢失——由 Java 控制面按 owner_epoch 重建（规格 §12.1 允许）。
- `OWNER_EPOCH` 硬编码为 1（单 Worker）；多 Worker 部署时需改为 DB 租约。
- Debian bookworm 的 libtorrent-rasterbar 2.0.8 没有同步 `get_resume_data`；`SaveResumeData` 使用 `save_resume_data(save_info_dict)`，等待 `save_resume_data_alert`，再用 `write_resume_data_buf` 序列化。若未来头文件提供同步 API，C++20 helper 会在编译期选择同步分支。
- `StreamAlerts` 仍未实现；其余上表 RPC 走正常 `accepted/error` 响应。
