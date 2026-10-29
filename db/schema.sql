-- ─────────────────────────────────────────────────────────────
-- 私人笔记博客 · 数据库结构
-- 用法：mysql -u root -p < db/schema.sql
-- 需要 MySQL 9.0+（VECTOR 类型）
-- ─────────────────────────────────────────────────────────────

CREATE DATABASE IF NOT EXISTS notes_blog
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE notes_blog;

-- ── 用户（单管理员）──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  username      VARCHAR(64)  NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  display_name  VARCHAR(64)  DEFAULT NULL,
  role_name     VARCHAR(16)  NOT NULL DEFAULT 'ADMIN',
  -- token_version：改密码时 +1，让所有旧 JWT 立即失效。
  -- 定义必须与 db/migrations/001_add_token_version.sql 及 SchemaUpgrade 保持一致。
  token_version BIGINT       NOT NULL DEFAULT 0,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 笔记 / 文章（status 区分草稿与已发布）────────────────────
CREATE TABLE IF NOT EXISTS notes (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  title        VARCHAR(255) NOT NULL,
  slug         VARCHAR(255) NOT NULL,
  summary      VARCHAR(512) DEFAULT NULL,
  content_md   LONGTEXT     NOT NULL,
  status       VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
  source_path  VARCHAR(512) DEFAULT NULL,
  published_at DATETIME     DEFAULT NULL,
  -- 已发布文章被改过、但还没重新发布：公开页仍是旧内容
  has_unpublished_edits BOOLEAN NOT NULL DEFAULT FALSE,
  view_count   BIGINT       NOT NULL DEFAULT 0,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_notes_slug (slug),
  KEY idx_notes_status (status, published_at),
  FULLTEXT KEY ft_notes (title, content_md)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 标签 ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tags (
  id   BIGINT      NOT NULL AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tags_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS note_tags (
  note_id BIGINT NOT NULL,
  tag_id  BIGINT NOT NULL,
  PRIMARY KEY (note_id, tag_id),
  KEY idx_nt_tag (tag_id),
  CONSTRAINT fk_nt_note FOREIGN KEY (note_id) REFERENCES notes (id) ON DELETE CASCADE,
  CONSTRAINT fk_nt_tag  FOREIGN KEY (tag_id)  REFERENCES tags  (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 语义检索分片（含 1024 维向量）────────────────────────────
-- embedding 未映射到实体，读写走原生 SQL：
--   STRING_TO_VECTOR('[0.1,0.2,...]')  /  VECTOR_DISTANCE(a, b, 'COSINE')
CREATE TABLE IF NOT EXISTS note_chunks (
  id        BIGINT       NOT NULL AUTO_INCREMENT,
  note_id   BIGINT       NOT NULL,
  heading   VARCHAR(255) DEFAULT NULL,
  ordinal   INT          NOT NULL DEFAULT 0,
  content   MEDIUMTEXT   NOT NULL,
  embedding VECTOR(1024) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_chunk_note (note_id),
  CONSTRAINT fk_chunk_note FOREIGN KEY (note_id) REFERENCES notes (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 操作审计日志 ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS audit_logs (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  username   VARCHAR(64)  NOT NULL,
  action     VARCHAR(32)  NOT NULL,
  target     VARCHAR(255) DEFAULT NULL,
  detail     VARCHAR(500) DEFAULT NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 游客评论 ──────────────────────────────────────────────────
-- 默认可见，管理员可隐藏（status=HIDDEN，软隐藏）或删除。
-- nickname 当前恒为 NULL（游客统一显示「访客」），留列是为了以后想开昵称时不用改表。
-- 定义必须与 db/migrations/004 及 SchemaUpgrade 保持一致。
CREATE TABLE IF NOT EXISTS comments (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  note_id    BIGINT       NOT NULL,
  nickname   VARCHAR(32)  DEFAULT NULL,
  content    VARCHAR(2000) NOT NULL,
  status     VARCHAR(16)  NOT NULL DEFAULT 'VISIBLE',
  client_ip  VARCHAR(64)  DEFAULT NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_comments_note (note_id, status, created_at),
  KEY idx_comments_created (created_at),
  CONSTRAINT fk_comments_note FOREIGN KEY (note_id) REFERENCES notes (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 游客每日问答配额 ─────────────────────────────────────────
-- 主键 (client_ip, day)：用 INSERT ... ON DUPLICATE KEY UPDATE 一条语句
-- 原子地「加一并读回」，避免先查再改在并发下多放行。
-- 与内存版 RateLimiter 的区别：这个落库，进程重启不清零。
CREATE TABLE IF NOT EXISTS guest_daily_usage (
  client_ip VARCHAR(64) NOT NULL,
  day       DATE        NOT NULL,
  used      INT         NOT NULL DEFAULT 0,
  PRIMARY KEY (client_ip, day),
  KEY idx_gdu_day (day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 订阅额度仪表盘：账号元数据 ──────────────────────────────
-- 注意：额度看板功能已于 2026-10-29 移除，这 4 张表保留但不再生效，
-- 只为不丢历史数据；新部署不需要它们，删表也不影响应用。
CREATE TABLE IF NOT EXISTS usage_account (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  account_key VARCHAR(64)  NOT NULL,
  provider    VARCHAR(32)  NOT NULL,
  alias       VARCHAR(64)  NOT NULL,
  enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
  sort_order  INT          NOT NULL DEFAULT 0,
  config_json VARCHAR(512) DEFAULT NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_account_key (account_key),
  KEY idx_usage_account_order (enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 订阅额度仪表盘：窗口使用快照 ────────────────────────────
CREATE TABLE IF NOT EXISTS usage_snapshot (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  account_key  VARCHAR(64)  NOT NULL,
  window_key   VARCHAR(64)  NOT NULL,
  label        VARCHAR(64)  NOT NULL,
  used_percent DOUBLE       DEFAULT NULL,
  resets_at    DATETIME     DEFAULT NULL,
  plan         VARCHAR(64)  DEFAULT NULL,
  raw_data     TEXT         DEFAULT NULL,
  captured_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_snap_acc_win_time (account_key, window_key, captured_at),
  KEY idx_snap_captured (captured_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 订阅额度仪表盘：按天花费与用量 ──────────────────────────
CREATE TABLE IF NOT EXISTS usage_daily_spend (
  id          BIGINT         NOT NULL AUTO_INCREMENT,
  account_key VARCHAR(64)    NOT NULL,
  day         DATE           NOT NULL,
  cost        DECIMAL(10, 4) NOT NULL DEFAULT 0.0000,
  tokens_in   BIGINT         NOT NULL DEFAULT 0,
  tokens_out  BIGINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_spend_acc_day (account_key, day),
  KEY idx_spend_day (day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 订阅额度仪表盘：采集器心跳与状态 ────────────────────────
CREATE TABLE IF NOT EXISTS usage_collector_state (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  collector_id VARCHAR(64)  NOT NULL,
  role         VARCHAR(32)  NOT NULL,
  version      VARCHAR(32)  NOT NULL,
  status       VARCHAR(32)  NOT NULL DEFAULT 'OK',
  message      VARCHAR(512) DEFAULT NULL,
  last_seen_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_collector_id (collector_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
