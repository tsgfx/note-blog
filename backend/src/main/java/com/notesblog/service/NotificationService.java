package com.notesblog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notesblog.config.SiteUrls;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 外部通知服务（Webhook）。
 *
 * <p>目前用于新评论即时触达作者。支持飞书、钉钉、企业微信机器人、Bark 以及通用 Webhook。
 * 未配置 webhook-url 时静默跳过；发送采用异步非阻塞，即使外部服务超时或异常也绝不影响主业务。
 */
@Slf4j
@Service
public class NotificationService {

    private final String webhookUrl;
    private final SiteUrls siteUrls;
    private final HttpClient httpClient;
    private final NotificationPayloadFormatter payloadFormatter;
    /** 旁路池：外部 webhook 可能挂 4 秒，绝不能占请求线程或 commonPool。 */
    private final Executor executor;

    @Autowired
    public NotificationService(
            @Value("${app.notification.webhook-url:}") String webhookUrl,
            SiteUrls siteUrls,
            ObjectMapper objectMapper,
            @Qualifier("sideEffectExecutor") Executor executor) {
        this(webhookUrl, siteUrls, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build(), executor);
    }

    /** 供测试直接构造（默认 HttpClient，同步执行） */
    public NotificationService(String webhookUrl, SiteUrls siteUrls, ObjectMapper objectMapper) {
        this(webhookUrl, siteUrls, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build(), Runnable::run);
    }

    /** 供测试注入 HttpClient（不给 executor 时同步执行，断言更确定） */
    public NotificationService(
            String webhookUrl,
            SiteUrls siteUrls,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this(webhookUrl, siteUrls, objectMapper, httpClient, Runnable::run);
    }

    /** 供测试注入 HttpClient 与执行器。 */
    public NotificationService(
            String webhookUrl,
            SiteUrls siteUrls,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            Executor executor) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.siteUrls = siteUrls;
        this.payloadFormatter = new NotificationPayloadFormatter(this.webhookUrl, objectMapper);
        this.httpClient = httpClient;
        this.executor = executor;
    }

    /** 是否启用了通知 */
    public boolean isEnabled() {
        return !webhookUrl.isBlank();
    }

    /**
     * 新评论通知。异步发送，永不抛出异常。
     *
     * @param postTitle 文章标题
     * @param postSlug 文章 slug（拼公开访问链接）
     * @param commentContent 评论文本
     * @param clientIp 评论者 IP
     */
    public void notifyNewComment(String postTitle, String postSlug, String commentContent, String clientIp) {
        if (!isEnabled()) {
            return;
        }

        try {
        CompletableFuture.runAsync(() -> {
            try {
                String articleUrl = siteUrls.post(postSlug);
                String truncatedContent = truncate(commentContent, 300);
                String bodyJson = payloadFormatter.commentPayload(siteUrls.title(), postTitle, articleUrl, truncatedContent, clientIp);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl))
                        .timeout(Duration.ofSeconds(4))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    log.warn("notification_webhook_error status={} body={}", response.statusCode(), truncate(response.body(), 200));
                } else {
                    log.info("notification_webhook_sent slug={}", postSlug);
                }
            } catch (Exception e) {
                log.warn("notification_webhook_failed slug={} error={}", postSlug, e.getMessage());
            }
        }, executor);
        } catch (RejectedExecutionException e) {
            log.debug("notification_dropped slug={}", postSlug);
        }
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }
}
