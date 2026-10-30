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
 * <p>目前只管三个接口：问答要花 LLM token，搜索要做向量检索，
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
@RequiredArgsConstructor
public class RateLimitConfig implements WebMvcConfigurer {

    /** 阅读量上报：slug 占一段，用单层通配。 */
    static final String VIEW_PATH = "/api/blog/posts/*/view";

    private final ObjectProvider<RateLimiter> rateLimiterProvider;

    /**
     * 匿名问答的每分钟上限。
     *
     * <p>这是「防脚本瞬时刷」的兜底，不是主要约束——主要约束是
     * {@code GuestQuotaService} 的每日上限（落库、重启不清零）。
     * 必须大于等于每日上限，否则游客会先看到「请 60 秒后再试」而不是
     * 「今天的次数用完了」，语义莫名其妙。
     *
     * <p>默认值必须与 application.yml 一致：注解里的默认值只在 application.yml
     * 缺这个键时生效，两处不一致会出现「配置写 20、实际按 10 跑」这种
     * 只有读代码才能发现的偏差（本地端到端实测踩过）。
     */
    @Value("${app.rate-limit.ask-per-minute:25}")
    private int askPerMinute;

    @Value("${app.rate-limit.search-per-minute:60}")
    private int searchPerMinute;

    /**
     * 阅读量上报的每分钟上限。
     *
     * <p>正常读者一篇一个会话只报一次，30/分钟对任何真人都够用；
     * 这条规则挡的是脚本——阅读量现在既上榜单又参与排序，
     * 不设上限的话一个 curl 循环就能把任意文章刷到「热门第一」。
     */
    @Value("${app.rate-limit.view-per-minute:30}")
    private int viewPerMinute;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        RateLimiter rateLimiter = rateLimiterProvider.getIfAvailable();
        if (rateLimiter == null) {
            log.warn("没有可用的 RateLimiter，接口限流未启用");
            return;
        }

        Map<String, RateLimitInterceptor.Rule> rules = Map.of(
                "/api/ask", new RateLimitInterceptor.Rule(askPerMinute, Duration.ofMinutes(1)),
                "/api/search", new RateLimitInterceptor.Rule(searchPerMinute, Duration.ofMinutes(1)),
                VIEW_PATH, new RateLimitInterceptor.Rule(viewPerMinute, Duration.ofMinutes(1)));

        registry.addInterceptor(new RateLimitInterceptor(rateLimiter, rules))
                .addPathPatterns("/api/ask", "/api/search", VIEW_PATH);
    }
}
