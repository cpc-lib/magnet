-- P2 下载任务数据模型（规格 §8：download_task / download_task_file / engine_session / task_event）
-- 并发安全：状态更新 WHERE id=? AND version=? CAS（§8.1）；删除保留墓碑 deleted_at 防异步复活（§8.1）

CREATE TABLE download_task (
    id                  BINARY(16)    NOT NULL PRIMARY KEY,
    user_id             BINARY(16)    NOT NULL,
    resource_id         BINARY(16)    NOT NULL,
    status              VARCHAR(16)   NOT NULL,
    selected_bytes      BIGINT        NOT NULL,
    verified_bytes      BIGINT        NOT NULL DEFAULT 0,
    priority            INT           NOT NULL DEFAULT 0,
    speed_limit         BIGINT        NULL,
    target_subdirectory VARCHAR(1024) NOT NULL DEFAULT '',
    error_code          VARCHAR(32)   NULL,
    error_message       VARCHAR(1024) NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    started_at          DATETIME(6)   NULL,
    completed_at        DATETIME(6)   NULL,
    deleted_at          DATETIME(6)   NULL,
    KEY idx_task_user (user_id, created_at),
    KEY idx_task_status (status, updated_at),
    KEY idx_task_resource (resource_id, status),
    CONSTRAINT fk_task_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_task_resource FOREIGN KEY (resource_id) REFERENCES torrent_resource (id)
) ENGINE = InnoDB;

CREATE TABLE download_task_file (
    id             BINARY(16)   NOT NULL PRIMARY KEY,
    task_id        BINARY(16)   NOT NULL,
    file_index     INT          NOT NULL,
    selected       TINYINT(1)   NOT NULL DEFAULT 1,
    verified_bytes BIGINT       NOT NULL DEFAULT 0,
    export_state   VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    UNIQUE KEY uk_task_file (task_id, file_index),
    CONSTRAINT fk_task_file_task FOREIGN KEY (task_id) REFERENCES download_task (id)
) ENGINE = InnoDB;

CREATE TABLE engine_session (
    id             BINARY(16)   NOT NULL PRIMARY KEY,
    resource_id    BINARY(16)   NOT NULL,
    worker_id      VARCHAR(64)  NOT NULL,
    engine_type    VARCHAR(16)  NOT NULL,
    state          VARCHAR(16)  NOT NULL,
    owner_epoch    BIGINT       NOT NULL DEFAULT 1,
    lease_until    DATETIME(6)  NULL,
    resume_version BIGINT       NOT NULL DEFAULT 0,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    UNIQUE KEY uk_session_resource (resource_id, engine_type),
    CONSTRAINT fk_session_resource FOREIGN KEY (resource_id) REFERENCES torrent_resource (id)
) ENGINE = InnoDB;

CREATE TABLE task_event (
    id           BINARY(16)    NOT NULL PRIMARY KEY,
    task_id      BINARY(16)    NOT NULL,
    seq          BIGINT        NOT NULL,
    event_type   VARCHAR(32)   NOT NULL,
    payload_json JSON          NULL,
    created_at   DATETIME(6)   NOT NULL,
    UNIQUE KEY uk_task_event_seq (task_id, seq),
    KEY idx_task_event_task (task_id, created_at),
    CONSTRAINT fk_task_event_task FOREIGN KEY (task_id) REFERENCES download_task (id)
) ENGINE = InnoDB;
