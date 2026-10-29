package com.notesblog.controller;

import com.notesblog.common.ApiResponse;
import com.notesblog.dto.BackupStatusDto;
import com.notesblog.service.BackupStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维状态。需要登录才能看——备份时间与目录属于运行信息，
 * 不适合和公开的 {@code /api/blog/**} 放在一起。
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final BackupStatusService backupStatusService;

    @GetMapping("/backup")
    public ApiResponse<BackupStatusDto> backup() {
        return ApiResponse.ok(backupStatusService.status());
    }
}
