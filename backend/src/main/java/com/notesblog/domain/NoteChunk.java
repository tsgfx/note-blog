package com.notesblog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 语义检索分片。{@code embedding VECTOR(1024)} 列未在实体中映射，
 * 向量读写走原生 SQL（STRING_TO_VECTOR / VECTOR_DISTANCE），见 M1。
 */
@Entity
@Table(name = "note_chunks")
@Getter
@Setter
public class NoteChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_id", nullable = false)
    private Long noteId;

    private String heading;

    @Column(nullable = false)
    private Integer ordinal;

    @Column(nullable = false)
    private String content;
}
