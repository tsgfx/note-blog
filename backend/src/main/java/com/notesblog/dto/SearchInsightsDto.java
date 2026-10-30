package com.notesblog.dto;

import java.time.LocalDateTime;
import java.util.List;

public record SearchInsightsDto(
        int days,
        long totalSearches,
        long zeroHitSearches,
        double zeroHitRate,
        List<ZeroHitItem> topZeroHits,
        List<TopQueryItem> topQueries) {

    public record ZeroHitItem(
            String query,
            long count,
            LocalDateTime lastSearchedAt) {
    }

    public record TopQueryItem(
            String query,
            long count,
            double avgHits) {
    }
}
