package com.notesblog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 各 webhook 提供商的消息体适配器。
 *
 * <p>这里只关心 payload schema，不关心网络、线程池或失败降级；
 * 这样传输副作用留在 {@link NotificationService}，供应商格式变化也只落在这一个位置。
 */
final class NotificationPayloadFormatter {

    private final String webhookUrl;
    private final ObjectMapper objectMapper;

    NotificationPayloadFormatter(String webhookUrl, ObjectMapper objectMapper) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl;
        this.objectMapper = objectMapper;
    }

    /** 根据 Webhook URL 特征构造适配的消息体。飞书、钉钉、企业微信、Bark 与通用 Webhook 一并兼容。 */
    String commentPayload(String siteTitle, String postTitle, String articleUrl, String commentContent, String clientIp) {
        String summary = String.format("【%s · 新评论提醒】\n文章：《%s》\n内容：%s\n链接：%s",
                siteTitle, postTitle, commentContent, articleUrl);

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
}
