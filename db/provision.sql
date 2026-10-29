-- ─────────────────────────────────────────────────────────────
-- 建库建账号（以 root 执行一次）
--   mysql -u root -p < db/provision.sql
--   然后建表：mysql -u root -p < db/schema.sql
-- ─────────────────────────────────────────────────────────────

CREATE DATABASE IF NOT EXISTS notes_blog
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 应用专用账号（开发用密码，生产请改）
CREATE USER IF NOT EXISTS 'notes'@'localhost' IDENTIFIED BY 'notes_dev_pw';
GRANT ALL PRIVILEGES ON notes_blog.* TO 'notes'@'localhost';
FLUSH PRIVILEGES;
