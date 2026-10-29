package com.notesblog.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 登录失败计数与锁定。
 *
 * <p>在此之前 {@code /api/auth/login} 没有任何频率控制，密码是唯一防线：
 * 脚本可以全天候试错，而日志里只留下一条条 LOGIN_FAILED。
 *
 * <p><b>为什么是两级而不是只按 IP：</b>IP 来自请求头（{@code X-Real-IP}），
 * 而它并非不可伪造——绕过 nginx 直连 8081 就能随意填写。只按「用户名 + IP」计数的话，
 * 换个伪造的 IP 就等于重置计数。所以再加一层「按用户名」的全局上限：
 * 单机只有一个管理员账号，只有它值得被暴力猜，把这一个账号守住就够了。
 *
 * <p>状态放在内存里，单机部署不需要 Redis；进程重启即清零，这可以接受
 * （重启意味着服务不可用，攻击者也拿不到收益）。代价是**多实例部署时每个实例各算各的**，
 * 真要做多实例再换成共享存储。
 */
@Service
public class LoginAttemptService {

    /** 同一「用户名 + IP」累计失败多少次后锁定。公开是为了让测试与文档引用同一份数值 */
    public static final int MAX_PER_KEY = 5;

    /** 同一用户名跨 IP 的失败上限，防伪造 IP 绕过 */
    public static final int MAX_PER_USERNAME = 15;

    /** 锁定时长，也是计数窗口：超过这个时长的旧失败不再累计 */
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    /** 条目上限，防止被大量随机用户名刷爆内存 */
    private static final int MAX_ENTRIES = 10_000;

    private final Clock clock;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptService() {
        this(Clock.systemUTC());
    }

    /** 供测试注入固定时钟；两个构造器并存，所以上面那个标了 @Autowired */
    LoginAttemptService(Clock clock) {
        this.clock = clock;
    }

    /**
     * 还要等多少秒才允许再试。0 表示未锁定。
     *
     * @return 剩余锁定秒数，取「用户名 + IP」与「用户名」两桶中较长的那个
     */
    public long lockedSeconds(String username, String ip) {
        Instant now = clock.instant();
        long byKey = remainingSeconds(attempts.get(key(username, ip)), now);
        long byUser = remainingSeconds(attempts.get(userKey(username)), now);
        return Math.max(byKey, byUser);
    }

    /**
     * 记一次失败。
     *
     * @return 本次是否刚刚触发锁定——调用方据此写一条审计，
     *     而不是每个被拒的请求都写一行（否则日志会被刷爆）
     */
    public boolean recordFailure(String username, String ip) {
        Instant now = clock.instant();
        boolean justLocked = bump(key(username, ip), now, MAX_PER_KEY);
        // 用 |= 而不是短路，两个桶都要记上
        justLocked |= bump(userKey(username), now, MAX_PER_USERNAME);
        purgeIfCrowded(now);
        return justLocked;
    }

    /** 登录成功后清掉计数：用户已经证明自己知道密码，不该继续背着失败的账 */
    public void clear(String username, String ip) {
        attempts.remove(key(username, ip));
        attempts.remove(userKey(username));
    }

    // ── 内部实现 ──────────────────────────────────────

    private boolean bump(String key, Instant now, int limit) {
        // 用 compute 而不是 get→改→put：并发请求下后者会丢计数
        boolean[] justLocked = {false};
        attempts.compute(key, (ignored, previous) -> {
            Attempt attempt = previous == null || isStale(previous, now) ? Attempt.fresh(now) : previous;
            attempt.count++;
            if (attempt.lockedUntil == null && attempt.count >= limit) {
                attempt.lockedUntil = now.plus(LOCK_DURATION);
                justLocked[0] = true;
            }
            return attempt;
        });
        return justLocked[0];
    }

    private long remainingSeconds(Attempt attempt, Instant now) {
        if (attempt == null || attempt.lockedUntil == null) return 0;
        long seconds = Duration.between(now, attempt.lockedUntil).toSeconds();
        return Math.max(seconds, 0);
    }

    /** 锁定已过期，或上次失败距今超过一个窗口，就重新开始计数 */
    private boolean isStale(Attempt attempt, Instant now) {
        if (attempt.lockedUntil != null) return now.isAfter(attempt.lockedUntil);
        return now.isAfter(attempt.windowStart.plus(LOCK_DURATION));
    }

    private void purgeIfCrowded(Instant now) {
        if (attempts.size() <= MAX_ENTRIES) return;
        attempts.entrySet().removeIf(entry -> isStale(entry.getValue(), now));
    }

    private String key(String username, String ip) {
        return "a|" + normalize(username) + "|" + ip;
    }

    private String userKey(String username) {
        return "u|" + normalize(username);
    }

    /** 用户名大小写不敏感，避免 Admin / admin 各算一份 */
    private String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    private static final class Attempt {
        int count;
        Instant windowStart;
        Instant lockedUntil;

        static Attempt fresh(Instant now) {
            Attempt attempt = new Attempt();
            attempt.windowStart = now;
            return attempt;
        }
    }
}
