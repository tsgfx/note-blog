package com.notesblog.controller;

import com.notesblog.common.ApiResponse;
import com.notesblog.common.NotFoundException;
import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.dto.BlogStatsDto;
import com.notesblog.dto.NoteDetailDto;
import com.notesblog.dto.NoteSummaryDto;
import com.notesblog.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/blog")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicBlogController {

    /** 单页上限。超过就截到这个值，避免一次拉走整库。 */
    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final NoteRepository noteRepository;

    /**
     * 公开文章列表，分页返回。
     *
     * <p>以前这里写死 {@code PageRequest.of(0, 100)} 且不接收分页参数，
     * 发布超过 100 篇之后第 101 篇起在博客里看不到，也没有任何提示。
     */
    @GetMapping("/posts")
    public ApiResponse<Page<NoteSummaryDto>> posts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Page<Note> result = noteRepository.findByStatus(NoteStatus.PUBLISHED,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "publishedAt")));
        return ApiResponse.ok(result.map(NoteSummaryDto::from));
    }

    @GetMapping("/posts/{slug}")
    public ApiResponse<NoteDetailDto> post(@PathVariable String slug) {
        return ApiResponse.ok(noteRepository.findBySlug(slug)
                .filter(note -> note.getStatus() == NoteStatus.PUBLISHED)
                .map(NoteDetailDto::from)
                .orElseThrow(() -> new NotFoundException("公开文章不存在：" + slug)));
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
}
