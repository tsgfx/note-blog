package com.notesblog.config;

import com.notesblog.common.ApiRoutes;
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
 * 访问边界：匿名只碰得到「公开读」与「登录」，管理面一律要求 ADMIN。
 *
 * <p>匿名（permitAll）：{@code /api/health}、{@code /api/auth/**}（登录）、
 * {@code /api/blog/**}（已发布内容的读）、{@code GET /api/assets/**}（正文配图）、
 * {@code /api/ask}（游客问答，额度在 AskController 里按身份区分）、
 * {@code /api/inbox/quick-capture}（由 X-Inbox-Token 自鉴权）。
 * 其余 {@code /api/notes|admin|search|system|assets} 需 {@code ROLE_ADMIN}，
 * {@code /api/account/**} 需登录（改密码等自助操作）。
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
                        // 开放剪藏接口（由 Controller 内部根据 X-Inbox-Token 进行鉴权）
                        .requestMatchers(ApiRoutes.INBOX_QUICK_CAPTURE).permitAll()
                        // 问答对游客开放：游客额度与检索范围在 AskController 里按身份区分。
                        // 注意放行的是「能调用」，不是「能看全库」——游客只检索已发布分片。
                        .requestMatchers("/api/ask").permitAll()
                        // 错误页由容器转发，不能要求认证，否则真实错误会被掩盖成 401
                        .requestMatchers("/error").permitAll()
                        // 资源只读公开（博客正文里的图片），上传/删除必须管理员
                        .requestMatchers(HttpMethod.GET, "/api/assets/**").permitAll()
                        // 账号相关写操作必须登录（显式声明，避免被 /api/auth/** 的 permitAll 误伤）
                        .requestMatchers("/api/account/**").authenticated()
                        /*
                         * 管理面显式要求 ADMIN，而不是「登录即可」。
                         *
                         * 以前全站只有 anyRequest().authenticated()，而系统里恰好只有一个管理员账号，
                         * 所以看不出问题。但只要将来多出一个只读账号（或任何 role != ADMIN 的用户），
                         * 他立刻就能读写全部笔记、跑备份、改审计——「有账号」被当成了「是管理员」。
                         * 这里把边界写在声明处：登录 ≠ 管理员。
                         */
                        .requestMatchers("/api/notes/**", "/api/admin/**", "/api/search/**",
                                "/api/system/**", "/api/assets/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":401,\"message\":\"请先登录\",\"data\":null}");
                        })
                        // 已登录但角色不够：403，不能落回默认的 HTML 错误页，也不该伪装成 401
                        .accessDeniedHandler((request, response, deniedException) -> {
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":403,\"message\":\"没有权限执行该操作\",\"data\":null}");
                        }))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
