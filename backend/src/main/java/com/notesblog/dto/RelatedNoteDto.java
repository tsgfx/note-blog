package com.notesblog.dto;

/**
 * 相关笔记卡片。比 NoteSummaryDto 更轻：不需要状态、时间、标签，
 * 多带一个 score 方便排查「为什么推荐它」。
 *
 * @param score 与当前笔记的相似度（0~1，当前笔记各分片向量均值与对方最强分片的余弦）
 */
public record RelatedNoteDto(Long id, String title, String slug, String summary, String sourcePath, double score) {
}
