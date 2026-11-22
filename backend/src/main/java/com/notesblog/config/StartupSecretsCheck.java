package com.notesblog.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 启动即校验密钥，别让「默认口令」悄悄跑进生产。
 *
 * <p>以前 {@code application.yml} 给 {@code security.jwt-secret} 和
 * {@code security.admin-password} 都留了可用的默认值（{@code change-me-now} 之流）。
 * 这等于把所有保护都变成默认关闭：忘了设环境变量，服务照常启动，
 * 任何人都能用文档里的口令登录，任何 JWT 都能用公开的密钥伪造。
 * 「配置错就起不来」比「配置错但能跑」安全得多。
 *
 * <p>本地开发用 {@code --spring.profiles.active=dev}（见 {@code application-dev.yml}），
 * 生产由 {@code docker-compose.prod.yml} 用 {@code ${JWT_SECRET:?}} 强制注入。
 */
@Slf4j
@Component
public class StartupSecretsCheck {

    private final SecurityProperties security;

    public StartupSecretsCheck(SecurityProperties security) {
        this.security = security;
    }

    @PostConstruct
    void verify() {
        String secret = security.jwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET 未设置：生产用 docker compose 注入，本地开发请加 --spring.profiles.active=dev");
        }
        if (secret.length() < 32) {
            throw new IllegalStateException("JWT_SECRET 太短（" + secret.length() + " 字符）：HMAC-SHA256 请至少 32 字符");
        }
        String password = security.adminPassword();
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "ADMIN_PASSWORD 未设置：生产用 docker compose 注入，本地开发请加 --spring.profiles.active=dev");
        }
        log.info("启动密钥校验通过（JWT secret {} 字符）", secret.length());
    }
}
