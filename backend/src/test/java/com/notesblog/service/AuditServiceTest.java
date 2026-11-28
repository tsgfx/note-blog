package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notesblog.domain.AuditLog;
import com.notesblog.repository.AuditLogRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/** 审计日志的管理端分页边界。 */
@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditService service;

    @BeforeEach
    void setUp() {
        service = new AuditService(auditLogRepository);
    }

    @Test
    @DisplayName("非法分页输入收敛到安全范围，单页最多 200")
    void pagingIsClamped() {
        when(auditLogRepository.findAllByOrderByIdDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.list(-1, 0);
        Pageable first = capturedPageable();
        assertThat(first.getPageNumber()).isZero();
        assertThat(first.getPageSize()).isEqualTo(1);

        service.list(2, 999);
        Pageable second = capturedPageable();
        assertThat(second.getPageNumber()).isEqualTo(2);
        assertThat(second.getPageSize()).isEqualTo(200);
    }

    @Test
    @DisplayName("服务层直接返回对外 DTO，控制器不再接触实体映射")
    void returnsDtoPage() {
        AuditLog log = new AuditLog();
        log.setId(7L);
        log.setUsername("admin");
        log.setAction("NOTE_DELETE");
        log.setTarget("42");
        when(auditLogRepository.findAllByOrderByIdDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(log)));

        var page = service.list(0, 50);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).id()).isEqualTo(7L);
        assertThat(page.getContent().get(0).username()).isEqualTo("admin");
        assertThat(page.getContent().get(0).action()).isEqualTo("NOTE_DELETE");
    }

    private Pageable capturedPageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogRepository, org.mockito.Mockito.atLeastOnce()).findAllByOrderByIdDesc(captor.capture());
        return captor.getValue();
    }
}
