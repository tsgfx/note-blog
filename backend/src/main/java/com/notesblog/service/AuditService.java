package com.notesblog.service;

import com.notesblog.domain.AuditLog;
import com.notesblog.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /** 记录一次管理操作；未登录场景（如登录失败）记为 anonymous。 */
    public void record(String action, String target, String detail) {
        AuditLog log = new AuditLog();
        log.setUsername(currentUser());
        log.setAction(action);
        log.setTarget(truncate(target, 255));
        log.setDetail(truncate(detail, 500));
        auditLogRepository.save(log);
    }

    public void recordAs(String username, String action, String target, String detail) {
        AuditLog log = new AuditLog();
        log.setUsername(truncate(username, 64));
        log.setAction(action);
        log.setTarget(truncate(target, 255));
        log.setDetail(truncate(detail, 500));
        auditLogRepository.save(log);
    }

    public Page<AuditLog> list(Pageable pageable) {
        return auditLogRepository.findAllByOrderByIdDesc(pageable);
    }

    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "anonymous" : truncate(auth.getName(), 64);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
