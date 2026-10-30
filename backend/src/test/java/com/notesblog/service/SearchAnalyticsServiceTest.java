package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notesblog.domain.SearchLog;
import com.notesblog.dto.SearchInsightsDto;
import com.notesblog.repository.SearchLogRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class SearchAnalyticsServiceTest {

    @Mock
    private SearchLogRepository searchLogRepository;

    @InjectMocks
    private SearchAnalyticsService searchAnalyticsService;

    @Test
    @DisplayName("搜索记录：截取超长查询词并异步保存搜索日志")
    void recordSearchAsyncSavesCleanQuery() throws Exception {
        String longQuery = "a".repeat(120);
        searchAnalyticsService.recordSearchAsync(longQuery, 0, "127.0.0.1");

        // CompletableFuture 稍微等待执行完成
        Thread.sleep(150);

        ArgumentCaptor<SearchLog> captor = ArgumentCaptor.forClass(SearchLog.class);
        verify(searchLogRepository).save(captor.capture());

        SearchLog saved = captor.getValue();
        assertThat(saved.getQuery()).hasSize(100);
        assertThat(saved.getHitCount()).isEqualTo(0);
        assertThat(saved.getClientIp()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("搜索洞察：正确计算总搜索量、未命中率以及 Top 榜单")
    void getInsightsAggregatesStats() {
        LocalDateTime now = LocalDateTime.now();
        when(searchLogRepository.countByCreatedAtAfter(any())).thenReturn(100L);
        when(searchLogRepository.countByHitCountAndCreatedAtAfter(eq(0), any())).thenReturn(25L);

        when(searchLogRepository.findTopZeroHitQueries(any(), any(Pageable.class)))
                .thenReturn(List.of(
                        new Object[]{"K8s", 15L, now},
                        new Object[]{"Rust", 10L, now.minusHours(1)}
                ));

        when(searchLogRepository.findTopQueries(any(), any(Pageable.class)))
                .thenReturn(List.of(
                        new Object[]{"Docker", 40L, 5.5},
                        new Object[]{"Spring", 35L, 8.2}
                ));

        SearchInsightsDto insights = searchAnalyticsService.getInsights(30, 10);

        assertThat(insights.totalSearches()).isEqualTo(100L);
        assertThat(insights.zeroHitSearches()).isEqualTo(25L);
        assertThat(insights.zeroHitRate()).isEqualTo(25.0);
        assertThat(insights.topZeroHits()).hasSize(2);
        assertThat(insights.topZeroHits().get(0).query()).isEqualTo("K8s");
        assertThat(insights.topQueries()).hasSize(2);
        assertThat(insights.topQueries().get(0).query()).isEqualTo("Docker");
    }
}
