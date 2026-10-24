# Magnet Platform API 文档

> Base URL: `http://localhost:8080`
> 认证方式: `Authorization: Bearer <token>`（除注册/登录外所有端点必需）

## 错误码

统一错误响应：`{"code":"ERROR_CODE","message":"描述"}`

| HTTP | code | 说明 |
|------|------|------|
| 400 | VALIDATION | 请求参数不合法 |
| 401 | UNAUTHORIZED | 未认证或 token 无效 |
| 403 | FORBIDDEN | 无权限 |
| 404 | NOT_FOUND | 资源不存在 |
| 409 | CONFLICT | 状态冲突 |
| 410 | INPUT_LOST | 资源元数据已丢失 |
| 422 | INVALID_MAGNET / INVALID_TORRENT / INVALID_PATH | 输入内容不合法 |
| 429 | RATE_LIMITED | 频率超限 |
| 500 | EXPORT_IO_ERROR | 导出 IO 错误 |
| 503 | WORKER_UNAVAILABLE | 引擎不可用 |
| 504 | METADATA_TIMEOUT | 元数据超时 |

---

## 1. Auth — 认证

### POST /api/v1/auth/register

注册新用户。**201 Created**

```json
// Request
{ "username": "alice", "password": "secure-password" }

// Response
{
  "token": "eyJhbGciOi...",
  "user": { "id": "uuid", "username": "alice", "role": "USER" }
}
```

### POST /api/v1/auth/login

登录获取 JWT。**200 OK**，请求/响应同 register。

---

## 2. Parse — 资源解析

### POST /api/v1/parses

提交 Magnet URI 或 HTTP/HTTPS 直链解析。**201 Created**

```json
// Request
{ "uri": "magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c" }

// Response (ParseJobView)
{
  "id": "uuid",
  "resourceId": "uuid",
  "inputType": "MAGNET",
  "displayHint": "Big Buck Bunny",
  "status": "COMPLETED",
  "errorCode": null,
  "errorMessage": null,
  "createdAt": "2026-10-23T10:00:00",
  "updatedAt": "2026-10-23T10:00:05"
}
```

### POST /api/v1/parses/upload

上传 .torrent 文件解析。**201 Created**，`multipart/form-data`，字段名 `file`。

### GET /api/v1/parses

分页查询解析历史。

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| page | int | 1 | 页码 |
| size | int | 30 | 每页大小 |
| status | string | — | 可选过滤：PENDING/RUNNING/COMPLETED/FAILED |

```json
// Response (PageView<ParseJobView>)
{ "items": [...], "total": 42, "page": 1, "size": 30 }
```

### GET /api/v1/parses/{id}

查询单个解析任务。

### POST /api/v1/parses/{id}/retry

重试失败的解析任务。

---

## 3. Resource — 资源文件

### GET /api/v1/resources/{id}/files

获取资源的文件清单。

```json
// Response (ResourceDetailView)
{
  "resource": {
    "id": "uuid",
    "name": "Big Buck Bunny",
    "versionType": "V1",
    "btih": "dd8255ecdc...",
    "btmh": null,
    "pieceLength": 262144,
    "totalBytes": 276445467,
    "privateFlag": false,
    "metadataSource": "DHT",
    "trackers": ["udp://tracker.example:1337"],
    "fileCount": 3,
    "metadataReady": true,
    "createdAt": "2026-10-23T10:00:00"
  },
  "files": [
    {
      "fileIndex": 0,
      "relativePath": "Big Buck Bunny/BigBuckBunny_640x360.m4v",
      "sizeBytes": 276445467,
      "v2PiecesRootHex": null
    }
  ]
}
```

---

## 4. Task — 下载任务

### POST /api/v1/tasks

创建下载任务。**201 Created**

```json
// Request (CreateTaskRequest)
{
  "parseId": "uuid",           // 必填：已完成的解析 ID
  "fileIndices": [0, 1],       // 必填：要下载的文件下标
  "targetSubdirectory": "movies/2024",  // 可选：导出子目录
  "startImmediately": true,    // 可选：默认 true
  "priority": 5                // 可选：优先级
}

// Response (TaskView)
{
  "id": "uuid",
  "resourceId": "uuid",
  "status": "DOWNLOADING",
  "selectedBytes": 552890934,
  "verifiedBytes": 12345678,
  "progressPercent": 2.23,
  "priority": 5,
  "speedLimit": null,
  "targetSubdirectory": "movies/2024",
  "errorCode": null,
  "errorMessage": null,
  "version": 3,
  "createdAt": "2026-10-23T10:00:00",
  "updatedAt": "2026-10-23T10:01:00",
  "startedAt": "2026-10-23T10:00:05",
  "completedAt": null
}
```

### GET /api/v1/tasks

分页查询任务列表。参数同 parse list，`status` 可选值：`QUEUED/STARTING/DOWNLOADING/VERIFYING/PAUSED/COMPLETED/FAILED/RETRY_WAIT/REPAIRING/DELETED`。

### GET /api/v1/tasks/{id}

查询单个任务详情。

### POST /api/v1/tasks/{id}/pause

暂停任务。共享会话中仅收回该用户需求，其他用户不受影响。

### POST /api/v1/tasks/{id}/resume

恢复已暂停的任务。

### POST /api/v1/tasks/{id}/retry

重试失败的任务。

### PATCH /api/v1/tasks/{id}

更新任务属性。

```json
// Request (PatchTaskRequest)
{ "priority": 10, "speedLimit": 1048576 }  // speedLimit: 字节/秒
```

### DELETE /api/v1/tasks/{id}

删除任务。**204 No Content**

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| removeOwnExport | bool | false | 是否同时删除已导出的文件副本 |

### GET /api/v1/tasks/{id}/files

获取任务的文件级进度。

```json
// Response (List<TaskFileView>)
[
  {
    "fileIndex": 0,
    "selected": true,
    "verifiedBytes": 12345678,
    "exportState": "PENDING",
    "relativePath": "Big Buck Bunny/BigBuckBunny_640x360.m4v",
    "sizeBytes": 276445467
  }
]
```

### GET /api/v1/tasks/{id}/events

SSE 事件流（`Accept: text/event-stream`）。

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| afterSeq | long | 0 | 从该序号之后补拉持久化事件 |

事件类型：`STATUS`、`SNAPSHOT`（每 2 秒推送一次 TaskView）。任务到达终态（COMPLETED/FAILED/DELETED）后自动关闭。

---

## 5. Export — 文件导出

### GET /api/v1/tasks/{taskId}/exports

查询任务的导出记录。

```json
// Response (List<ExportView>)
[
  {
    "id": "uuid",
    "fileIndex": 0,
    "relativePath": "movies/2024/BigBuckBunny_640x360.m4v",
    "state": "COMPLETED",
    "bytes": 276445467,
    "errorMessage": null
  }
]
```

### POST /api/v1/tasks/{taskId}/exports

手动触发已完成任务的文件导出。

### GET /api/v1/tasks/{taskId}/exports/{exportId}/content

下载已导出的文件内容。响应头 `Content-Disposition: attachment`，`Content-Type: application/octet-stream`。

---

## Postman Collection

导入 [magnet-api.postman_collection.json](magnet-api.postman_collection.json) 到 Postman，设置变量 `base_url` 和 `token` 即可使用。
