package com.notesblog.config;

import com.notesblog.service.RateLimiter;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 接口限流的注册。
 *
 * <p>目前只管五个接口：问答要花 LLM token，检索要做向量检索，
 * 公开检索与反向链接都是匿名可调的高成本读写，
 * 阅读量上报是公开写接口、不设上限会被脚本刷榜。
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
@EnableConfigurationProperties(RateLimitProperties.class)
@RequiredArgsConstructor
public class RateLimitConfig implements WebMvcConfigurer {

    /** 阅读量上报：slug 占一段，用单层通配。 */
    static final String VIEW_PATH = "/api/blog/posts/*/view";

    /**
     * 公开检索。匿名可用，且每次都做一次 embedding 调用——
     * 和 /api/search 一样贵，不能不设上限（一个 curl 循环就能把 embedding 额度烧光）。
     */
    static final String PUBLIC_SEARCH_PATH = "/api/blog/search";

    /**
     * 公开反向链接。slug 占一段，用单层通配。
     *
     * <p>这条查询对整库正文做 LIKE '%slug%' 全表扫（索引帮不上忙），
     * 是站上最贵的匿名读接口之一；不设上限的话一串并发就能把数据库 CPU 打满。
     */
    static final String PUBLIC_BACKLINKS_PATH = "/api/blog/posts/*/backlinks";

    private final ObjectProvider<RateLimiter> rateLimiterProvider;
    private final RateLimitProperties properties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        RateLimiter rateLimiter = rateLimiterProvider.getIfAvailable();
        if (rateLimiter == null) {
            log.warn("没有可用的 RateLimiter，接口限流未启用");
            return;
        }

        Map<String, RateLimitInterceptor.Rule> rules = Map.of(
                "/api/ask", new RateLimitInterceptor.Rule(properties.askPerMinute(), Duration.ofMinutes(1)),
                "/api/search", new RateLimitInterceptor.Rule(properties.searchPerMinute(), Duration.ofMinutes(1)),
                PUBLIC_SEARCH_PATH, new RateLimitInterceptor.Rule(properties.publicSearchPerMinute(), Duration.ofMinutes(1)),
                PUBLIC_BACKLINKS_PATH, new RateLimitInterceptor.Rule(properties.backlinksPerMinute(), Duration.ofMinutes(1)),
                VIEW_PATH, new RateLimitInterceptor.Rule(properties.viewPerMinute(), Duration.ofMinutes(1)));

        registry.addInterceptor(new RateLimitInterceptor(rateLimiter, rules))
                .addPathPatterns("/api/ask", "/api/search", PUBLIC_SEARCH_PATH, PUBLIC_BACKLINKS_PATH, VIEW_PATH);
    }
}
