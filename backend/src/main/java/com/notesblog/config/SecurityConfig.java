package com.notesblog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * M0 阶段：API 全部放行，先把骨架跑通。
 * M3 接入 JWT：仅 /api/auth/**、GET 已发布内容公开，写操作需管理员。
 *
 * <p><b>约束</b>：{@code /api/auth/**} 是 permitAll，只应放"匿名入口"（登录）。
 * 任何需要身份的认证类写操作（改密码、登出、绑定）必须挂在 {@code /api/account/**} 下，
 * 否则等于匿名可调用。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/api/auth/**", "/api/blog/**").permitAll()
                        // 错误页由容器转发，不能要求认证，否则真实错误会被掩盖成 401
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/assets/**").permitAll()
                        // 账号相关写操作必须登录（显式声明，避免被 /api/auth/** 的 permitAll 误伤）
                        .requestMatchers("/api/account/**").authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":401,\"message\":\"请先登录\",\"data\":null}");
                }))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
