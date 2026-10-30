package com.notesblog.controller;

import com.notesblog.common.ApiResponse;
import com.notesblog.dto.AskRequest;
import com.notesblog.dto.SearchResultDto;
import com.notesblog.service.SearchAnalyticsService;
import com.notesblog.service.SemanticSearchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 游客可用的公开搜索。
 *
 * <p>单独开一个入口而不是放开 {@code /api/search}：那个是管理员的检索接口，
 * 返回全库结果，将来加字段（比如草稿标记）时不会波及公开面。
 *
 * <p>只返回已发布笔记的分片——草稿也在分片索引里，这一点必须在这里守住。
 */
@RestController
@RequestMapping("/api/blog")
@RequiredArgsConstructor
public class PublicSearchController {

    private final SemanticSearchService searchService;
    private final SearchAnalyticsService searchAnalyticsService;

    @PostMapping("/search")
    public ApiResponse<Map<String, Object>> search(
            @Valid @RequestBody AskRequest request,
            HttpServletRequest servletRequest) {
        List<SearchResultDto> results = searchService.searchPublished(request.question(), 10);
        String clientIp = resolveClientIp(servletRequest);
        searchAnalyticsService.recordSearchAsync(request.question(), results.size(), clientIp);
        return ApiResponse.ok(Map.of("results", results, "total", results.size()));
    }

    private static String resolveClientIp(HttpServletRequest request) {
        if (request == null) return "unknown";
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip == null ? "unknown" : ip;
    }
}

