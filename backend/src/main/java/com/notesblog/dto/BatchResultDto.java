package com.notesblog.dto;

import java.util.List;

/**
 * 批量操作结果。{@code succeeded + skipped == total} 恒成立，
 * 前端据此给出一句不含糊的提示。
 */
public record BatchResultDto(int total, int succeeded, int skipped, List<SkippedItem> skippedItems) {

    /** 单条被跳过的原因。title 可能为空（例如笔记已不存在）。 */
    public record SkippedItem(Long id, String title, String reason) {
    }
}
