package com.notesblog.dto;

/**
 * 补建索引的结果。
 *
 * <p>只回「排队了几篇」而不是「补成功几篇」：索引是异步跑的，
 * 同步等若干篇 × 若干分片的嵌入调用会把请求拖到超时。
 * 真实结果看日志里的 {@code note_reindexed}。
 *
 * @param queued    这一轮排进队列的篇数
 * @param remaining 处理之后，全库仍缺分片的篇数。前端据此循环调用——
 *                  线程池队列只有 20 个槽位，整库缺分片时必须点很多次，
 *                  没有这个数用户不知道自己还要点几下
 */
public record ReindexReportDto(int queued, long remaining) {
}
