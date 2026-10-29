package com.notesblog.config;

import com.notesblog.service.RateLimiter;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 接口限流的注册。
 *
 * <p>目前只管两个"贵"的接口：问答要花 LLM token，搜索要做向量检索。
 * 其它接口是本地 CRUD，限流只会给自己添麻烦。
 *
 * <p><b>为什么用 {@link ObjectProvider} 而不是直接注入：</b>
 * {@code @WebMvcTest} 切片会把所有 {@code WebMvcConfigurer} 拉进上下文，
 * 但不会加载 {@code RateLimiter}（它是普通 {@code @Service}），
 * 直接注入会让 7 个控制器切片测试在启动阶段就失败。
 * 拿不到就跳过注册，同时 **打一条 warn**——生产环境里如果真丢了 Bean，
 * 日志会写明「限流未启用」，不会安静地失效。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RateLimitConfig implements WebMvcConfigurer {

    private final ObjectProvider<RateLimiter> rateLimiterProvider;

    @Value("${app.rate-limit.ask-per-minute:10}")
    private int askPerMinute;

    @Value("${app.rate-limit.search-per-minute:60}")
    private int searchPerMinute;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        RateLimiter rateLimiter = rateLimiterProvider.getIfAvailable();
        if (rateLimiter == null) {
            log.warn("没有可用的 RateLimiter，接口限流未启用");
            return;
        }

        Map<String, RateLimitInterceptor.Rule> rules = Map.of(
                "/api/ask", new RateLimitInterceptor.Rule(askPerMinute, Duration.ofMinutes(1)),
                "/api/search", new RateLimitInterceptor.Rule(searchPerMinute, Duration.ofMinutes(1)));

        registry.addInterceptor(new RateLimitInterceptor(rateLimiter, rules))
                .addPathPatterns("/api/ask", "/api/search");
    }
}
