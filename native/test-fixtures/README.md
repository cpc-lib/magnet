# Worker 確定性测试夹具与端到端 Smoke 验证

本目录提供无外部网络依赖、版权合规的本地 V1 BitTorrent 测试夹具生成脚本与 C++ libtorrent Worker 端到端验证脚本。

## 文件说明

- `create_v1_fixture.py`：生成确定性的 32 KiB `payload.txt`（2 个 16 KiB 分块）及包含真实 SHA-1 分块哈希的 `payload.torrent`。
- `verify_worker.ps1`：构建 `magnet-libtorrent-worker:test` 镜像、启动容器并执行完整 gRPC 生命周期与恢复/坏块检测验证。

## 运行方式

在仓库根目录执行：

```powershell
# 1. 仅生成夹具
python native/test-fixtures/create_v1_fixture.py native/test-fixtures/out

# 2. 执行完整 Worker Smoke + Resume 恢复 + 坏块重检验证
powershell -ExecutionPolicy Bypass -File native/test-fixtures/verify_worker.ps1
```

## 验证覆盖项

1. `Health`：返回 `ok=true, worker_id=local-worker-1, libtorrent_version=2.0.8`。
2. 路径越界拦截：拒绝 `/data` 根目录之外的 `storage_path`（如 `/etc/escape`）。
3. `AddTorrent` + `UpdateFilePriorities` + `GetSnapshot`：按 `piece_granularity` 返回精确校验进度 `file_verified_bytes=[32768]`。
4. `SaveResumeData` + `Pause` + `Resume`：返回非空 resume 数据与递增版本号。
5. 容器重启 + Resume 恢复：携带已保存 `resume_data` 重建会话，快照恢复 `[32768]`。
6. 磁盘坏块检测：篡改第 2 个分块（偏移 20000）并以空 resume 触发重检，`file_verified_bytes` 精确回落为 `[16384]`（不会虚报 COMPLETED）。
7. `RemoveTorrent(remove_unreferenced_data=true)`：清理底层会话并删除磁盘数据文件。
