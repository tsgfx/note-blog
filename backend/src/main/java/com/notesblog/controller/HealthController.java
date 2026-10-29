package com.notesblog.controller;

import com.notesblog.common.ApiResponse;
import com.notesblog.dto.HealthReport;
import com.notesblog.service.HealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final HealthService healthService;

    /**
     * 健康检查。公开接口，**只报依赖状态，不报任何业务数据**。
     *
     * <p>始终返回 HTTP 200，靠 body 里的 {@code status} 区分 {@code up} / {@code degraded}，
     * 理由见 {@link HealthService}。
     */
    @GetMapping("/health")
    public ApiResponse<HealthReport> health() {
        return ApiResponse.ok(healthService.check());
    }
}
