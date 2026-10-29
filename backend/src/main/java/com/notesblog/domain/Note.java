package com.notesblog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * 笔记 / 文章：同一张表，status 区分「私有草稿」与「已发布博客」。
 */
@Entity
@Table(name = "notes")
@Getter
@Setter
public class Note {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, unique = true)
    private String slug;

    private String summary;

    @Column(name = "content_md", nullable = false)
    private String contentMd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NoteStatus status = NoteStatus.DRAFT;

    @Column(name = "source_path")
    private String sourcePath;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /**
     * 已发布之后又被改过，但还没重新发布。
     *
     * <p>公开页读的就是这一行，所以编辑已发布文章等于直接改线上。这个标记不改读取路径
     * （不引入版本表是刻意的取舍），只负责把「线上还是旧的」这件事显示出来。
     * 发布时清零。
     */
    @Column(name = "has_unpublished_edits", nullable = false)
    private boolean hasUnpublishedEdits;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "note_tags",
            joinColumns = @JoinColumn(name = "note_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new LinkedHashSet<>();
}
