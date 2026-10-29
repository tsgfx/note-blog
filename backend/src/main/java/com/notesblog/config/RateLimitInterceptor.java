package com.notesblog.config;

import com.notesblog.common.ClientIp;
import com.notesblog.service.RateLimiter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
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

    /**
     * 已登录用户（管理员）不受分钟限流约束的路径。
     *
     * <p>问答已对游客开放：游客的约束是「每天 N 条」（GuestQuotaService，落库），
     * 分钟限流只用来兜住脚本式的瞬时刷。管理员不计数，否则他自己连着问就会
     * 被「请 60 秒后再试」卡住。
     */
    private static final Set<String> ANONYMOUS_ONLY = Set.of("/api/ask");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Rule rule = rules.get(request.getRequestURI());
        if (rule == null) return true;

        // 只对最初的 REQUEST 派发计数。
        //
        // /api/ask 返回 SseEmitter，属于异步请求：Servlet 容器会在流结束时
        // 再派发一次（type=ASYNC）。拦截器对第二次派发同样会执行 preHandle，
        // 于是同一次提问被计两次——既把额度算少了一半（实测 25 次上限第 14 次就拦），
        // 又会在写 429 body 时因响应已提交抛 IllegalStateException（日志里一串堆栈）。
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }

        if (ANONYMOUS_ONLY.contains(request.getRequestURI()) && isAuthenticated()) {
            return true;
        }

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

    /**
     * 当前请求是否已带有效身份。
     *
     * <p>只认 {@code ROLE_ADMIN}：{@code JwtAuthFilter} 把角色写进 authority，
     * 令牌无效时 SecurityContext 里是 anonymousUser（有 authority 但不是 ROLE_ADMIN），
     * 所以不能只判 {@code isAuthenticated()}——匿名请求那个值也是 true。
     */
    private static boolean isAuthenticated() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /** 每条路径的限额：一个时间窗口内最多多少次 */
    public record Rule(int limit, Duration window) {
    }
}
