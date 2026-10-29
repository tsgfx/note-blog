package com.notesblog.controller;

import com.notesblog.common.ApiResponse;
import com.notesblog.dto.ChangePasswordRequest;
import com.notesblog.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前账号的写操作。
 *
 * <p>刻意不放在 {@code /api/auth/**} 下——那个前缀是 permitAll，只服务于匿名登录入口。
 * 这里的接口由 {@code SecurityConfig} 显式要求认证。
 */
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final AuthService authService;

    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                            Authentication authentication) {
        authService.changePassword(authentication.getName(), request);
        return ApiResponse.ok();
    }
}
