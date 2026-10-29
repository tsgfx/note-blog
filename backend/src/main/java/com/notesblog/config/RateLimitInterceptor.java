package com.notesblog.config;

import com.notesblog.common.ClientIp;
import com.notesblog.service.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 接口限流拦截器。
 *
 * <p>放在拦截器而不是各 Controller 里，是因为拦截器在参数校验之前执行：
 * 请求体格式不对的请求也会被计数，否则「发一堆必然 400 的请求」就能绕开限流。
 *
 * <p>位置还有一个隐含的好处：Spring Security 的过滤器链在 DispatcherServlet 之前，
 * 所以未登录的请求会先被 401 挡下，不消耗配额。
 */
@Slf4j
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;
    private final Map<String, Rule> rules;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Rule rule = rules.get(request.getRequestURI());
        if (rule == null) return true;

        // key 里带上路径：问答和搜索各自独立计数，不互相挤占
        String key = request.getRequestURI() + "|" + ClientIp.of(request);
        RateLimiter.Decision decision = rateLimiter.acquire(key, rule.limit(), rule.window());
        if (decision.allowed()) return true;

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(String.format(
                "{\"code\":429,\"message\":\"请求过于频繁，请 %d 秒后再试\",\"data\":null}",
                decision.retryAfterSeconds()));
        return false;
    }

    /** 每条路径的限额：一个时间窗口内最多多少次 */
    public record Rule(int limit, Duration window) {
    }
}
