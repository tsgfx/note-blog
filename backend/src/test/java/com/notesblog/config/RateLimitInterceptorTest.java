package com.notesblog.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notesblog.service.RateLimiter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 限流拦截器的两个边界，都是端到端实测踩出来的：
 *
 * <ol>
 *   <li><b>异步派发不能重复计数</b>：{@code /api/ask} 返回 SseEmitter，容器在流结束时
 *       会再走一次 preHandle（type=ASYNC）。计两次会把额度砍半（实测 25 次上限第 14 次
 *       就拦），并且第二次写 429 body 时响应已提交，抛 IllegalStateException。</li>
 *   <li><b>管理员不受分钟限流</b>：他的约束是「不计数」，不该被「请 60 秒后再试」卡住。</li>
 * </ol>
 */
class RateLimitInterceptorTest {

    private RateLimiter rateLimiter;
    private RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        interceptor = new RateLimitInterceptor(rateLimiter,
                Map.of("/api/ask", new RateLimitInterceptor.Rule(25, Duration.ofMinutes(1))));
        SecurityContextHolder.clearContext();
    }

    private HttpServletRequest request(DispatcherType type) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/api/ask");
        when(req.getDispatcherType()).thenReturn(type);
        when(req.getHeader("X-Real-IP")).thenReturn("203.0.113.7");
        return req;
    }

    private HttpServletResponse response() throws Exception {
        HttpServletResponse res = mock(HttpServletResponse.class);
        when(res.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return res;
    }

    @Test
    @DisplayName("首次请求计数")
    void requestDispatchCounts() throws Exception {
        when(rateLimiter.acquire(anyString(), anyInt(), any()))
                .thenReturn(new RateLimiter.Decision(true, 0));

        assertThat(interceptor.preHandle(request(DispatcherType.REQUEST), response(), new Object()))
                .isTrue();
        verify(rateLimiter).acquire(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("异步派发不重复计数：否则额度被砍半")
    void asyncDispatchDoesNotCount() throws Exception {
        assertThat(interceptor.preHandle(request(DispatcherType.ASYNC), response(), new Object()))
                .isTrue();

        // 关键断言：一次都没调计数
        verify(rateLimiter, never()).acquire(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("管理员直接放行，不消耗额度")
    void adminBypassesMinuteLimit() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null,
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        assertThat(interceptor.preHandle(request(DispatcherType.REQUEST), response(), new Object()))
                .isTrue();
        verify(rateLimiter, never()).acquire(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("匿名用户超额：429 并写回 Retry-After")
    void anonymousOverLimitIsRejected() throws Exception {
        when(rateLimiter.acquire(anyString(), anyInt(), any()))
                .thenReturn(new RateLimiter.Decision(false, 42));
        HttpServletResponse res = response();

        assertThat(interceptor.preHandle(request(DispatcherType.REQUEST), res, new Object()))
                .isFalse();
        verify(res).setStatus(429);
        verify(res).setHeader("Retry-After", "42");
    }
}
