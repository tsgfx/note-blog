package com.notesblog.dto;

import com.notesblog.domain.Note;
import com.notesblog.domain.Tag;
import java.time.LocalDateTime;
import java.util.List;

/** 列表用的轻量视图，不含正文。sourcePath 为旧 vault 中的原始路径，新建笔记为空。 */
public record NoteSummaryDto(
        Long id,
        String title,
        String slug,
        String summary,
        String status,
        String sourcePath,
        LocalDateTime publishedAt,
        /** 已发布但改过还没重新发布：线上仍是旧内容 */
        boolean hasUnpublishedEdits,
        LocalDateTime updatedAt,
        List<String> tags,
        long viewCount,
        /**
         * 关键词命中正文时的一小段上下文，其余情况为 null。
         *
         * <p>搜索会同时匹配标题、正文和标签，但列表只显示摘要——命中正文时
         * 摘要把关键词藏起来了，读者看到一条对不上的结果。把命中的那一句带出来，
         * 「为什么它在结果里」就不用再点进去找。
         */
        String matchSnippet,
        /** 推荐原因（例如："基于你常看的 #并发 标签"、"热门精选"），为空则为常规列表 */
        String recommendReason) {

    public NoteSummaryDto(
            Long id,
            String title,
            String slug,
            String summary,
            String status,
            String sourcePath,
            LocalDateTime publishedAt,
            boolean hasUnpublishedEdits,
            LocalDateTime updatedAt,
            List<String> tags,
            long viewCount,
            String matchSnippet) {
        this(
                id,
                title,
                slug,
                summary,
                status,
                sourcePath,
                publishedAt,
                hasUnpublishedEdits,
                updatedAt,
                tags,
                viewCount,
                matchSnippet,
                null);
    }

    public NoteSummaryDto withRecommendReason(String reason) {
        return new NoteSummaryDto(
                id,
                title,
                slug,
                summary,
                status,
                sourcePath,
                publishedAt,
                hasUnpublishedEdits,
                updatedAt,
                tags,
                viewCount,
                matchSnippet,
                reason);
    }

    public static NoteSummaryDto from(Note n) {
        return from(n, null);
    }

    /** @param keyword 已 trim 的关键词；为 null 表示不做命中片段 */
    public static NoteSummaryDto from(Note n, String keyword) {
        List<String> tags = n.getTags().stream().map(Tag::getName).sorted().toList();
        return new NoteSummaryDto(
                n.getId(),
                n.getTitle(),
                n.getSlug(),
                n.getSummary(),
                n.getStatus() == null ? null : n.getStatus().name(),
                n.getSourcePath(),
                n.getPublishedAt(),
                n.isHasUnpublishedEdits(),
                n.getUpdatedAt(),
                tags,
                n.getViewCount(),
                snippet(n, keyword, tags));
    }

    /**
     * 只在「关键词不出现在标题/摘要/标签里」时才生成片段。
     *
     * <p>标题或摘要已经显示命中位置时再加一段正文，只是同一句话多说一遍。
     */
    static String snippet(Note n, String keyword, List<String> tags) {
        if (keyword == null || keyword.isBlank()) return null;
        String kw = keyword.trim().toLowerCase();
        if (contains(n.getTitle(), kw) || contains(n.getSummary(), kw)) return null;
        for (String tag : tags) {
            if (contains(tag, kw)) return null;
        }
        String content = n.getContentMd();
        if (content == null || content.isEmpty()) return null;
        int hit = content.toLowerCase().indexOf(kw);
        if (hit < 0) return null;

        int from = Math.max(0, hit - 40);
        int to = Math.min(content.length(), hit + kw.length() + 60);
        String raw = content.substring(from, to)
                .replaceAll("```[\\s\\S]*", " ")
                .replaceAll("[#>*_`~|]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (raw.isEmpty()) return null;
        return (from > 0 ? "…" : "") + raw + (to < content.length() ? "…" : "");
    }

    private static boolean contains(String text, String lowerKeyword) {
        return text != null && text.toLowerCase().contains(lowerKeyword);
    }
}
