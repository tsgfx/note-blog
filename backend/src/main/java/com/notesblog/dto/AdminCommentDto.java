package com.notesblog.dto;

import com.notesblog.domain.Comment;
import java.time.LocalDateTime;

/**
 * 管理端评论视图：比公开视图多 {@code status}、{@code clientIp}、{@code noteTitle}。
 *
 * <p>带 {@code clientIp} 是有意的：这是管理员唯一能追溯「同一来源刷了一屏」的线索。
 * 它只在 {@code /api/admin/**} 下返回，受默认鉴权保护。
 */
public record AdminCommentDto(
        Long id,
        Long noteId,
        String noteTitle,
        String noteSlug,
        String author,
        String content,
        String status,
        String clientIp,
        LocalDateTime createdAt) {

    public static AdminCommentDto from(Comment c, String noteTitle, String noteSlug) {
        return new AdminCommentDto(
                c.getId(),
                c.getNoteId(),
                noteTitle,
                noteSlug,
                c.getNickname() == null || c.getNickname().isBlank()
                        ? CommentDto.GUEST_AUTHOR : c.getNickname(),
                c.getContent(),
                c.getStatus() == null ? null : c.getStatus().name(),
                c.getClientIp(),
                c.getCreatedAt());
    }
}
