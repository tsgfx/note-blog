package com.notesblog.controller;

import com.notesblog.common.ApiResponse;
import com.notesblog.common.ClientIp;
import com.notesblog.dto.BacklinkDto;
import com.notesblog.dto.BlogStatsDto;
import com.notesblog.dto.NoteDetailDto;
import com.notesblog.dto.NoteSummaryDto;
import com.notesblog.dto.NoteTagCountDto;
import com.notesblog.dto.RelatedNoteDto;
import com.notesblog.service.BacklinkService;
import com.notesblog.service.PublicBlogService;
import com.notesblog.service.RecommendationService;
import com.notesblog.service.SearchAnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/blog")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicBlogController {

    private final PublicBlogService publicBlogService;
    private final RecommendationService recommendationService;
    private final BacklinkService backlinkService;
    private final SearchAnalyticsService searchAnalyticsService;

    /**
     * 博客推荐流：支持冷启动精选与基于读者足迹的个性化兴趣推荐。
     *
     * @param history 逗号分隔或多值的历史浏览文章 slug 清单（来自前端 localStorage）
     * @param limit 返回上限，默认 10 篇
     */
    @GetMapping("/recommendations")
    public ApiResponse<List<NoteSummaryDto>> recommendations(
            @RequestParam(required = false) List<String> history,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(recommendationService.recommend(history, limit));
    }

    /** 公开文章列表，支持按标签或关键词即时过滤、最新/热门排序，分页返回。 */
    @GetMapping("/posts")
    public ApiResponse<Page<NoteSummaryDto>> posts(
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "recent") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PublicBlogService.DEFAULT_PAGE_SIZE) int size,
            HttpServletRequest servletRequest) {
        Page<NoteSummaryDto> result = publicBlogService.posts(tag, keyword, sort, page, size);
        if (keyword != null && !keyword.isBlank()) {
            searchAnalyticsService.recordSearchAsync(keyword.trim(), (int) result.getTotalElements(), ClientIp.of(servletRequest));
        }
        return ApiResponse.ok(result);
    }

    /** 公开文章的反向链接（提及本文的笔记）。 */
    @GetMapping("/posts/{slug}/backlinks")
    public ApiResponse<List<BacklinkDto>> backlinks(@PathVariable String slug) {
        return ApiResponse.ok(backlinkService.findBacklinksForPublic(slug));
    }

    @GetMapping("/posts/{slug}")
    public ApiResponse<NoteDetailDto> post(@PathVariable String slug) {
        return ApiResponse.ok(publicBlogService.post(slug));
    }

    /** 公开博客的热门标签，只统计已发布文章，草稿里独有的标签不会出现在公开页上。 */
    @GetMapping("/tags")
    public ApiResponse<List<NoteTagCountDto>> tags(@RequestParam(defaultValue = "12") int limit) {
        return ApiResponse.ok(publicBlogService.tags(limit));
    }

    /** 公开文章相关推荐：基于向量语义，仅返回已发布文章。 */
    @GetMapping("/posts/{slug}/related")
    public ApiResponse<List<RelatedNoteDto>> relatedPosts(
            @PathVariable String slug,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(publicBlogService.relatedPosts(slug, limit));
    }

    /**
     * 记录公开文章阅读量（PV）。
     *
     * <p>独立 POST 端点而不是放在 GET /posts/{slug} 里面：避免爬虫污染阅读量，
     * 也避免破坏详情 GET 的 ETag 协商缓存。
     */
    @PostMapping("/posts/{slug}/view")
    @Transactional
    public ApiResponse<Map<String, Long>> recordView(@PathVariable String slug) {
        return ApiResponse.ok(Map.of("viewCount", publicBlogService.recordView(slug)));
    }

    /**
     * 首页统计。只返回聚合数字：已发布篇数、标签数、最近发布时间；
     * 不含笔记总数，避免暴露草稿量。
     */
    @GetMapping("/stats")
    public ApiResponse<BlogStatsDto> stats() {
        return ApiResponse.ok(publicBlogService.stats());
    }
}
