package com.notesblog.dto;

import com.notesblog.domain.AuditLog;
import java.time.LocalDateTime;

/** 操作日志的对外视图，不暴露实体。 */
public record AuditLogDto(
        Long id,
        String username,
        String action,
        String target,
        String detail,
        LocalDateTime createdAt) {

    public static AuditLogDto from(AuditLog log) {
        return new AuditLogDto(log.getId(), log.getUsername(), log.getAction(),
                log.getTarget(), log.getDetail(), log.getCreatedAt());
    }
}
