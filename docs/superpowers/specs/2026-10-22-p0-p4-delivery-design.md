# P0–P4 必须交付补齐设计

> 日期：2026-10-22  
> 状态：待文档复核  
> 适用项目：`magnet-platform`  
> 范围：P0、P1、P2、P3、P4  
> 排除：P5 Worker 高可用、P6 aria2 多协议、P7 监控/吞吐优化

## 1. 目标与成功标准

在保留现有 Java 控制面、C++ libtorrent Worker、MySQL/Redis 和 React 前端边界的前提下，补齐规格中 P0–P4 的可交付能力。交付必须能够通过代码级测试，并在 Docker 环境中完成真实 Worker 验收，而不是仅依赖 Mock 引擎。

成功标准按里程碑定义：

| 阶段 | 必须完成 | 退出标准 |
| --- | --- | --- |
| P0 | Java 多模块、JWT、MySQL、API、前端基础流程 | 普通用户只能访问自己的解析、任务和导出；越权统一返回 404 |
| P1 | Magnet/.torrent 解析、V1/V2/Hybrid、历史、重试、文件树 | 合规输入可进入 READY；非法输入进入明确错误状态；解析历史可查询和重试 |
| P2 | 真实 libtorrent Worker、任务状态、文件优先级、恢复 | 合法本地测试种子可完成单用户真实下载，暂停/恢复和重启后恢复有效 |
| P3 | 同资源单 Session、需求并集、用户级控制 | A/B 用户共享同一资源时，一个用户暂停或删除不影响另一个用户继续 |
| P4 | 已验证进度、resume data、安全导出、路径防护、回收 | 坏块/重启/越权/目录逃逸测试通过，完成文件可安全流式下载 |

P5–P7 不纳入本次交付。尤其不实现跨主机租约 fencing、aria2 HTTP(S)/Metalink 和新的监控平台。

## 2. 现状与主要缺口

当前代码已经包含：

- Java 多模块、JWT 鉴权、MyBatis/Flyway 数据模型和基础 REST API；
- Magnet 基础校验、V1/V2/Hybrid `.torrent` 解析和解析历史；
- 用户任务状态机、文件选择、需求并集的初步编排；
- Java gRPC 适配器和 C++ libtorrent Worker 的基础 AddTorrent、优先级、快照、暂停/恢复、删除与元数据轮询接口；
- React 解析列表、解析详情和任务列表页面。

需要补齐的关键缺口：

1. Java 任务创建没有把已保存的 `.torrent` 元数据和受控下载目录传给 Worker；
2. Worker 的 resume data 没有真正保存和恢复，进程重启后 Session 不能可靠重建；
3. 任务进度只按 Session 总字节比例估算，没有按用户选中文件更新 `download_task_file`；
4. 完成态没有连接到安全的用户导出物化与流式下载；
5. 共享 Session 的生命周期、无引用回收和删除后的数据保护不完整；
6. aria2 占位和 P5–P7 功能不属于本次范围，应保持明确未接入；
7. 前端缺少任务详情、文件进度和已完成导出访问；
8. 现有测试覆盖元数据和基础 API，但缺少真实 Worker 合同、A/B 共享 Session、恢复、导出和路径安全验收。

## 3. 方案选择

采用“保留现有架构、按垂直切片补齐 P0–P4”的方案。

### 3.1 选择原因

- 现有模块边界已经对应控制面、领域、应用编排、基础设施、引擎 SPI、Worker 和前端，不需要重写；
- P0/P1 的核心解析和鉴权代码可以保留，重点把 P2–P4 的数据流接通；
- 真实 Worker 通过 Docker 可构建和运行，能够满足 P2 的真实下载退出标准；
- 用端口接口隔离文件存储、导出和 Session 恢复，可以让应用测试继续使用内存 Fake，不把测试绑死在 native 进程上。

### 3.2 明确不采用的方案

- 只实现 Mock 引擎：无法证明真实下载、文件优先级和恢复，因此不满足 P2；
- 重构为新的分布式存储/调度架构：会引入 P5 级别的范围和风险，且没有当前交付所需的证据。

## 4. 总体架构

```text
React + Ant Design
        │ JWT / REST / SSE
        ▼
Spring Boot API
  ├─ Auth / tenant authorization
  ├─ ParseService
  ├─ TaskService
  ├─ ExportService
  └─ Scheduler
        │
        ├─ MySQL: users/resources/parses/tasks/sessions/exports/events
        ├─ Redis: parse quota
        ├─ Shared storage root
        │    ├─ metadata/*.torrent
        │    ├─ resources/{resourceId}/
        │    ├─ resume/{resourceId}.dat
        │    └─ exports/{userId}/{taskId}/
        │
        └─ gRPC
             ▼
       libtorrent Worker
       one Session per resource + storage domain
```

Java 负责身份、授权、用户意图、状态机、数据库一致性和导出权限。Worker 只负责 libtorrent Session、协议交互、Piece 校验、文件写入和底层快照，不接收用户级授权决策。

Java 与 Worker 必须使用同一逻辑共享存储根目录：本地运行时通过配置映射到相同目录；Docker 验收时通过同一个 named volume 或 bind mount 映射到 API 与 Worker 容器。用户提交的 `targetSubdirectory` 只作为受控逻辑子目录，不能变成 Worker 的任意绝对路径。

## 5. 数据与接口设计

### 5.1 资源元数据

`torrent_resource` 继续保存资源身份、V1/V2 哈希、协议类型、文件数量和来源；新增或补齐一个受控的 metadata 存储引用，使任务创建时可以加载已校验 `.torrent` 字节。引用只保存相对路径或存储键，不保存客户端提供的绝对路径。

Magnet 元数据解析完成后，Worker 返回 `.torrent` 字节；Java 先重新通过 `TorrentMetaParser` 校验，再原子保存元数据并更新资源文件列表，最后将解析任务置为 READY。任何 Worker 返回的字节都不能绕过 Java 侧的格式、大小、路径和 V1/V2/Hybrid 校验。

### 5.2 Engine SPI 与 gRPC

保留 `DownloadEngine` 的用户无关抽象，补齐以下数据流：

- `ResourceDescriptor` 能提供资源元数据引用/字节和受控 Worker 存储目录；
- `createOrAttach` 在已有 Session 时幂等复用，在重启后携带可选 resume data 重建；
- `setDemand` 传递同一资源所有活跃任务的文件下标并集；
- `snapshot` 返回 Session 状态、总已验证字节、速度、Peer 数和按文件已验证字节；
- `saveResume` 返回版本和 resume data，并通过原子文件写入保存；
- `removeSession` 只在没有其他活跃任务、导出和恢复需求时删除底层数据。

Worker 侧保留 `resource_id + owner_epoch + deadline` 命令信封。P0–P4 只使用单 Worker，因此 `owner_epoch` 仍为单 Worker 配置值；不把它扩展成 P5 的租约 fencing。

Worker 的 `ResolveMetadata`、`AddTorrent`、`UpdateFilePriorities`、`Pause`、`Resume`、`GetSnapshot`、`SaveResumeData` 和 `RemoveTorrent` 需要可验证地工作。Java SSE 可以继续采用持久事件补拉加定时快照，不要求本次把 `StreamAlerts` 扩展成集群事件总线。

### 5.3 用户任务与共享 Session

任务状态仍由 Java 状态机驱动：

```text
CREATED → QUEUED → STARTING → DOWNLOADING → VERIFYING → COMPLETED
                         │             │
                         └─ PAUSING → PAUSED → QUEUED
DOWNLOADING/STARTING → RETRY_WAIT → QUEUED
任意非终态 → FAILED
任意状态 → DELETING → DELETED
```

调度器每轮执行：

1. 读取 QUEUED/RETRY_WAIT/PAUSING 任务并按用户并发上限推进；
2. 为资源确保唯一的 libtorrent Session；
3. 汇总所有未暂停、未删除任务的选中文件下标并集并下发 Worker；
4. 读取 Session 按文件快照，更新每个任务选中文件的 `download_task_file.verified_bytes`；
5. 以用户选中文件已验证字节之和计算 `download_task.verified_bytes`；
6. 仅当该任务全部选中文件都达到校验完成条件时进入 VERIFYING/COMPLETED；任务完成后由导出编排异步物化用户副本，导出状态独立记录，只有 READY 导出才可读取；
7. 当资源没有活跃用户需求时使 Session 进入 IDLE，并在安全条件满足时回收。

用户暂停只从需求并集中移除该用户的选择；如果 B 仍请求同一文件，Worker 不暂停。用户删除只改变自己的任务和导出引用，不删除仍被其他用户使用的资源数据。

### 5.4 Resume 与恢复

每个共享 Session 的 resume data 由 Java 以资源维度保存，采用临时文件写入、flush/关闭、原子 rename。数据库保存 resume 版本和相对存储键，避免把大块二进制直接塞进任务表。

应用启动或调度发现数据库中有活动/可恢复 Session 时：

1. 检查资源元数据和受控下载目录；
2. 读取 resume data（丢失或损坏时允许空 resume 重建）；
3. 调用 Worker AddTorrent；
4. 重新读取按文件快照；
5. 以实际已验证数据重新计算用户任务进度，禁止沿用数据库旧进度直接显示 COMPLETED。

### 5.5 用户导出

新增用户导出记录和应用端口。任务完成后，ExportService 在 `storageRoot/exports/{userId}/{taskId}` 下安全物化选中文件：

- 源路径只能来自资源元数据的已校验相对路径；
- 目标路径只能来自已清洗的任务子目录和文件相对路径；
- 拒绝 `..`、绝对路径、Windows 驱动器前缀、控制字符、符号链接逃逸和大小写冲突；
- 使用临时文件/目录与原子 rename，避免导出中断产生伪完成文件；
- 未完成或失败导出不能通过 API 读取。

提供：

- `GET /api/v1/tasks/{id}/exports`：只返回当前用户可访问的导出记录；
- `GET /api/v1/tasks/{id}/exports/{exportId}/content`：鉴权后流式读取；
- `DELETE /api/v1/tasks/{id}?removeOwnExport=true`：只删除当前用户自己的导出副本。

### 5.6 资源回收

资源回收由独立的 GC 调度步骤执行，不在用户请求线程中直接删除共享数据：

1. 任务删除、导出完成或导出删除后，重新计算资源引用；
2. 只有在没有未删除任务、未完成导出、恢复需求和保留期要求时，才记录 `GC_CANDIDATE`；
3. 候选进入可配置宽限期，期间不删除 Worker Session 或磁盘数据；
4. 宽限期结束后再次核查同一组引用和 Session 版本；
5. 二次核查仍满足条件时，将资源标为 `GC_DELETING`，先停止/删除 Worker Session，再删除资源数据和恢复快照；
6. 任一步骤失败保留候选记录并重试，不把失败当成已回收；
7. 进程崩溃或调度器重启后，候选状态可继续扫描，不能依赖内存队列。

GC 候选记录只保存资源 ID、候选时间、宽限截止时间、状态和最近错误；删除操作使用资源级版本/状态检查，禁止迟到的任务或 Worker 回写把正在使用的资源误删。

## 6. 前端交付

在现有页面基础上只补齐 P0–P4 所需流程：

- 解析详情页显示 READY 资源的文件树、校验进度和创建任务；
- 任务列表显示状态、选中/已验证字节、速度/Peer（若 Worker 提供）和合法操作；
- 任务详情页显示文件级进度、事件时间线、暂停/恢复/重试、导出列表；
- 完成导出提供受控下载入口；
- 删除确认明确区分“删除我的任务”和“删除我的导出文件”；
- SSE/轮询只更新当前可见任务，页面不可见时降低频率。

不实现管理员 Worker 面板、集群拓扑、aria2 任务页面和 P7 性能仪表盘。

## 7. 错误处理与安全边界

- 资源、解析任务、下载任务和导出均从 JWT 中取得实际用户；客户端不能提交任意 `userId`；
- 非法或无权访问的资源/任务/导出统一按 404 处理，避免 ID 枚举；
- Worker 不信任客户端输入的路径、Tracker 或存储路径；
- `.torrent` 仍受大小、bencode 深度/节点数/整数范围、文件数、路径和 Piece 元数据校验；
- Worker 不可用时创建命令保留为可重试的失败/排队状态，不显示伪完成；
- 迟到的 Worker 结果必须通过资源/任务版本和当前 Session 绑定检查，不能复活 DELETED 任务；
- 导出流式读取前必须再次检查任务、导出记录、用户和文件路径；
- 删除底层资源前必须确认没有其他活跃任务、未完成导出或恢复需求。

## 8. 测试与验收

### 8.1 单元测试

- Magnet 的 V1/V2/Hybrid hash、Tracker、display name 和非法输入；
- V1/V2/Hybrid `.torrent` 元数据、坏 bencode、坏 Piece 数、坏 Merkle/pieces root、路径穿越和大小写冲突；
- Task 状态机、CAS 版本冲突、用户暂停/恢复/删除幂等性；
- 需求并集和按文件计算用户进度；
- resume 文件原子保存/加载和损坏回退；
- 导出路径清洗、符号链接防护和用户隔离。

### 8.2 应用/API 集成测试

- 注册、登录、401、跨用户解析/资源/任务/导出 404；
- Magnet RESOLVING → READY/TIMEOUT/FAILED 和 retry；
- `.torrent` 上传、资源文件树和创建任务；
- A/B 同资源不同文件选择；A 暂停/删除后 B 继续；
- 完成任务后只能下载自己的导出；`removeOwnExport` 不影响其他用户；
- MySQL migration、CAS 更新和重启后的真实进度对账。

### 8.3 Worker/端到端测试

使用 Docker 构建并运行 C++ Worker，使用合法、可重复的本地测试种子和本地 seeder，避免依赖未授权公共内容或不稳定公网 Tracker。验证：

1. AddTorrent/Health/文件优先级；
2. 单用户真实下载完成；
3. 暂停/恢复；
4. Java 重启后 resume data 和实际文件进度恢复；
5. 两个用户同资源只建立一个 Session；
6. 一个用户删除/暂停不影响另一个用户；
7. 导出文件内容与合法测试种子内容一致。

### 8.4 Web 验证

- TypeScript 编译和生产构建；
- 解析详情、任务详情、导出下载的主要操作路径；
- 状态非法时按钮禁用，删除确认文案正确。

## 9. 实施顺序

1. 先补资源元数据存储引用、Worker 字节/路径契约和失败测试；
2. 实现 Worker resume、按文件快照和真实任务进度；
3. 实现共享 Session 生命周期、A/B 隔离和恢复；
4. 实现导出记录、物化、流式下载和 GC 安全条件；
5. 补齐 API/前端任务详情和导出流程；
6. 运行 Java、Worker、Web 全量验证并修复由验证发现的问题；
7. 更新 README/运行说明，明确 P0–P4 已交付和 P5–P7 仍未纳入。

每个行为变更先写一个会失败的测试，再写最小实现；不做与 P0–P4 无关的重构。
