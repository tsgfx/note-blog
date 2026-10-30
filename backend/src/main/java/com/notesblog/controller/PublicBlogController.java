package com.notesblog.controller;

import com.notesblog.common.ApiResponse;
import com.notesblog.common.NotFoundException;
import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.dto.BacklinkDto;
import com.notesblog.dto.BlogStatsDto;
import com.notesblog.dto.NoteDetailDto;
import com.notesblog.dto.NoteSummaryDto;
import com.notesblog.dto.NoteTagCountDto;
import com.notesblog.dto.RelatedNoteDto;
import com.notesblog.repository.NoteRepository;
import com.notesblog.service.BacklinkService;
import com.notesblog.service.NoteService;
import com.notesblog.service.RecommendationService;
import com.notesblog.service.RelatedNoteService;
import com.notesblog.service.SearchAnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    /** 单页上限。超过就截到这个值，避免一次拉走整库。 */
    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final NoteRepository noteRepository;
    private final NoteService noteService;
    private final RelatedNoteService relatedNoteService;
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

    /**
     * 公开文章列表，支持按标签或关键词即时过滤、最新/热门排序，分页返回。
     */
    @GetMapping("/posts")
    public ApiResponse<Page<NoteSummaryDto>> posts(
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "recent") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            HttpServletRequest servletRequest) {
        int safePage = Math.max(page, 0);
        int safeSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        boolean sortByViews = "views".equalsIgnoreCase(sort);
        Sort order = sortByViews
                ? Sort.by(Sort.Order.desc("viewCount"), Sort.Order.desc("publishedAt"), Sort.Order.desc("id"))
                : Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("id"));
        Pageable pageable = PageRequest.of(safePage, safeSize, order);

        List<String> tags = (tag != null && !tag.isBlank()) ? List.of(tag.trim()) : null;
        String kw = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;

        if (tags != null || kw != null) {
            Page<NoteSummaryDto> pageResult = noteService.list(NoteStatus.PUBLISHED, null, kw, tags, pageable);
            if (kw != null) {
                searchAnalyticsService.recordSearchAsync(kw, (int) pageResult.getTotalElements(), resolveClientIp(servletRequest));
            }
            return ApiResponse.ok(pageResult);
        }
        Page<Note> result = noteRepository.findByStatus(NoteStatus.PUBLISHED, pageable);
        return ApiResponse.ok(result.map(NoteSummaryDto::from));
    }

    /**
     * 公开文章的反向链接（提及本文的笔记）。
     */
    @GetMapping("/posts/{slug}/backlinks")
    public ApiResponse<List<BacklinkDto>> backlinks(@PathVariable String slug) {
        return ApiResponse.ok(backlinkService.findBacklinksForPublic(slug));
    }

    @GetMapping("/posts/{slug}")
    public ApiResponse<NoteDetailDto> post(@PathVariable String slug) {
        Note note = noteRepository.findBySlug(slug)
                .filter(n -> n.getStatus() == NoteStatus.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("公开文章不存在：" + slug));

        NoteDetailDto.AdjacentPost prev = null;
        NoteDetailDto.AdjacentPost next = null;
        if (note.getPublishedAt() != null) {
            prev = findAdjacent(noteRepository.findPrevPublishedPost(
                    note.getPublishedAt(), note.getId(), PageRequest.of(0, 1)));
            next = findAdjacent(noteRepository.findNextPublishedPost(
                    note.getPublishedAt(), note.getId(), PageRequest.of(0, 1)));
        }

        return ApiResponse.ok(NoteDetailDto.from(note, prev, next));
    }

    /**
     * 公开博客的热门标签，按篇数降序。
     *
     * <p>列表页支持 {@code ?tag=} 筛选却没有标签清单，读者只能靠猜。
     * 这里只统计已发布文章，草稿里独有的标签不会出现在公开页上。
     */
    @GetMapping("/tags")
    public ApiResponse<List<NoteTagCountDto>> tags(@RequestParam(defaultValue = "12") int limit) {
        int wanted = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        List<NoteTagCountDto> all = noteRepository.countTagsByStatus(NoteStatus.PUBLISHED).stream()
                .map(row -> new NoteTagCountDto((String) row[0], ((Number) row[1]).longValue()))
                .toList();
        return ApiResponse.ok(all.size() > wanted ? all.subList(0, wanted) : all);
    }

    /**
     * 公开文章相关推荐：基于向量语义，仅返回已发布文章。
     */
    @GetMapping("/posts/{slug}/related")
    public ApiResponse<List<RelatedNoteDto>> relatedPosts(
            @PathVariable String slug,
            @RequestParam(required = false) Integer limit) {
        Note note = noteRepository.findBySlug(slug)
                .filter(n -> n.getStatus() == NoteStatus.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("公开文章不存在：" + slug));
        return ApiResponse.ok(relatedNoteService.relatedPublished(note.getId(), limit));
    }

    /**
     * 记录公开文章阅读量（PV）。
     *
     * <p>独立 POST 端点而不是放在 GET /posts/{slug} 里面：
     * 1. 避免爬虫或未执行 JS 的脚本污染阅读量；
     * 2. 避免破坏 GET /posts/{slug} 的 ETag 协商缓存（否则每次自增都会使缓存失效，
     *    详情页每次都要完整传输正文）；
     * 3. 前端可在同会话（sessionStorage）内去重，避免读者 F5 刷新重复计次。
     */
    @PostMapping("/posts/{slug}/view")
    @Transactional
    public ApiResponse<Map<String, Long>> recordView(@PathVariable String slug) {
        Note note = noteRepository.findBySlug(slug)
                .filter(n -> n.getStatus() == NoteStatus.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("公开文章不存在：" + slug));
        noteRepository.incrementViewCount(note.getId());
        return ApiResponse.ok(Map.of("viewCount", note.getViewCount() + 1));
    }

    private NoteDetailDto.AdjacentPost findAdjacent(List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) return null;
        Object[] row = rows.get(0);
        return new NoteDetailDto.AdjacentPost((String) row[0], (String) row[1]);
    }

    /**
     * 首页统计。挂在 {@code /api/blog/**} 下，因此沿用已有的 permitAll 规则，
     * 不需要动 SecurityConfig。
     *
     * <p>只返回聚合数字：已发布篇数、标签数、最近发布时间。不含标题与正文，
     * 也不含笔记总数——总数会暴露还有多少篇草稿。
     */
    @GetMapping("/stats")
    public ApiResponse<BlogStatsDto> stats() {
        return ApiResponse.ok(new BlogStatsDto(
                noteRepository.countByStatus(NoteStatus.PUBLISHED),
                noteRepository.countDistinctTagsByStatus(NoteStatus.PUBLISHED),
                noteRepository.findLatestPublishedAt(NoteStatus.PUBLISHED)));
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
