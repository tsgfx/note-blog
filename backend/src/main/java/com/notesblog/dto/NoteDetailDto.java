package com.notesblog.dto;

import com.notesblog.domain.Note;
import com.notesblog.domain.Tag;
import java.time.LocalDateTime;
import java.util.List;

/** 详情视图，含 Markdown 正文。 */
public record NoteDetailDto(
        Long id,
        String title,
        String slug,
        String summary,
        String contentMd,
        String status,
        String sourcePath,
        LocalDateTime publishedAt,
        /** 已发布但改过还没重新发布：线上仍是旧内容 */
        boolean hasUnpublishedEdits,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<String> tags) {

    public static NoteDetailDto from(Note n) {
        return new NoteDetailDto(
                n.getId(),
                n.getTitle(),
                n.getSlug(),
                n.getSummary(),
                n.getContentMd(),
                n.getStatus() == null ? null : n.getStatus().name(),
                n.getSourcePath(),
                n.getPublishedAt(),
                n.isHasUnpublishedEdits(),
                n.getCreatedAt(),
                n.getUpdatedAt(),
                n.getTags().stream().map(Tag::getName).sorted().toList());
    }
}
