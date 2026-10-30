package com.notesblog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public NotificationService(
            @Value("${app.notification.webhook-url:}") String webhookUrl,
            @Value("${site.base-url:http://localhost:5173}") String baseUrl,
            ObjectMapper objectMapper) {
        this(webhookUrl, baseUrl, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build());
    }

    /** 供测试注入 HttpClient */
    public NotificationService(
            String webhookUrl,
            String baseUrl,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
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

        CompletableFuture.runAsync(() -> {
            try {
                String articleUrl = baseUrl + "/blog/" + postSlug;
                String truncatedContent = truncate(commentContent, 300);
                String bodyJson = buildPayloadJson(postTitle, articleUrl, truncatedContent, clientIp);

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
        });
    }

    /**
     * 根据 Webhook URL 特征构造适配的消息体。
     * 飞书、钉钉、企业微信、Bark 与通用 Webhook 一并兼容。
     */
    public String buildPayloadJson(String postTitle, String articleUrl, String commentContent, String clientIp) {
        String summary = String.format("【一隅 · 新评论提醒】\n文章：《%s》\n内容：%s\n链接：%s",
                postTitle, commentContent, articleUrl);

        try {
            if (webhookUrl.contains("feishu") || webhookUrl.contains("larksuite")) {
                // 飞书自定义机器人格式
                return objectMapper.writeValueAsString(Map.of(
                        "msg_type", "text",
                        "content", Map.of("text", summary)
                ));
            } else if (webhookUrl.contains("dingtalk.com")) {
                // 钉钉自定义机器人格式
                return objectMapper.writeValueAsString(Map.of(
                        "msgtype", "text",
                        "text", Map.of("content", summary)
                ));
            } else if (webhookUrl.contains("weixin.qq.com")) {
                // 企业微信机器人格式
                return objectMapper.writeValueAsString(Map.of(
                        "msgtype", "text",
                        "text", Map.of("content", summary)
                ));
            } else if (webhookUrl.contains("api.day.app")) {
                // iOS Bark 推送格式
                return objectMapper.writeValueAsString(Map.of(
                        "title", "新评论：《" + postTitle + "》",
                        "body", commentContent,
                        "url", articleUrl,
                        "group", "一隅博客"
                ));
            }

            // 通用格式：同时携带各常见机器人的兼容字段与结构化键
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("msgtype", "text");
            payload.put("msg_type", "text");
            payload.put("text", Map.of("content", summary));
            payload.put("content", Map.of("text", summary));
            payload.put("title", "新评论：《" + postTitle + "》");
            payload.put("articleTitle", postTitle);
            payload.put("commentContent", commentContent);
            payload.put("clientIp", clientIp == null ? "" : clientIp);
            payload.put("url", articleUrl);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"text\":\"" + summary.replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
        }
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }
}
