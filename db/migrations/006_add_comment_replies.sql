-- 006: 评论回复
--
-- parent_id 指向被回复的顶层评论（只做一层嵌套）；author_reply 标记「这是博主在回复」，
-- 公开页据此换称呼（博主/访客）与底色。
--
-- 老库由后端启动时的 SchemaUpgrade 幂等补齐，这个文件是给新库与手工执行用的。

ALTER TABLE comments
    ADD COLUMN parent_id    BIGINT  DEFAULT NULL AFTER note_id,
    ADD COLUMN author_reply BOOLEAN NOT NULL DEFAULT FALSE AFTER status,
    ADD KEY idx_comments_parent (parent_id, status, created_at);
