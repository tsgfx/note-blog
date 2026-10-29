-- 游客评论与每日问答配额。
--
-- 背景：站点原先只能管理员登录用，评论/问答都要身份。开放给游客后需要两样东西：
--   1. comments       —— 游客写的评论，默认可见，管理员可隐藏/删除
--   2. guest_daily_usage —— 游客每天问答次数的硬上限（按 IP 计）
--
-- 为什么配额要落库而不是放内存：RateLimiter 是固定窗口内存计数，进程重启就清零。
-- 「每天 5 条」是给游客的承诺，重启一次就白送一轮，反而更糟。所以按天落一行。
--
-- 正常情况不用手工执行：SchemaUpgrade 启动时会幂等建表。

CREATE TABLE IF NOT EXISTS comments (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  note_id    BIGINT       NOT NULL,
  -- 游客没有身份：统一显示「访客」，昵称留空。
  -- 这一列保留是为了以后想开昵称时不必再改表，当前恒为 NULL。
  nickname   VARCHAR(32)  DEFAULT NULL,
  content    VARCHAR(2000) NOT NULL,
  -- 展示状态：VISIBLE 默认可见，HIDDEN 仅管理员可见（软隐藏，不删数据）
  status     VARCHAR(16)  NOT NULL DEFAULT 'VISIBLE',
  -- 客户端 IP，用于限流与追溯。只给管理员看，不出现在任何公开响应里。
  client_ip  VARCHAR(64)  DEFAULT NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_comments_note (note_id, status, created_at),
  KEY idx_comments_created (created_at),
  CONSTRAINT fk_comments_note FOREIGN KEY (note_id) REFERENCES notes (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 游客每日问答次数。一天一行，主键就是 (ip, day)，
-- 这样「原子地加一并拿到新值」用 INSERT ... ON DUPLICATE KEY UPDATE 一条语句完成，
-- 不需要先查再改（先查再改在并发下会多放行）。
CREATE TABLE IF NOT EXISTS guest_daily_usage (
  client_ip VARCHAR(64) NOT NULL,
  day       DATE        NOT NULL,
  used      INT         NOT NULL DEFAULT 0,
  PRIMARY KEY (client_ip, day),
  KEY idx_gdu_day (day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
