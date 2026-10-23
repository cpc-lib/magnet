# P6 HTTP/HTTPS 实机验收 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task in the current session.

**Goal:** 完成用户已确认的 HTTP/HTTPS 真实 aria2 下载和手动导出验收。
**Architecture:** 保留统一任务和 SPI，在资源描述中附带输出文件名；aria2 独立配置容器存储根目录。
RPC 错误传播，暂停恢复状态幂等，断点依赖实际 .aria2 文件。BT 路径不变。
**Tech Stack:** Java 21、Spring Boot、JUnit、aria2 JSON-RPC、Docker、MySQL Testcontainers。

## 1. 失败回归与适配器修复
- [x] 在 Aria2JsonRpcEngineTest 添加路径/文件名、网络失败不创建、暂停恢复错误传播测试。
- [x] 执行 `mvn -pl server/download-engine-aria2 -am "-Dtest=Aria2JsonRpcEngineTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，确认失败。
- [x] ResourceDescriptor 增加 outputFilename，保留旧构造函数；TaskService 两处创建描述传 resource.name。
- [x] addUri 配置 `dir=/data/resources/<uuid>`、`out=filename`、`continue=true`、`auto-file-renaming=false`；GID 缺失才创建，初始暂停后按需求恢复。
- [x] 暂停/恢复按 tellStatus 状态幂等调用；snapshot 错误抛出；删除确认后再清理。
- [x] 重跑适配器及 TaskService 测试。

## 2. 探测与配置
- [x] HttpProbeServiceTest 添加未知大小及路径文件名拒绝测试，先观察失败。
- [x] 探测拒绝非正大小/非法文件名，避免零大小任务和导出路径不一致。
- [x] Mock aria2 改为显式测试开关，API 测试启用该开关。
- [x] 自建 infra/aria2/Dockerfile，Compose 挂载 /data，明确 RPC 参数和 storage-root。

## 3. 实机验收与交付
- [x] 增加真实 aria2 集成测试，使用隔离目录和 HTTP/HTTPS Range 源，验证暂停恢复、重启续传、文件名和实际字节。
- [x] API 测试使用真实 aria2 覆盖解析、完成两轮复核、导出前无副本、手动导出字节一致。
- [x] `mvn test` 170 项通过，无 Docker 跳过；`cd web; npm run build` 成功。
- [x] 更新 README/HANDOFF/ISSUE，明确 HTTP/HTTPS 与单控制面边界，复核暂存区后 Conventional Commit（实现 `e0f754e`）。
