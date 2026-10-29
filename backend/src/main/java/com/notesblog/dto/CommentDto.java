package com.notesblog.dto;

import com.notesblog.domain.Comment;
import java.time.LocalDateTime;

/**
 * 公开评论视图。<b>刻意不含 {@code clientIp}</b>：
 * 评论是公开的，把 IP 带出去等于泄露访问者信息。管理员接口用 {@link AdminCommentDto}。
 *
 * <p>{@code author} 目前恒为「访客」——游客不填昵称（见 Comment 实体注释）。
 */
public record CommentDto(Long id, String author, String content, LocalDateTime createdAt) {

    public static final String GUEST_AUTHOR = "访客";

    public static CommentDto from(Comment c) {
        return new CommentDto(
                c.getId(),
                c.getNickname() == null || c.getNickname().isBlank() ? GUEST_AUTHOR : c.getNickname(),
                c.getContent(),
                c.getCreatedAt());
    }
}
