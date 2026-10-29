package com.notesblog.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 游客每日问答配额。
 *
 * <p><b>为什么不复用 {@link RateLimiter}：</b>那个是固定窗口的内存计数，进程重启即清零，
 * 且窗口是一分钟级的。这里要的是「一天 5 条」这种跨重启仍然成立的承诺——
 * 每天一行落库，重启、发版都不影响。
 *
 * <p><b>为什么两道并行（IP + 浏览器）：</b>纯 IP 太粗暴，手机流量和不少宽带是
 * 共享出口 IP，一个人问完别人就问不了；纯浏览器（localStorage）清一下缓存就绕过。
 * 所以服务端按 IP 做<b>硬上限</b>（默认 15/天，比承诺的 5 条宽，专门留给共享 IP 的误伤余量），
 * 浏览器端按 5 条拦截并显示剩余次数。两者都过才放行。前端那道挡不住脚本，但要挡的是体验。
 *
 * <p><b>并发安全：</b>「加一并读回」用 {@code INSERT ... ON DUPLICATE KEY UPDATE}
 * 一条语句完成。先 SELECT 判断再 UPDATE 会在并发下多放行几个请求。
 */
@Slf4j
@Service
public class GuestQuotaService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * 服务端按 IP 的硬上限。
     *
     * <p>刻意大于对外承诺的每日条数：运营商 NAT 后面可能有整栋楼，
     * 严格按承诺值会让别人问不了；前端已经把单浏览器压到承诺值了，
     * 这里放宽只是给共享 IP 留余量，不是放宽给滥用者。
     */
    private final int ipDailyCeiling;

    /** 对外承诺的每日条数，前端据此显示「还能问几条」。 */
    private final int dailyAllowance;

    @Autowired
    public GuestQuotaService(JdbcTemplate jdbc,
                             @Value("${app.guest.ask-daily-allowance:5}") int dailyAllowance,
                             @Value("${app.guest.ask-ip-daily-ceiling:15}") int ipDailyCeiling) {
        this(jdbc, Clock.system(ZoneId.of("Asia/Shanghai")), dailyAllowance, ipDailyCeiling);
    }

    /** 供测试注入固定时钟 */
    GuestQuotaService(JdbcTemplate jdbc, Clock clock, int dailyAllowance, int ipDailyCeiling) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.dailyAllowance = dailyAllowance <= 0 ? 5 : dailyAllowance;
        // 兜底：IP 上限不能小于承诺值，否则前端说「还能问 3 条」、服务端已经拒了
        this.ipDailyCeiling = Math.max(ipDailyCeiling, this.dailyAllowance);
    }

    /** 对外承诺的每日条数（= 单浏览器上限），前端据此显示剩余。 */
    public int dailyAllowance() {
        return dailyAllowance;
    }

    /** 今日已经用掉的次数（用于在响应里告诉前端还可以问几条）。 */
    public int usedToday(String clientIp) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT used FROM guest_daily_usage WHERE client_ip = ? AND day = ?",
                Integer.class, clientIp, today());
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    /**
     * 尝试占用一次配额。
     *
     * <p>先原子加一，再看加完之后的值：只要 {@code used > ceiling} 就回退这次占用，
     * 保证不会因为「超了还留着」把计数越推越高（否则被刷时数字会一路涨到几十万）。
     *
     * @return 允许则带剩余次数，否则带需要等多久（到明天零点）
     */
    public Decision consume(String clientIp) {
        LocalDate today = today();

        // 原子加一。MySQL 的 ON DUPLICATE KEY UPDATE 在并发下是行级原子操作。
        jdbc.update("""
                INSERT INTO guest_daily_usage (client_ip, day, used) VALUES (?, ?, 1)
                ON DUPLICATE KEY UPDATE used = used + 1
                """, clientIp, today);

        int used = usedToday(clientIp);
        if (used <= ipDailyCeiling) {
            // 剩余按「对外承诺值」算给前端：IP 上限是内部兜底，对用户不该体现出来
            return new Decision(true, Math.max(0, dailyAllowance - used), 0);
        }

        // 超了：把这次多占的退回去，避免计数被刷到离谱的数字
        try {
            jdbc.update("""
                    UPDATE guest_daily_usage SET used = used - 1
                    WHERE client_ip = ? AND day = ? AND used > 0
                    """, clientIp, today);
        } catch (DataAccessException e) {
            log.warn("guest_quota_rollback_failed ip={} err={}", clientIp, e.getMessage());
        }
        return new Decision(false, 0, secondsUntilTomorrow());
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    /** 距离明天零点还有多少秒，用于 Retry-After。 */
    private long secondsUntilTomorrow() {
        ZoneId zone = clock.getZone();
        var now = java.time.ZonedDateTime.now(clock);
        var tomorrow = now.toLocalDate().plusDays(1).atStartOfDay(zone);
        return Math.max(1, java.time.Duration.between(now, tomorrow).getSeconds());
    }

    /**
     * @param allowed         是否放行
     * @param remaining       这轮之后还剩几次（allowed=false 时为 0）
     * @param retryAfterSeconds 被拒时距明天零点的秒数
     */
    public record Decision(boolean allowed, int remaining, long retryAfterSeconds) {
    }
}
