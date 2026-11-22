package com.notesblog.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「配置错就起不来」这条性质的护栏。
 *
 * <p>曾经的默认值是 {@code change-me-now} 和一段仓库里公开的字符串——
 * 忘了设环境变量，服务照常启动、所有人用文档口令就能登录。
 */
class StartupSecretsCheckTest {

    private static final String OK_SECRET = "a".repeat(48);

    private StartupSecretsCheck check(String secret, String password) {
        return new StartupSecretsCheck(new SecurityProperties(secret, 24, "admin", password));
    }

    @Test
    @DisplayName("正常配置通过")
    void validPasses() {
        assertThatCode(() -> check(OK_SECRET, "pw").verify()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("缺 JWT_SECRET 直接启动失败")
    void blankSecretFails() {
        assertThatThrownBy(() -> check("", "pw").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> check(null, "pw").verify())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("JWT_SECRET 短于 32 字符拒绝启动")
    void shortSecretFails() {
        assertThatThrownBy(() -> check("short-secret", "pw").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("太短");
    }

    @Test
    @DisplayName("缺 ADMIN_PASSWORD 直接启动失败")
    void blankPasswordFails() {
        assertThatThrownBy(() -> check(OK_SECRET, "").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD");
    }
}
