-- 002_add_usage_tables.sql
--
-- 订阅额度仪表盘相关表：账号元数据、窗口使用快照、每日花费、采集器心跳。
--
-- ⚠️ 幂等执行或首次升级。通过 SchemaUpgrade 自动在启动时补齐。
--
-- 用法：
--   docker compose exec -T mysql mysql -u root -p notes_blog < db/migrations/002_add_usage_tables.sql
-- 或：
--   mysql -u notes -p notes_blog < db/migrations/002_add_usage_tables.sql

USE notes_blog;

-- ── 账号元数据（公网 DB 不存任何私密凭证）────────────────────
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

-- ── 每次采集的窗口快照（保留最新与历史趋势）────────────────
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

-- ── 按天花费与用量统计───────────────────────────────────────
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

-- ── 采集器状态与心跳─────────────────────────────────────────
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
