package com.notesblog.controller;

import com.notesblog.common.ApiResponse;
import com.notesblog.common.ClientIp;
import com.notesblog.dto.LoginRequest;
import com.notesblog.dto.LoginResponse;
import com.notesblog.service.AuditService;
import com.notesblog.service.AuthService;
import com.notesblog.service.LoginAttemptService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final LoginAttemptService loginAttemptService;
    private final AuditService auditService;

    /**
     * 登录。
     *
     * <p>失败计数与锁定放在这里而不是 {@link AuthService}：判断要不要锁需要客户端 IP，
     * 那是 Web 层的事，服务层不该知道 HttpServletRequest。
     *
     * <p>锁定期间返回 <b>429</b> 而不是 401：前端对 401 的处理是清 token 并跳登录页，
     * 而「试太多次」不是身份失效，不该触发那套逻辑。
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String ip = ClientIp.of(http);

        long waitSeconds = loginAttemptService.lockedSeconds(request.username(), ip);
        if (waitSeconds > 0) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(waitSeconds))
                    .body(ApiResponse.error(429, retryMessage(waitSeconds)));
        }

        try {
            LoginResponse result = authService.login(request);
            loginAttemptService.clear(request.username(), ip);
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (BadCredentialsException e) {
            // 只在「刚刚触发锁定」那一次写审计，否则每个被拒请求都写一行会把日志刷爆
            if (loginAttemptService.recordFailure(request.username(), ip)) {
                auditService.recordAs(request.username(), "LOGIN_BLOCKED", null, "连续登录失败，已锁定 15 分钟");
            }
            throw e;
        }
    }

    private String retryMessage(long seconds) {
        long minutes = Math.max(1, (seconds + 59) / 60);
        return "尝试次数过多，请 " + minutes + " 分钟后再试";
    }
}
