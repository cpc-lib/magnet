-- P0+P1 核心数据模型（规格 §8 精简版）
-- UUID 统一 BINARY(16)；哈希按规格 btih 20 字节 / btmh 32 字节，独立唯一且允许空值

CREATE TABLE app_user (
    id            BINARY(16)     NOT NULL PRIMARY KEY,
    username      VARCHAR(32)    NOT NULL,
    password_hash VARCHAR(100)   NOT NULL,
    role          VARCHAR(16)    NOT NULL,
    status        VARCHAR(16)    NOT NULL,
    created_at    DATETIME(6)    NOT NULL,
    UNIQUE KEY uk_app_user_username (username)
) ENGINE = InnoDB;

CREATE TABLE torrent_resource (
    id              BINARY(16)   NOT NULL PRIMARY KEY,
    canonical_key   VARCHAR(80)  NOT NULL,
    btih            BINARY(20)   NULL,
    btmh            BINARY(32)   NULL,
    version_type    VARCHAR(8)   NOT NULL,
    name            VARCHAR(512) NULL,
    piece_length    BIGINT       NULL,
    total_bytes     BIGINT       NULL,
    private_flag    TINYINT(1)   NOT NULL DEFAULT 0,
    metadata_source VARCHAR(16)  NOT NULL,
    trackers        JSON         NOT NULL,
    file_count      INT          NULL,
    created_at      DATETIME(6)  NOT NULL,
    UNIQUE KEY uk_resource_canonical (canonical_key),
    UNIQUE KEY uk_resource_btih (btih),
    UNIQUE KEY uk_resource_btmh (btmh)
) ENGINE = InnoDB;

CREATE TABLE resource_hash_alias (
    hash_type   VARCHAR(8)    NOT NULL,
    hash_bytes  VARBINARY(32) NOT NULL,
    resource_id BINARY(16)    NOT NULL,
    PRIMARY KEY (hash_type, hash_bytes),
    CONSTRAINT fk_alias_resource FOREIGN KEY (resource_id) REFERENCES torrent_resource (id)
) ENGINE = InnoDB;

CREATE TABLE torrent_file (
    id             BINARY(16)    NOT NULL PRIMARY KEY,
    resource_id    BINARY(16)    NOT NULL,
    file_index     INT           NOT NULL,
    relative_path  VARCHAR(1024) NOT NULL,
    size_bytes     BIGINT        NOT NULL,
    v2_pieces_root CHAR(64)      NULL,
    UNIQUE KEY uk_file_resource_index (resource_id, file_index),
    CONSTRAINT fk_file_resource FOREIGN KEY (resource_id) REFERENCES torrent_resource (id)
) ENGINE = InnoDB;

CREATE TABLE parse_job (
    id            BINARY(16)     NOT NULL PRIMARY KEY,
    user_id       BINARY(16)     NOT NULL,
    resource_id   BINARY(16)     NOT NULL,
    input_type    VARCHAR(8)     NOT NULL,
    input_hash    VARBINARY(32)  NULL,
    input_uri     VARCHAR(8192)  NULL,
    input_path    VARCHAR(1024)  NULL,
    display_hint  VARCHAR(512)   NULL,
    status        VARCHAR(16)    NOT NULL,
    error_code    VARCHAR(32)    NULL,
    error_message VARCHAR(1024)  NULL,
    version       BIGINT         NOT NULL DEFAULT 0,
    created_at    DATETIME(6)    NOT NULL,
    updated_at    DATETIME(6)    NOT NULL,
    KEY idx_parse_job_user (user_id, created_at),
    KEY idx_parse_job_status_updated (status, updated_at),
    KEY idx_parse_job_user_resource (user_id, resource_id),
    CONSTRAINT fk_job_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_job_resource FOREIGN KEY (resource_id) REFERENCES torrent_resource (id)
) ENGINE = InnoDB;
