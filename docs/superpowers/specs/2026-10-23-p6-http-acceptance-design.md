# P6 HTTP/HTTPS 实机验收补充设计

用户已确认本轮仅完成 HTTP/HTTPS，FTP/Metalink 后续单列。

沿用现有统一解析、任务、共享需求、两轮完成复核和手动导出流程。BT 保持 libtorrent。
API 使用宿主机 STORAGE_ROOT，aria2 使用独立的 daemon storage-root（Docker 默认 /data），
两者指向同一物理目录。资源描述传递已解析的文件名，addUri 显式指定 out，禁止自动重命名。
仅在 GID 确实不存在时创建，网络/鉴权错误必须传播。暂停、恢复先读状态保证幂等，
RPC 失败不能假装成功或生成空进度。断点数据由 aria2 .aria2 文件管理，开启 continue，
重建适配器及 aria2 重启后恢复同一资源目录；不得凭数据库计数完成。
新建 daemon GID 初始暂停并报告 IDLE，仅需求并集非空时由统一调度恢复，避免重启后擅自恢复暂停任务。

HTTP 探测必须得到正的声明大小，否则拒绝创建（本轮不支持未知大小流）。服务端文件名
不得包含路径分隔符或 traversal。默认禁用真实 aria2 时不能自动启用假下载；Mock 仅显式测试启用。

验收使用自建 aria2 Docker 镜像和隔离测试目录、本地可控 HTTP Range 源，比对下载与导出字节。
覆盖下载中暂停/恢复、重建适配器/重启 aria2 后续传、路径映射和固定文件名。
HTTP 引擎本轮只支持单 API 控制面，不宣称具备 libtorrent Worker 的物理 fencing。
GC 只有确认 aria2 停止后才允许现有安全存储删除对应资源目录，失败保留候选。
