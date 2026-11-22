package com.notesblog.controller;

import com.notesblog.common.ApiResponse;
import com.notesblog.common.ClientIp;
import com.notesblog.dto.QuickCaptureRequest;
import com.notesblog.dto.QuickCaptureResponseDto;
import com.notesblog.service.InboxService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class InboxController {

    private final InboxService inboxService;

    /**
     * 开放快捷捕捉 / 剪藏入库接口。
     * 支持 iOS 快捷指令、Raycast、Alfred 脚本或自动化工具通过 Token 快速写入草稿库。
     */
    @PostMapping("/api/inbox/quick-capture")
    public ApiResponse<QuickCaptureResponseDto> quickCapture(
            @Valid @RequestBody QuickCaptureRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Inbox-Token", required = false) String customTokenHeader,
            Authentication authentication,
            HttpServletRequest servletRequest) {

        if (!inboxService.isAuthorized(authHeader, customTokenHeader, authentication)) {
            return ApiResponse.error(401, inboxService.isTokenConfigured()
                    ? "剪藏未授权：请在请求头提供有效的 X-Inbox-Token 或 Bearer Token"
                    : "剪藏未启用：服务端未配置 INBOX_TOKEN，只能登录后从设置页写入");
        }

        String clientIp = ClientIp.of(servletRequest);
        return ApiResponse.ok(inboxService.capture(request, clientIp));
    }

    /**
     * 管理员获取剪藏 Token、调用入口与快捷指令配置样例。
     */
    @GetMapping("/api/admin/inbox/config")
    public ApiResponse<Map<String, Object>> getInboxConfig() {
        return ApiResponse.ok(inboxService.getConfig());
    }

}
