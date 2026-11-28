package com.notesblog.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 站点公开 URL 与对外元数据的权威来源。
 *
 * <p>{@code site.base-url} 是部署期外部入口地址，可能带末尾斜杠；RSS、sitemap、
 * 评论通知都必须输出同一个规范化绝对地址。API 内部端点不在这里建模，
 * 因为它和公开阅读 URL 是不同语义（例如开发态可能分别是 8080 与 5173）。
 */
@Component
public class SiteUrls {

    private final String baseUrl;
    private final String title;

    public SiteUrls(@Value("${site.base-url}") String baseUrl, @Value("${site.title}") String title) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.baseUrl = normalized;
        this.title = title == null ? "" : title.trim();
    }

    /** 规范化后的站点根地址，不带末尾斜杠；未配置时为空串。 */
    public String base() {
        return baseUrl;
    }

    /** 对外展示的站点标题。RSS、通知等公开出口必须使用同一个权威值。 */
    public String title() {
        return title;
    }

    public String home() {
        return baseUrl + "/";
    }

    public String blogIndex() {
        return baseUrl + "/blog";
    }

    public String post(String slug) {
        return baseUrl + "/blog/" + slug;
    }

    public String sitemap() {
        return baseUrl + "/sitemap.xml";
    }
}
