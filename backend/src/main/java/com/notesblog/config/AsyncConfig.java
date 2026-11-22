package com.notesblog.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 线程池。
 *
 * <p><b>为什么每个用途一个池，而不是都用 {@code CompletableFuture.*Async}：</b>
 * 不传 executor 时用的是 {@code ForkJoinPool.commonPool()}，它在 2 核机器上并行度只有 1。
 * 一旦有一路 3 分钟的 LLM 流占住那唯一的线程，健康检查、搜索日志、评论通知全都排在它后面，
 * 表现出来就是「整个站偶发卡死」。池子分开之后，慢的只会拖慢自己那一类任务。
 *
 * <p><b>Bean 名不能改：</b>{@code taskExecutor} 是 {@code @Async} 的默认查找名，
 * {@code NoteIndexService} 依赖它。其余池子用具名 {@link ExecutorService} 注入，
 * 不声明成 {@code TaskExecutor}，避免和 {@code @Async} 的解析打架。
 */
@Slf4j
@Configuration
public class AsyncConfig {

    /** 笔记索引（{@code @Async}）：任务短，但要串行保护向量文件。 */
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("note-index-");
        executor.initialize();
        return executor;
    }

    /**
     * 问答流：一个任务 = 一次最长 3 分钟的 SSE 会话，而且占着 LLM 配额。
     *
     * <p>上限压得低是有意的——并发问答应按「同时烧多少 token」算，不是按 CPU 算。
     * 池满时抛 {@link java.util.concurrent.RejectedExecutionException}，由调用方把那一路
     * SSE 用错误事件收尾，好过无限排队后前端等到超时。
     */
    @Bean(name = "askExecutor", destroyMethod = "shutdown")
    public ExecutorService askExecutor() {
        return new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                Thread.ofPlatform().name("ask-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 旁路任务：搜索日志、评论通知。丢了不影响正确性，所以池满时**丢弃并计数**，
     * 绝不回压到请求线程——把一次检索请求拖慢去写统计，是本末倒置。
     */
    @Bean(name = "sideEffectExecutor", destroyMethod = "shutdown")
    public ExecutorService sideEffectExecutor() {
        AtomicLong dropped = new AtomicLong();
        RejectedExecutionHandler discard = (task, pool) -> {
            long n = dropped.incrementAndGet();
            // 只在第一次和每 100 次打一条，池子真堵了也不会刷爆日志
            if (n == 1 || n % 100 == 0) {
                log.warn("side_effect_dropped total={} queue={}", n, pool.getQueue().size());
            }
        };
        return new ThreadPoolExecutor(
                1, 2, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(200),
                Thread.ofPlatform().name("side-", 0).factory(),
                discard);
    }

    /**
     * 健康探针：一条虚拟线程一次探测，队列不设上限也不会积压（任务本身由
     * {@code completeOnTimeout} 兜底）。用平台线程池会让「数据库卡住」变成
     * 「探针线程也卡住」。
     */
    @Bean(name = "healthExecutor", destroyMethod = "close")
    public ExecutorService healthExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}