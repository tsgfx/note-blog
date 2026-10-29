package com.notesblog.dto;

/** 一条检索命中。score 越大越相关（混合检索的融合分数）。 */
public record SearchResultDto(
        Long noteId,
        String title,
        String slug,
        String heading,
        String content,
        double score) {
}
