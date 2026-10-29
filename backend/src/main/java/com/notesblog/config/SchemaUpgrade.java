package com.notesblog.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时的幂等结构升级。
 *
 * <p>项目的表结构由 {@code db/schema.sql} 管理（{@code ddl-auto: none}），但那份脚本挂在
 * MySQL 镜像的 {@code /docker-entrypoint-initdb.d/} 下，只在数据目录为空时执行一次。
 * 已有数据的库（Docker named volume、本地开发库）拿不到新增的列，于是由这里补齐。
 *
 * <p>{@code @Order(1)} 必须小于 seedAdmin 的 order：{@code ensureAdmin()} 里的
 * {@code findByUsername} 会 select 全字段，列没加上就会报 Unknown column。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SchemaUpgrade implements ApplicationRunner {

    private static final String COLUMN = "token_version";

    /** 已发布文章「有未发布修改」标记，见 db/migrations/003。 */
    private static final String NOTE_EDIT_COLUMN = "has_unpublished_edits";

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        upgradeTokenVersion();
        upgradeNoteEditFlag();
        upgradeUsageTables();
    }

    private void upgradeTokenVersion() {
        if (columnExists("users", COLUMN)) {
            return;
        }
        try {
            jdbc.execute("ALTER TABLE users ADD COLUMN " + COLUMN
                    + " BIGINT NOT NULL DEFAULT 0 AFTER role_name");
            log.info("schema_upgraded users.{}", COLUMN);
        } catch (DataAccessException e) {
            // 不静默跳过：列缺失时后续每条用户查询都会失败，早失败比晚失败好排查
            throw new IllegalStateException(
                    "无法自动为 users 表添加 " + COLUMN
                            + " 列，请手工执行 db/migrations/001_add_token_version.sql", e);
        }
    }

    /**
     * 已发布文章的「有未发布修改」标记。
     *
     * <p>同样不能跳过：实体里加了字段，列不存在时每条笔记查询都会报 Unknown column，
     * 整个列表页都打不开。
     */
    private void upgradeNoteEditFlag() {
        if (columnExists("notes", NOTE_EDIT_COLUMN)) {
            return;
        }
        try {
            jdbc.execute("ALTER TABLE notes ADD COLUMN " + NOTE_EDIT_COLUMN
                    + " BOOLEAN NOT NULL DEFAULT FALSE AFTER published_at");
            log.info("schema_upgraded notes.{}", NOTE_EDIT_COLUMN);
        } catch (DataAccessException e) {
            throw new IllegalStateException(
                    "无法自动为 notes 表添加 " + NOTE_EDIT_COLUMN
                            + " 列，请手工执行 db/migrations/003_add_note_edit_flag.sql", e);
        }
    }

    /**
     * 额度看板功能已移除，但历史表保留（数据不删）。
     * 这段只在「旧库连表都没有」时兜底，保证 db/schema.sql 与手工迁移两条路一致。
     */
    private void upgradeUsageTables() {
        if (tableExists("usage_account")) {
            return;
        }
        try {
            jdbc.execute("""
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
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            jdbc.execute("""
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
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            jdbc.execute("""
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
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            jdbc.execute("""
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
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            log.info("schema_upgraded usage tables (usage_account, usage_snapshot, usage_daily_spend, usage_collector_state)");
        } catch (DataAccessException e) {
            throw new IllegalStateException(
                    "无法自动创建订阅仪表盘表，请手工执行 db/migrations/002_add_usage_tables.sql", e);
        }
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = ?
                """, Integer.class, tableName);
        return count != null && count > 0;
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = ?
                   AND COLUMN_NAME = ?
                """, Integer.class, table, column);
        return count != null && count > 0;
    }
}
