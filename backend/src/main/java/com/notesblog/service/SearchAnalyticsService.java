package com.notesblog.service;

import com.notesblog.domain.SearchLog;
import com.notesblog.dto.SearchInsightsDto;
import com.notesblog.repository.SearchLogRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchAnalyticsService {

    private final SearchLogRepository searchLogRepository;

    /**
     * 异步记录一次搜索行为，绝不影响主接口响应延迟。
     *
     * @param rawQuery 搜索原始输入
     * @param hitCount 命中结果数
     * @param clientIp 客户端 IP
     */
    public void recordSearchAsync(String rawQuery, int hitCount, String clientIp) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return;
        }
        String cleanQuery = rawQuery.trim();
        if (cleanQuery.length() > 100) {
            cleanQuery = cleanQuery.substring(0, 100);
        }

        final String query = cleanQuery;
        CompletableFuture.runAsync(() -> {
            try {
                SearchLog logItem = new SearchLog();
                logItem.setQuery(query);
                logItem.setHitCount(Math.max(0, hitCount));
                logItem.setClientIp(clientIp != null && clientIp.length() > 60 ? clientIp.substring(0, 60) : clientIp);
                logItem.setCreatedAt(LocalDateTime.now());
                searchLogRepository.save(logItem);
            } catch (Exception e) {
                log.warn("failed_to_save_search_log query={} error={}", query, e.getMessage());
            }
        });
    }

    /**
     * 获取近 N 天的搜索洞察：包含总搜索量、未命中率、Top 零命中词榜与 Top 热门词榜。
     */
    @Transactional(readOnly = true)
    public SearchInsightsDto getInsights(int days, int limit) {
        int safeDays = Math.max(1, Math.min(days, 365));
        int safeLimit = Math.max(1, Math.min(limit, 50));
        LocalDateTime since = LocalDateTime.now().minusDays(safeDays);

        long total = searchLogRepository.countByCreatedAtAfter(since);
        long zeroHits = searchLogRepository.countByHitCountAndCreatedAtAfter(0, since);
        double zeroHitRate = total > 0 ? Math.round((double) zeroHits / total * 1000.0) / 10.0 : 0.0;

        List<SearchInsightsDto.ZeroHitItem> topZeroHits = searchLogRepository
                .findTopZeroHitQueries(since, PageRequest.of(0, safeLimit))
                .stream()
                .map(row -> new SearchInsightsDto.ZeroHitItem(
                        (String) row[0],
                        ((Number) row[1]).longValue(),
                        (LocalDateTime) row[2]
                ))
                .toList();

        List<SearchInsightsDto.TopQueryItem> topQueries = searchLogRepository
                .findTopQueries(since, PageRequest.of(0, safeLimit))
                .stream()
                .map(row -> new SearchInsightsDto.TopQueryItem(
                        (String) row[0],
                        ((Number) row[1]).longValue(),
                        row[2] != null ? Math.round(((Number) row[2]).doubleValue() * 10.0) / 10.0 : 0.0
                ))
                .toList();

        return new SearchInsightsDto(safeDays, total, zeroHits, zeroHitRate, topZeroHits, topQueries);
    }
}
