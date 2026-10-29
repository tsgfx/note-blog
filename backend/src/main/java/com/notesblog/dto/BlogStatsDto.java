package com.notesblog.dto;

import java.time.LocalDateTime;

/**
 * 首页用的公开统计：只有聚合数字，不含任何标题、正文或标签名。
 *
 * <p>故意做成一条独立接口，而不是让前端从文章列表里数：
 * 列表有分页上限（单页最多 50），数出来的永远是"这一页有几篇"。
 *
 * <p><b>不含笔记总数</b>：接口是 permitAll，返回草稿数等于告诉访客
 * 「库里还有多少篇私密笔记」。只回已发布相关的数字。
 *
 * @param published       已发布文章数
 * @param tags            已发布文章用到的标签数（去重）
 * @param latestPublished 最近一次发布时间；从未发布过时为 {@code null}
 */
public record BlogStatsDto(long published, long tags, LocalDateTime latestPublished) {
}
