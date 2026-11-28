package com.notesblog.controller;

import com.notesblog.common.ApiResponse;
import com.notesblog.dto.ChangePasswordRequest;
import com.notesblog.dto.MeResponse;
import com.notesblog.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前账号的读写操作。
 *
 * <p>刻意不放在 {@code /api/auth/**} 下——那个前缀是 permitAll，只服务于匿名登录入口。
 * 这里的接口由 {@code SecurityConfig} 显式要求认证（不是 {@code hasRole("ADMIN")}）：
 * 前端要拿它判断「我是不是管理员」，非管理员调用也得能拿到 200 和自己的角色。
 */
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final AuthService authService;

    /**
     * 当前登录者的身份摘要。
     *
     * <p>角色刻意从 {@link Authentication} 的 {@code ROLE_*} 授权项取，而不是再查一遍库：
     * 这样和 {@code SecurityConfig} 里 {@code hasRole("ADMIN")} 的判定完全同源，
     * 不会出现「前端说是管理员、后端其实不放行」这类错位。
     */
    @GetMapping("/me")
    public ApiResponse<MeResponse> me(Authentication authentication) {
        return ApiResponse.ok(authService.me(authentication.getName(), authentication.getAuthorities()));
    }

    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                            Authentication authentication) {
        authService.changePassword(authentication.getName(), request);
        return ApiResponse.ok();
    }
}
