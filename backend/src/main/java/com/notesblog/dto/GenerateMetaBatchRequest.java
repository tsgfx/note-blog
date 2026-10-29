package com.notesblog.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量补摘要/标签的入参。
 *
 * <p>{@code ids} 为空表示「按库里缺什么补什么」，这是运维场景（一次性补齐历史笔记）；
 * 给了 {@code ids} 就是列表页勾选的那批。
 *
 * @param ids   指定笔记；为空则按「摘要或标签缺失」自动挑选
 * @param force 是否连已有内容一起重写（默认否，只补空白的）
 * @param limit 本次最多处理几篇；单篇要等模型两三秒，批太大前端会长时间转圈
 */
public record GenerateMetaBatchRequest(
        @Size(max = 200, message = "一次最多指定 200 篇")
        List<@NotNull Long> ids,

        Boolean force,

        @Min(value = 1, message = "至少处理 1 篇")
        @Max(value = 10, message = "一批最多 10 篇")
        Integer limit) {

    public boolean forceOrDefault() {
        return Boolean.TRUE.equals(force);
    }

    public int limitOrDefault() {
        return limit == null ? 5 : limit;
    }
}
