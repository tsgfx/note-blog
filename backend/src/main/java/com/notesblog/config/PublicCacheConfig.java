package com.notesblog.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.DigestUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * 公开读接口的 HTTP 协商缓存：{@code Cache-Control: no-cache} + {@code ETag}。
 *
 * <p>只覆盖 {@code /api/blog/**}（文章列表/详情/标签/统计/评论列表/搜索/sitemap/robots/rss）
 * ——匿名可读、且只在「发布或改文章」时变化。后台接口
 * （{@code /api/notes/**}、{@code /api/admin/**}）一律不参与，那里是草稿与审计数据，
 * 任何一层缓存都是泄露面。
 *
 * <p>为什么是 {@code no-cache}（每次回源校验）而不是 {@code max-age=60}：
 * 发布之后作者多半立刻刷新看效果，带 {@code max-age} 会让浏览器拿着旧列表不放，
 * 表现成「刚发的文章没出现」。协商缓存每次都问一句，内容没变就回 304（空响应体）：
 * 既不会看到旧内容，也省掉重复传输。改文章后 ETag 自然变化，不需要手动清缓存。
 *
 * <p>为什么不用 {@code ShallowEtagHeaderFilter}：它在 Spring 6 里对已提交的响应
 * 直接跳过，实测在这条链上不产出 ETag（同一个注册方式，Cache-Control 能出来、
 * ETag 出不来）。自己算 MD5 只有十几行，行为完全可预期。
 *
 * <p>不做服务端缓存：全库 680 篇、正文不到 10MB，InnoDB 的 buffer pool 已经全兜住，
 * 再叠一层内存缓存只是多一份要失效的状态，收益抵不过维护成本。
 */
@Configuration
public class PublicCacheConfig {

    /** 公开读接口的统一前缀。DTO 与控制器都挂在 {@code /api/blog} 下。 */
    private static final String PUBLIC_URL_PATTERN = "/api/blog/*";

    @Bean
    public FilterRegistrationBean<PublicCacheFilter> publicCacheFilter() {
        FilterRegistrationBean<PublicCacheFilter> registration =
                new FilterRegistrationBean<>(new PublicCacheFilter());
        registration.addUrlPatterns(PUBLIC_URL_PATTERN);
        registration.setName("publicCacheFilter");
        return registration;
    }

    static final class PublicCacheFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            boolean isGet = "GET".equals(request.getMethod());
            boolean isHead = "HEAD".equals(request.getMethod());

            // 写请求（提交评论、游客问答）的响应一律不给缓存语义
            if (!isGet && !isHead) {
                chain.doFilter(request, response);
                return;
            }

            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
            if (isHead) {
                // HEAD 没有正文可算摘要，缓存头给了就行
                chain.doFilter(request, response);
                return;
            }

            // 必须在链条跑完、正文写完之后才能算摘要，所以先缓存整个响应体
            ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
            try {
                chain.doFilter(request, wrapper);
                byte[] body = wrapper.getContentAsByteArray();
                // 只给 200 加 ETag：4xx/5xx 不该被当成「可复用的表示」缓存下来
                if (wrapper.getStatus() == HttpServletResponse.SC_OK && body.length > 0) {
                    // 弱校验值：Caddy 会对 JSON 做 gzip/zstd，同一份内容传到客户端的字节不同，
                    // 强 ETag（要求逐字节一致）在这里不成立
                    String opaque = "\"" + DigestUtils.md5DigestAsHex(body) + "\"";
                    if (opaque.equals(stripWeak(request.getHeader(HttpHeaders.IF_NONE_MATCH)))) {
                        wrapper.resetBuffer(); // 304 不能带正文
                        response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                        response.setHeader(HttpHeaders.ETAG, "W/" + opaque);
                        return;
                    }
                    wrapper.setHeader(HttpHeaders.ETAG, "W/" + opaque);
                }
            } finally {
                // 无论走到哪条分支，缓存下来的正文都要真正写回客户端
                wrapper.copyBodyToResponse();
            }
        }

        /** 客户端可能回传带或不带 {@code W/} 前缀的形式，比较前统一去掉。 */
        private static String stripWeak(String ifNoneMatch) {
            if (ifNoneMatch == null) return null;
            String value = ifNoneMatch.trim();
            return value.startsWith("W/") ? value.substring(2) : value;
        }
    }
}
