package com.notesblog.controller;

import com.notesblog.common.ApiResponse;
import com.notesblog.dto.SearchInsightsDto;
import com.notesblog.dto.SearchRequest;
import com.notesblog.dto.SearchResultDto;
import com.notesblog.service.SearchAnalyticsService;
import com.notesblog.service.SemanticSearchService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 语义搜索。
 *
 * <p>和管理员问答同样的分界：登录的走全库（含草稿），匿名的只看已发布。
 * 草稿也在分片索引里，不过滤就是把未发布正文送给匿名访客。
 *
 * <p>注意这个接口本身仍要求登录（{@code SecurityConfig} 没有放行 {@code /api/search}）：
 * 公开的搜索入口是 {@code /api/blog/search}，见 {@code PublicBlogController}。
 * 这里保留管理员分支只是因为同一个服务两个范围都用得上。
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SemanticSearchService searchService;
    private final SearchAnalyticsService searchAnalyticsService;

    @PostMapping
    public ApiResponse<List<SearchResultDto>> search(@Valid @RequestBody SearchRequest request,
                                                     Authentication authentication) {
        List<SearchResultDto> results = isAdmin(authentication)
                ? searchService.search(request.question(), request.safeLimit())
                : searchService.searchPublished(request.question(), request.safeLimit());
        searchAnalyticsService.recordSearchAsync(request.question(), results.size(), "admin");
        return ApiResponse.ok(results);
    }

    /**
     * 搜索洞察与未命中词统计分析（选题雷达）。
     */
    @GetMapping("/insights")
    public ApiResponse<SearchInsightsDto> insights(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(searchAnalyticsService.getInsights(days, limit));
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
