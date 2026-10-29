package com.notesblog.service;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 按 key 的固定窗口限流。
 *
 * <p>用于 {@code /api/ask} 与 {@code /api/search}：这两个接口每次调用都要么烧 LLM token
 * （问答），要么做一次向量检索（搜索）。没有上限时，一个脚本就能把配额打满。
 *
 * <p><b>为什么是固定窗口而不是滑动窗口：</b>固定窗口有边界突刺（一分钟的最末与下一分钟的开头
 * 各打满一次，实际两倍速率），但它省内存、行为可预测、报错信息里的「还要等几秒」是准的。
 * 这里的目的是"防脚本"，不是"精确计费"——为此维护每个请求的时间戳队列不值得。
 *
 * <p>状态在内存里，单机部署够用；进程重启即清零。
 */
@Service
public class RateLimiter {

    /** 条目上限，防止被大量随机 IP 刷爆内存 */
    private static final int MAX_ENTRIES = 10_000;

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public RateLimiter() {
        this(Clock.systemUTC());
    }

    /** 供测试注入固定时钟 */
    RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * 记一次请求并判断是否放行。
     *
     * @return {@code allowed=true} 表示放行；否则 {@code retryAfterSeconds} 是可以再试的等待秒数
     */
    public Decision acquire(String key, int limit, Duration window) {
        long now = clock.millis();
        long windowMillis = window.toMillis();

        Window current = windows.compute(key, (ignored, previous) -> {
            if (previous == null || now - previous.startMillis >= windowMillis) {
                return new Window(now, 1);
            }
            return new Window(previous.startMillis, previous.count + 1);
        });

        if (current.count <= limit) {
            return new Decision(true, 0);
        }
        purgeIfCrowded(now);
        long resetAt = current.startMillis + windowMillis;
        // 向上取整：说「还要等 0 秒」比说「等 1 秒」更容易让人反复重试
        return new Decision(false, Math.max(1, (resetAt - now + 999) / 1000));
    }

    private void purgeIfCrowded(long now) {
        if (windows.size() <= MAX_ENTRIES) return;
        // 窗口最长按 1 小时粗算，超过 1 小时没动过的条目一定过期
        long staleBefore = now - Duration.ofHours(1).toMillis();
        windows.entrySet().removeIf(entry -> entry.getValue().startMillis < staleBefore);
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private record Window(long startMillis, int count) {
    }
}
