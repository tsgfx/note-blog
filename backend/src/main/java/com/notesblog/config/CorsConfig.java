package com.notesblog.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 允许的来源列表，由 {@code app.cors.allowed-origins} 提供，
 * 可用环境变量 {@code CORS_ALLOWED_ORIGINS} 覆盖（逗号分隔）。
 *
 * <p>注意浏览器的真实行为：即使前后端同源，POST 请求也会带 {@code Origin} 头。
 * Spring 的 CORS 过滤器只要看到 Origin 就比对白名单，不在名单里直接返回 403
 * （"Invalid CORS request"）——页面能打开，但所有接口都失败。
 *
 * <p>所以这里用 {@link CorsConfiguration#setAllowedOriginPatterns} 而不是
 * {@code setAllowedOrigins}：前者支持 {@code http://localhost:[*]} 这种端口通配，
 * 本机换端口（5173 / 4173 / 8088）不用再改代码。
 * 代价是配置里出现 {@code *} 时匹配范围比精确串宽，因此外部来源仍要显式列举。
 */
@Configuration
public class CorsConfig {

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${app.cors.allowed-origins}") String origins) {
        this.allowedOrigins = Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
