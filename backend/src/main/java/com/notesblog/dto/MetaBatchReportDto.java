package com.notesblog.dto;

import java.util.List;

/**
 * 批量补摘要/标签的结果。
 *
 * <p>分四个计数而不是一个「成功数」，是因为这四种结局对调用方的意义完全不同：
 * {@code generated} 是真正花了模型额度的，{@code skipped} 本来就有内容（没花钱），
 * {@code failed} 可以原样重试。
 *
 * @param requested 本次实际处理了几篇
 * @param generated 调了模型并成功写入的篇数
 * @param skipped   已有内容且未强制重写，因此没有调用模型的篇数
 * @param failed    调用或解析失败、可以重试的篇数
 * @param hasMore   库里是否还有待补的笔记；前端据此决定要不要再来一轮
 * @param remaining 处理完之后，全库仍缺摘要或标签的篇数
 * @param failures  失败明细，最多回 5 条，够定位问题即可
 */
public record MetaBatchReportDto(
        int requested,
        int generated,
        int skipped,
        int failed,
        boolean hasMore,
        long remaining,
        List<Failure> failures) {

    public record Failure(Long id, String title, String reason) {
    }
}
