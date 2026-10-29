package com.notesblog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import com.notesblog.service.AuthService;

/**
 * 排除 UserDetailsServiceAutoConfiguration：本项目用自建 JWT 过滤器鉴权，
 * 该自动配置只会在启动日志里打印一个用不到的随机密码，容易被误认为存在默认账号。
 * 管理员账号由 seedAdmin 按环境变量 ADMIN_USERNAME / ADMIN_PASSWORD 创建。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableAsync
public class NotesBlogApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotesBlogApplication.class, args);
    }

    /**
     * 创建管理员账号。{@code @Order(2)} 保证在 {@link com.notesblog.config.SchemaUpgrade}
     * 之后执行——否则查询用户表时可能还没有 token_version 列。
     */
    @Bean
    @Order(2)
    CommandLineRunner seedAdmin(AuthService authService) {
        return args -> authService.ensureAdmin();
    }
}
