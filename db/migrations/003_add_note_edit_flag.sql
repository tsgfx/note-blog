-- 给已发布文章加「有未发布修改」标记。
--
-- 背景：发布是直接改 status，公开页读的就是同一行 content_md。
-- 于是「改个错字」等于立即改线上，读者可能看到改到一半的半成品。
-- 这个标记不改读取路径，只让列表和详情能提示「线上还是旧的，改完记得重新发布」。
--
-- 正常情况不用手工执行：SchemaUpgrade 启动时会幂等补列。

ALTER TABLE notes
  ADD COLUMN has_unpublished_edits BOOLEAN NOT NULL DEFAULT FALSE AFTER published_at;
