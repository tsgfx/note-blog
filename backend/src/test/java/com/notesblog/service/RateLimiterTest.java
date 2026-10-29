package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 固定窗口限流的边界。
 *
 * <p>要守住三件事：正好第 N 次放行、第 N+1 次被拦、窗口翻篇后重新放行。
 * 差一错误是这种计数器最容易犯的毛病。
 */
class RateLimiterTest {

    private final MutableClock clock = new MutableClock();
    private final RateLimiter limiter = new RateLimiter(clock);

    private static final Duration MINUTE = Duration.ofMinutes(1);

    @Test
    @DisplayName("配额内全部放行，超出的第一次被拦")
    void allowsUpToLimit() {
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.acquire("k", 10, MINUTE).allowed()).as("第 %s 次应放行", i + 1).isTrue();
        }

        RateLimiter.Decision blocked = limiter.acquire("k", 10, MINUTE);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("不同 key 互不影响：问答打满不影响搜索")
    void keysAreIndependent() {
        for (int i = 0; i < 10; i++) {
            limiter.acquire("ask|1.1.1.1", 10, MINUTE);
        }
        assertThat(limiter.acquire("ask|1.1.1.1", 10, MINUTE).allowed()).isFalse();
        assertThat(limiter.acquire("search|1.1.1.1", 10, MINUTE).allowed()).isTrue();
    }

    @Test
    @DisplayName("窗口翻篇后重新计数")
    void resetsAfterWindow() {
        for (int i = 0; i < 10; i++) {
            limiter.acquire("k", 10, MINUTE);
        }
        assertThat(limiter.acquire("k", 10, MINUTE).allowed()).isFalse();

        clock.advance(MINUTE.plusSeconds(1));
        assertThat(limiter.acquire("k", 10, MINUTE).allowed()).isTrue();
    }

    @Test
    @DisplayName("等待秒数向上取整，不会返回 0")
    void retryAfterIsAtLeastOneSecond() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire("k", 3, MINUTE);
        }
        clock.advance(Duration.ofSeconds(59).plusMillis(900));

        RateLimiter.Decision decision = limiter.acquire("k", 3, MINUTE);
        assertThat(decision.allowed()).isFalse();
        // 只剩 100ms 也要说 1 秒，否则客户端会立刻重试再被拒
        assertThat(decision.retryAfterSeconds()).isEqualTo(1);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-23T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
