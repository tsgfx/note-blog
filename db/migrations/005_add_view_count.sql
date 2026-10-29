-- 给笔记表增加阅读量计数
ALTER TABLE notes ADD COLUMN view_count BIGINT NOT NULL DEFAULT 0 AFTER has_unpublished_edits;
