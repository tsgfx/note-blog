package com.notesblog.controller;

import com.notesblog.common.ApiResponse;
import com.notesblog.dto.AuditLogDto;
import com.notesblog.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 操作日志查询（仅管理员，受 /api 默认鉴权保护）。 */
@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @GetMapping
    public ApiResponse<Page<AuditLogDto>> list(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "50") int size) {
        Page<AuditLogDto> result = auditService.list(PageRequest.of(page, Math.min(size, 200)))
                .map(AuditLogDto::from);
        return ApiResponse.ok(result);
    }
}
