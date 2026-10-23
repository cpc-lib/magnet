# ISSUE.md — 已知问题与技术债

## ✅ 已修复 | P5 缺少真实物理 fencing 与可靠接管
- **发现日期**: 2026-10-22
- **严重级**: 高
- **现象**: 多 Worker 可同时接受同一资源；旧命令、失联控制面和 Resume 旧计数使接管不安全。
- **复现**: `TaskServiceTest.leaseRenewFailureFencesSessionStopsWriting`；旧镜像运行 `python native/tests/test_ha.py WorkerHaTest.test_two_workers_cannot_write_same_resource` 会失败（两个 Worker 均接受）。
- **根因**: DB 租约草稿没有 native 磁盘隔离；过期续租和失败 RPC 仍被当成成功；异步磁盘删除未经确认。
- **修复**: `99c1586`（`feat(ha): fence same-host workers and recover expired leases`）：MySQL UTC 租约与 Epoch、共享 Linux flock 和持久代次、看门狗退出、RPC 错误传播、租约内回写/GC、物理锁内完成目录清理、恢复位图失效与重校验。
- **验证**: `mvn test`：113 项，0 失败/错误/跳过；`cd web; npm run build` 成功。`docker build -t magnet-libtorrent-worker:ha-test native` 后运行 `python native/tests/test_ha.py`：9 项真实双 Worker 回归全部通过（含删除确认丢失、冻结、崩溃与坏块恢复）。审查中发现的高优先级问题均已修复。

## 范围边界

本轮仅实现同宿主机 Worker 进程/容器 HA。跨主机、宿主机/数据库/存储 HA 与 HTTP 入口切换未实现，
详见 HANDOFF 和 README。冻结旧进程持有锁时接管等待，不能以强行双写换取固定恢复时限。
