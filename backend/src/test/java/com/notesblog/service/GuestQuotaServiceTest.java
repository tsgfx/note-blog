package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 游客每日问答配额。
 *
 * <p>要守的是「跨重启仍然成立」：这是给游客的承诺，内存计数重启就清零，
 * 反而比不限制更糟（等于告诉对方「重启一次白送一轮」）。
 */
class GuestQuotaServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private JdbcTemplate jdbc;
    private Clock clock;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        // 固定在下午，距离明天零点还有 9 小时（用于断言 Retry-After 非零）
        clock = Clock.fixed(Instant.parse("2026-10-29T07:00:00Z"), ZONE); // 15:00 北京时间
    }

    private GuestQuotaService service(int allowance, int ceiling) {
        return new GuestQuotaService(jdbc, clock, allowance, ceiling);
    }

    private void usedTodayIs(int used) {
        when(jdbc.queryForList(anyString(), eq(Integer.class), any(), any()))
                .thenReturn(List.of(used));
    }

    @Test
    @DisplayName("未超过承诺额度时放行，并给出剩余次数")
    void allowsWithinAllowance() {
        usedTodayIs(2);
        GuestQuotaService.Decision d = service(5, 15).consume("1.2.3.4");

        assertThat(d.allowed()).isTrue();
        assertThat(d.remaining()).isEqualTo(3);
        assertThat(d.retryAfterSeconds()).isZero();
    }

    @Test
    @DisplayName("超过 IP 硬上限时拒绝，且把这次多占的退回去")
    void rejectsBeyondCeilingAndRollsBack() {
        usedTodayIs(16);
        GuestQuotaService.Decision d = service(5, 15).consume("1.2.3.4");

        assertThat(d.allowed()).isFalse();
        assertThat(d.remaining()).isZero();
        // 距明天零点约 9 小时
        assertThat(d.retryAfterSeconds()).isBetween(8 * 3600L, 10 * 3600L);
        // 关键：拒绝后必须回退，否则被刷时计数会一路涨到几十万
        verify(jdbc).update(
                org.mockito.ArgumentMatchers.contains("used = used - 1"),
                eq("1.2.3.4"), any());
    }

    @Test
    @DisplayName("IP 上限之外仍按承诺值算剩余：共享出口 IP 不该让用户看到别人的用量")
    void remainingUsesAllowanceNotCeiling() {
        // 同一 WiFi 下别人已经问了 4 条，本浏览器第一条的剩余应是 0 而不是 11
        usedTodayIs(5);
        GuestQuotaService.Decision d = service(5, 15).consume("1.2.3.4");

        assertThat(d.allowed()).isTrue();  // 还没到 IP 上限 15
        assertThat(d.remaining()).isZero(); // 但承诺的 5 条已经用完
    }

    @Test
    @DisplayName("IP 上限配得比承诺值小时自动抬到承诺值，避免前端说还剩 3 条服务端已拒")
    void ceilingNeverBelowAllowance() {
        usedTodayIs(6);
        // 故意把 ceiling 配小（2 < 5）：构造器应抬到 5
        GuestQuotaService.Decision d = service(5, 2).consume("1.2.3.4");

        assertThat(d.allowed()).isFalse();
        verify(jdbc).update(
                org.mockito.ArgumentMatchers.contains("used = used - 1"),
                eq("1.2.3.4"), any());
    }

    @Test
    @DisplayName("配额落库：加一用的是 ON DUPLICATE KEY UPDATE，不是先查再改")
    void consumeIsAtomic() {
        usedTodayIs(1);
        service(5, 15).consume("1.2.3.4");

        verify(jdbc).update(
                org.mockito.ArgumentMatchers.contains("ON DUPLICATE KEY UPDATE"),
                eq("1.2.3.4"), any());
    }

    @Test
    @DisplayName("每天一行：日期取的是配置时区的当天，不是 UTC 当天")
    void dayUsesConfiguredZone() {
        usedTodayIs(0);
        service(5, 15).consume("1.2.3.4");

        // 15:00 北京时间 = 07:00 UTC，同一天；但如果用 UTC 判定，
        // 北京时间 00:30（前一天 16:30 UTC）会被算成前一天，额度会错位
        verify(jdbc).update(anyString(), eq("1.2.3.4"),
                eq(java.time.LocalDate.of(2026, 10, 29)));
    }
}
