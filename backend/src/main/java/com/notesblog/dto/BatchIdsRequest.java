package com.notesblog.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 批量操作的入参。上限与 NoteService.BATCH_LIMIT 保持一致。 */
public record BatchIdsRequest(
        @NotEmpty(message = "请选择要操作的笔记")
        @Size(max = 200, message = "一次最多操作 200 篇")
        List<@NotNull Long> ids) {
}
