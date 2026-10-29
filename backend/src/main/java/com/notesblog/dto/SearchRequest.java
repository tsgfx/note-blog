package com.notesblog.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SearchRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 1000, message = "问题不能超过 1000 个字符")
        String question,
        @Max(value = 20, message = "最多返回 20 条")
        Integer limit) {

    public int safeLimit() {
        return limit == null ? 8 : Math.max(1, limit);
    }
}
