package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.notesblog.dto.HealthReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 健康检查的判定分级。
 *
 * <p>重点是「降级」而不是「挂掉」：embedding 服务不可用时，浏览和编辑仍然应该算健康，
 * 只有问答与语义检索受影响。把这种情况判成 down，会让编排层去重启一个没问题的后端。
 */
@ExtendWith(MockitoExtension.class)
class HealthServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private EmbeddingProbe embeddingProbe;

    @InjectMocks
    private HealthService healthService;

    @Test
    @DisplayName("依赖全通 → up")
    void allHealthy() {
        when(jdbcTemplate.queryForObject(eq("select 1"), eq(Integer.class))).thenReturn(1);
        when(embeddingProbe.ping()).thenReturn(true);

        HealthReport report = healthService.check();

        assertThat(report.status()).isEqualTo(HealthReport.UP);
        assertThat(report.components()).containsEntry("db", true).containsEntry("embedding", true);
    }

    @Test
    @DisplayName("embedding 不可用 → degraded，但数据库仍标为可用")
    void embeddingDownIsDegraded() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(embeddingProbe.ping()).thenReturn(false);

        HealthReport report = healthService.check();

        assertThat(report.status()).isEqualTo(HealthReport.DEGRADED);
        assertThat(report.components()).containsEntry("db", true).containsEntry("embedding", false);
    }

    @Test
    @DisplayName("数据库查询抛异常 → degraded，且不把异常抛给调用方")
    void databaseErrorIsDegraded() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException("连不上"));
        when(embeddingProbe.ping()).thenReturn(true);

        HealthReport report = healthService.check();

        assertThat(report.status()).isEqualTo(HealthReport.DEGRADED);
        assertThat(report.components()).containsEntry("db", false);
    }
}
