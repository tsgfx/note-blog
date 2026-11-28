package com.notesblog.service;

import com.notesblog.common.NotFoundException;
import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.dto.BlogStatsDto;
import com.notesblog.dto.NoteDetailDto;
import com.notesblog.dto.NoteSummaryDto;
import com.notesblog.dto.NoteTagCountDto;
import com.notesblog.dto.RelatedNoteDto;
import com.notesblog.repository.NoteRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公开博客读模型与阅读量写入的权威入口。
 *
 * <p>这里拥有的不是简单的 repository 转发，而是公开内容边界：只有 PUBLISHED 可见、
 * 公开分页如何排序与限幅、详情如何携带相邻文章、统计只暴露聚合数字、feed/sitemap
 * 各取什么数据。控制器只处理 HTTP 参数、搜索行为记录和 XML/JSON 序列化。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicBlogService {

    public static final int MAX_PAGE_SIZE = 50;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private final NoteRepository noteRepository;
    private final NoteService noteService;
    private final RelatedNoteService relatedNoteService;

    /** 公开文章列表。tag/keyword 任一空则走发布态分页；带筛选时复用统一的笔记查询规格。 */
    public Page<NoteSummaryDto> posts(String tag, String keyword, String sort, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Sort order = "views".equalsIgnoreCase(sort)
                ? Sort.by(Sort.Order.desc("viewCount"), Sort.Order.desc("publishedAt"), Sort.Order.desc("id"))
                : Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("id"));
        Pageable pageable = PageRequest.of(safePage, safeSize, order);

        List<String> tags = (tag != null && !tag.isBlank()) ? List.of(tag.trim()) : null;
        String kw = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;
        if (tags != null || kw != null) {
            return noteService.list(NoteStatus.PUBLISHED, null, kw, tags, pageable);
        }
        return noteRepository.findByStatus(NoteStatus.PUBLISHED, pageable).map(NoteSummaryDto::from);
    }

    /** 公开文章详情，含按发布时间计算的相邻文章。 */
    public NoteDetailDto post(String slug) {
        Note note = requirePublished(slug);
        NoteDetailDto.AdjacentPost prev = null;
        NoteDetailDto.AdjacentPost next = null;
        if (note.getPublishedAt() != null) {
            prev = adjacent(noteRepository.findPrevPublishedPost(
                    note.getPublishedAt(), note.getId(), PageRequest.of(0, 1)));
            next = adjacent(noteRepository.findNextPublishedPost(
                    note.getPublishedAt(), note.getId(), PageRequest.of(0, 1)));
        }
        return NoteDetailDto.from(note, prev, next);
    }

    /** 公开博客标签云。limit 的边界语义与公开分页一致。 */
    public List<NoteTagCountDto> tags(int limit) {
        int wanted = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        List<NoteTagCountDto> all = noteRepository.countTagsByStatus(NoteStatus.PUBLISHED).stream()
                .map(row -> new NoteTagCountDto((String) row[0], ((Number) row[1]).longValue()))
                .toList();
        return all.size() > wanted ? all.subList(0, wanted) : all;
    }

    /** 公开文章的相关推荐；slug 必须先通过公开可见性边界。 */
    public List<RelatedNoteDto> relatedPosts(String slug, Integer limit) {
        Note note = requirePublished(slug);
        return relatedNoteService.relatedPublished(note.getId(), limit);
    }

    /**
     * 记录一次公开文章 PV，返回调用方看到的下一计数值。
     *
     * <p>保留原有语义：读取实体后执行原子 increment，返回值是读取值 + 1；
     * 高并发下它不是全局精确快照，只用于前端即时反馈，权威计数以数据库为准。
     */
    @Transactional
    public long recordView(String slug) {
        Note note = requirePublished(slug);
        noteRepository.incrementViewCount(note.getId());
        return note.getViewCount() + 1L;
    }

    /** 首页公开统计。刻意不暴露草稿量。 */
    public BlogStatsDto stats() {
        return new BlogStatsDto(
                noteRepository.countByStatus(NoteStatus.PUBLISHED),
                noteRepository.countDistinctTagsByStatus(NoteStatus.PUBLISHED),
                noteRepository.findLatestPublishedAt(NoteStatus.PUBLISHED));
    }

    /** RSS/feed 使用的公开文章读模型。数量上限由调用方按配置传入。 */
    public List<FeedItem> feedItems(int maxItems) {
        int limit = Math.max(maxItems, 1);
        return noteRepository
                .findByStatus(
                        NoteStatus.PUBLISHED,
                        PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "publishedAt")))
                .getContent().stream()
                .map(FeedItem::from)
                .toList();
    }

    /** sitemap 使用的公开文章引用。投影只含 slug 与时间，避免把正文读进内存。 */
    public List<PublishedRef> sitemapRefs(int maxItems) {
        int limit = Math.max(maxItems, 1);
        return noteRepository.findPublishedRefs(NoteStatus.PUBLISHED, PageRequest.of(0, limit)).stream()
                .map(row -> new PublishedRef(row.getSlug(), row.getUpdatedAt(), row.getPublishedAt()))
                .toList();
    }

    private Note requirePublished(String slug) {
        return noteRepository.findBySlug(slug)
                .filter(note -> note.getStatus() == NoteStatus.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("公开文章不存在：" + slug));
    }

    private NoteDetailDto.AdjacentPost adjacent(List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) return null;
        Object[] row = rows.get(0);
        return new NoteDetailDto.AdjacentPost((String) row[0], (String) row[1]);
    }

    /** RSS 内部读模型；不向 HTTP 边缘暴露 JPA 实体与懒加载细节。 */
    public record FeedItem(
            String title,
            String slug,
            String summary,
            String contentMd,
            LocalDateTime publishedAt,
            Set<String> tags) {
        static FeedItem from(Note note) {
            Set<String> tagNames = note.getTags() == null
                    ? Set.of()
                    : note.getTags().stream()
                            .map(com.notesblog.domain.Tag::getName)
                            .filter(name -> name != null && !name.isBlank())
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return new FeedItem(
                    note.getTitle(), note.getSlug(), note.getSummary(), note.getContentMd(), note.getPublishedAt(), tagNames);
        }
    }

    /** sitemap 内部读模型；不向控制器暴露 repository 投影类型。 */
    public record PublishedRef(String slug, LocalDateTime updatedAt, LocalDateTime publishedAt) {}
}
