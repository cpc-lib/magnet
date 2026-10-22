-- 移除全部外键约束：引用完整性改由业务代码控制（服务层先查后写 + JWT 用户上下文）
-- 保留原有索引（外键索引自动转为普通索引，查询性能不受影响）

ALTER TABLE resource_hash_alias DROP FOREIGN KEY fk_alias_resource;
ALTER TABLE torrent_file        DROP FOREIGN KEY fk_file_resource;
ALTER TABLE parse_job           DROP FOREIGN KEY fk_job_user;
ALTER TABLE parse_job           DROP FOREIGN KEY fk_job_resource;
ALTER TABLE download_task       DROP FOREIGN KEY fk_task_user;
ALTER TABLE download_task       DROP FOREIGN KEY fk_task_resource;
ALTER TABLE download_task_file  DROP FOREIGN KEY fk_task_file_task;
ALTER TABLE engine_session      DROP FOREIGN KEY fk_session_resource;
ALTER TABLE task_event          DROP FOREIGN KEY fk_task_event_task;
