package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.notesblog.config.SecurityProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** JWT 签发与校验。纯单元测试，不启动 Spring。 */
class JwtServiceTest {

    private static final String SECRET = "test-secret-at-least-32-bytes-long-000000";
    private static final long HOURS = 24;

    private JwtService service(long hours) {
        return new JwtService(new SecurityProperties(SECRET, hours, "admin", "pw"));
    }

    private JwtService service() {
        return service(HOURS);
    }

    @Test
    @DisplayName("签发后校验能拿回完整身份")
    void issueThenVerifyRoundTripsIdentity() {
        String token = service().issue("admin", "ADMIN", 7L);

        assertThat(service().verify(token)).hasValueSatisfying(principal -> {
            assertThat(principal.username()).isEqualTo("admin");
            assertThat(principal.role()).isEqualTo("ADMIN");
            assertThat(principal.tokenVersion()).isEqualTo(7L);
        });
    }

    @Test
    @DisplayName("签名被篡改后拒绝")
    void verifyRejectsTamperedSignature() {
        String token = service().issue("admin", "ADMIN", 0L);
        String tampered = token.substring(0, token.length() - 2) + "xy";

        assertThat(service().verify(tampered)).isEmpty();
    }

    @Test
    @DisplayName("过期令牌拒绝")
    void verifyRejectsExpiredToken() {
        // 负数有效期 = 签发即过期
        String expired = service(-1).issue("admin", "ADMIN", 0L);

        assertThat(service().verify(expired)).isEmpty();
    }

    @Test
    @DisplayName("没有 tv claim 的旧令牌按版本 0 处理")
    void verifyTreatsMissingVersionClaimAsZero() {
        String legacy = Jwts.builder()
                .subject("admin")
                .claims(Map.of("role", "ADMIN"))
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(service().verify(legacy)).hasValueSatisfying(principal ->
                assertThat(principal.tokenVersion()).isZero());
    }

    @Test
    @DisplayName("垃圾字符串返回空而不是抛异常")
    void verifyRejectsGarbageWithoutThrowing() {
        assertThat(service().verify("not-a-jwt")).isEmpty();
        assertThat(service().verify("")).isEmpty();
    }
}
