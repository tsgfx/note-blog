package com.notesblog.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 公开读接口的协商缓存。
 *
 * <p>守的是一条容易「看着对其实没生效」的线：头加上了但 304 分支写错，
 * 浏览器会拿到一个带正文的 304，或者永远命中不了缓存。这里直接断言字节。
 */
class PublicCacheFilterTest {

    private static final String BODY = "{\"code\":0,\"data\":[\"a\"]}";

    private static final FilterChain ECHO_JSON =
            (req, res) -> {
                HttpServletResponse response = (HttpServletResponse) res;
                response.setContentType("application/json");
                response.getWriter().write(BODY);
            };

    private final PublicCacheConfig.PublicCacheFilter filter = new PublicCacheConfig.PublicCacheFilter();

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, ECHO_JSON);
        return response;
    }

    @Test
    @DisplayName("GET 带 Cache-Control 与弱 ETag，正文照常返回")
    void getAddsEtagAndBody() throws Exception {
        MockHttpServletResponse response = run(new MockHttpServletRequest("GET", "/api/blog/posts"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(response.getHeader("ETag")).startsWith("W/\"").endsWith("\"");
        assertThat(response.getContentAsString()).isEqualTo(BODY);
    }

    @Test
    @DisplayName("If-None-Match 命中时回 304 且不带正文")
    void matchingEtagGets304() throws Exception {
        String etag = run(new MockHttpServletRequest("GET", "/api/blog/posts")).getHeader("ETag");

        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/blog/posts");
        second.addHeader("If-None-Match", etag);
        MockHttpServletResponse response = run(second);

        assertThat(response.getStatus()).isEqualTo(304);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader("ETag")).isEqualTo(etag);
        // 304 也要保住缓存语义，否则浏览器下次就不带 If-None-Match 了
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache");
    }

    @Test
    @DisplayName("客户端回传不带 W/ 前缀时同样命中 304")
    void strongFormOfWeakEtagStillMatches() throws Exception {
        String etag = run(new MockHttpServletRequest("GET", "/api/blog/posts")).getHeader("ETag");

        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/blog/posts");
        second.addHeader("If-None-Match", etag.substring(2)); // 去掉 W/
        assertThat(run(second).getStatus()).isEqualTo(304);
    }

    @Test
    @DisplayName("内容变化后 ETag 变化，不会误判成 304")
    void changedBodyChangesEtag() throws Exception {
        String etag = run(new MockHttpServletRequest("GET", "/api/blog/posts")).getHeader("ETag");

        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/blog/posts");
        second.addHeader("If-None-Match", etag);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(second, response, (req, res) -> {
            HttpServletResponse r = (HttpServletResponse) res;
            r.getWriter().write("{\"code\":0,\"data\":[\"a\",\"b\"]}");
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("ETag")).isNotEqualTo(etag);
    }

    @Test
    @DisplayName("写请求不加任何缓存头（评论、问答的响应不该被存下来）")
    void postIsUntouched() throws Exception {
        MockHttpServletResponse response = run(new MockHttpServletRequest("POST", "/api/blog/comments"));

        assertThat(response.getHeader("Cache-Control")).isNull();
        assertThat(response.getHeader("ETag")).isNull();
    }

    @Test
    @DisplayName("非 200 不给 ETag，避免把错误响应当成可复用表示")
    void errorResponseHasNoEtag() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/blog/posts/404"), response, (req, res) -> {
            HttpServletResponse r = (HttpServletResponse) res;
            r.setStatus(404);
            r.getWriter().write("{\"code\":404}");
        });

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getHeader("ETag")).isNull();
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":404}");
    }
}
