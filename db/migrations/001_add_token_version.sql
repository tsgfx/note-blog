-- 001_add_token_version.sql
--
-- 给 users 表加 token_version 列，用于"改密码后旧 JWT 立即失效"。
--
-- ⚠️ 只执行一次。MySQL 不支持 ADD COLUMN IF NOT EXISTS（那是 MariaDB 的语法），
--    重复执行会报 ERROR 1060 (42S21): Duplicate column name 'token_version'。
--    看到这个错误说明已经升级过，忽略即可。
--
-- 正常情况下不需要手工跑这个脚本：后端启动时 SchemaUpgrade 会先查
-- information_schema，缺列才自动 ALTER。此脚本用于排障，或当应用账号
-- 没有 ALTER 权限时由 DBA 执行。
--
-- 用法：
--   docker compose exec -T mysql mysql -u root -p notes_blog < db/migrations/001_add_token_version.sql
-- 或：
--   mysql -u notes -p notes_blog < db/migrations/001_add_token_version.sql

ALTER TABLE users
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0 AFTER role_name;
