ALTER TABLE torrent_resource ADD COLUMN metadata_path VARCHAR(1024) NULL;
ALTER TABLE engine_session ADD COLUMN resume_path VARCHAR(1024) NULL;

CREATE TABLE file_export (
    id            BINARY(16) NOT NULL PRIMARY KEY,
    task_id       BINARY(16) NOT NULL,
    user_id       BINARY(16) NOT NULL,
    resource_id   BINARY(16) NOT NULL,
    file_index    INT NOT NULL,
    relative_path VARCHAR(1024) NOT NULL,
    target_path   VARCHAR(1024) NOT NULL,
    state         VARCHAR(16) NOT NULL,
    bytes         BIGINT NOT NULL DEFAULT 0,
    error_code    VARCHAR(32) NULL,
    error_message VARCHAR(1024) NULL,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    UNIQUE KEY uk_file_export_task_file (task_id, file_index),
    KEY idx_file_export_user (user_id, created_at),
    KEY idx_file_export_task (task_id, state)
) ENGINE = InnoDB;

CREATE TABLE resource_gc_candidate (
    resource_id  BINARY(16) NOT NULL PRIMARY KEY,
    state        VARCHAR(16) NOT NULL,
    candidate_at DATETIME(6) NOT NULL,
    not_before   DATETIME(6) NOT NULL,
    last_error   VARCHAR(1024) NULL,
    updated_at   DATETIME(6) NOT NULL
) ENGINE = InnoDB;
