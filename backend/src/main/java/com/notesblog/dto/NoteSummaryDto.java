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
        long viewCount) {

    public static NoteSummaryDto from(Note n) {
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
                n.getTags().stream().map(Tag::getName).sorted().toList(),
                n.getViewCount());
    }
}
