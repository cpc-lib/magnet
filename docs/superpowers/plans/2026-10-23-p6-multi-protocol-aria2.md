# P6: 多协议与 aria2 引擎集成实施计划

目标：按照 ADR-002 与统一任务 API 规范，完成 HTTP(S) 解析、aria2 JSON-RPC 引擎适配与任务调度动态路由。

1. **领域与数据模型扩展**：
   - `InputType` 增加 `HTTP`；`VersionType` 增加 `HTTP`。
   - `ParseService` 支持 `submitHttp(userId, uri)`，实现 HTTP Probe（HEAD / Range 0-0 探测文件名与 Content-Length），落库统一资源与文件。
   - `ParseController` 支持识别 HTTP/HTTPS 链接并路由解析。

2. **aria2 引擎适配器（download-engine-aria2）**：
   - 完善 pom.xml 引入 Jackson 与 Spring Boot 依赖，父 pom / download-api pom 引入 `download-engine-aria2`。
   - 实现 `Aria2Client`：封装 JSON-RPC 2.0 基础调用（`addUri`, `pause`, `unpause`, `tellStatus`, `remove`, `getVersion`）。
   - 实现 `Aria2JsonRpcEngine`（`@ConditionalOnProperty(name = "magnet.engine.aria2.rpc.enabled", havingValue = "true")`）。
   - 实现 `MockAria2Engine`（`havingValue = "false", matchIfMissing = true`）。
   - 编写 `Aria2JsonRpcEngineTest`（基于 WireMock 或模拟 HTTP Server）与 `MockAria2EngineTest`。

3. **应用调度层多引擎动态路由（download-application）**：
   - `TaskService`：根据资源 `versionType`（BT 走 `LIBTORRENT`，HTTP 走 `ARIA2`）或会话 `engine_type` 动态路由对应引擎，不再硬编码 `libtorrentEngine()`。
   - `ReferenceGcService`：支持清理 ARIA2 会话。
   - 编写多引擎调度测试：HTTP 任务从创建、启动、快照推进、暂停、恢复、到完成对账的全生命周期。

4. **端到端验证与文档同步**：
   - `docker-compose.yml` 补充 aria2 服务配置示例。
   - 运行全量 `mvn test`（确保所有模块绿）。
   - 运行前端 `npm run build`。
   - 更新 `HANDOFF.md` 与 `ISSUE.md`。
