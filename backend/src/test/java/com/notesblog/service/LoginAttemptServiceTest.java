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
 * 登录失败计数的边界。
 *
 * <p>重点不是"5 次之后锁住"这一条，而是三个容易写错的地方：
 * 锁到期要能自动解开、伪造 IP 不能绕过、登录成功后要把账清掉。
 */
class LoginAttemptServiceTest {

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptService service = new LoginAttemptService(clock);

    private void fail(int times, String username, String ip) {
        for (int i = 0; i < times; i++) {
            service.recordFailure(username, ip);
        }
    }

    @Test
    @DisplayName("同一用户名 + IP：第 5 次失败后锁定，返回剩余秒数")
    void locksAfterFiveFailures() {
        fail(LoginAttemptService.MAX_PER_KEY - 1, "admin", "1.1.1.1");
        assertThat(service.lockedSeconds("admin", "1.1.1.1")).isZero();

        assertThat(service.recordFailure("admin", "1.1.1.1")).isTrue();
        assertThat(service.lockedSeconds("admin", "1.1.1.1"))
                .isBetween(LoginAttemptService.LOCK_DURATION.toSeconds() - 5, LoginAttemptService.LOCK_DURATION.toSeconds());
    }

    @Test
    @DisplayName("锁定到期后自动解除，不用手工清理")
    void unlockAfterDuration() {
        fail(LoginAttemptService.MAX_PER_KEY, "admin", "1.1.1.1");
        assertThat(service.lockedSeconds("admin", "1.1.1.1")).isPositive();

        clock.advance(LoginAttemptService.LOCK_DURATION.plusSeconds(1));
        assertThat(service.lockedSeconds("admin", "1.1.1.1")).isZero();
    }

    @Test
    @DisplayName("换个 IP 不能绕过：同一用户名的全局上限兜底")
    void rotatingIpDoesNotBypass() {
        // 每个 IP 只试 3 次（不到单个 key 的 5 次），累计到全局上限
        int ips = LoginAttemptService.MAX_PER_USERNAME / 3 + 1;
        for (int i = 0; i < ips; i++) {
            fail(3, "admin", "10.0.0." + i);
        }
        // 换一个全新的 IP 也进不去——这正是伪造 X-Real-IP 想达到的效果
        assertThat(service.lockedSeconds("admin", "192.168.9.9")).isPositive();
    }

    @Test
    @DisplayName("用户名大小写不敏感，Admin 与 admin 共用一份计数")
    void usernameIsCaseInsensitive() {
        fail(3, "ADMIN", "1.1.1.1");
        fail(2, "admin", "1.1.1.1");
        assertThat(service.lockedSeconds("Admin", "1.1.1.1")).isPositive();
    }

    @Test
    @DisplayName("登录成功清掉计数，之前的失败不再累计")
    void successClearsCounters() {
        fail(LoginAttemptService.MAX_PER_KEY - 1, "admin", "1.1.1.1");
        service.clear("admin", "1.1.1.1");
        assertThat(service.lockedSeconds("admin", "1.1.1.1")).isZero();

        fail(LoginAttemptService.MAX_PER_KEY - 1, "admin", "1.1.1.1");
        assertThat(service.lockedSeconds("admin", "1.1.1.1"))
                .as("清空后重新累计，4 次不该锁")
                .isZero();
    }

    @Test
    @DisplayName("窗口之外的旧失败不再累计，隔很久失败一次不会突然被锁")
    void oldFailuresExpire() {
        fail(LoginAttemptService.MAX_PER_KEY - 1, "admin", "1.1.1.1");
        clock.advance(LoginAttemptService.LOCK_DURATION.plusSeconds(1));

        fail(1, "admin", "1.1.1.1");
        assertThat(service.lockedSeconds("admin", "1.1.1.1")).isZero();
    }

    /** 可手动推进的时钟，避免测试里 sleep */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-23T00:00:00Z");

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
