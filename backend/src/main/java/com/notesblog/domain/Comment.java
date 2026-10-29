package com.notesblog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 一篇已发布文章下的游客评论。
 *
 * <p>游客没有账号，所以这里不挂 {@code User}：{@link #nickname} 当前恒为 NULL，
 * 对外统一显示「访客」。留这一列是为了以后想开昵称时不必再改表。
 *
 * <p>{@link #clientIp} 只用于限流与追溯，**不出现在任何公开响应里**——
 * 评论是公开的，把 IP 带出去等于泄露访问者信息。管理员接口另有一个 DTO 才带它。
 */
@Entity
@Table(name = "comments")
@Getter
@Setter
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_id", nullable = false)
    private Long noteId;

    private String nickname;

    @Column(nullable = false, length = 2000)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommentStatus status = CommentStatus.VISIBLE;

    @Column(name = "client_ip")
    private String clientIp;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
