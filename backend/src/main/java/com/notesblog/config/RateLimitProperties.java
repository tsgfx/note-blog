package com.notesblog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 高成本接口限流配置。
 *
 * <p>默认值只由 application.yml 维护；这里刻意不再写第二份 Java 默认值。
 * 过去注解默认值与 yml 是两份事实来源，出现过配置文件和实际运行口径不一致。
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        /** 匿名问答每分钟上限；必须不小于游客每日承诺值，否则限流语义会错乱。 */
        int askPerMinute,
        int searchPerMinute,
        /** 公开阅读量上报每分钟上限，挡脚本刷榜。 */
        int viewPerMinute,
        /** 公开语义检索每分钟上限。 */
        int publicSearchPerMinute,
        /** 公开反向链接每分钟上限。 */
        int backlinksPerMinute) {
}
