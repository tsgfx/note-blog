package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("未配置 Webhook 时禁用，不发起网络请求")
    void disabledWhenUrlIsBlank() {
        HttpClient httpClient = mock(HttpClient.class);
        NotificationService service = new NotificationService("", "http://localhost:5173", objectMapper, httpClient);

        assertThat(service.isEnabled()).isFalse();

        service.notifyNewComment("文章标题", "post-slug", "评论内容", "127.0.0.1");
        verify(httpClient, never()).sendAsync(any(), any());
    }

    @Test
    @DisplayName("识别飞书 Webhook 格式")
    void formatsFeishuPayload() {
        NotificationService service = new NotificationService(
                "https://open.feishu.cn/open-apis/bot/v2/hook/xxx",
                "https://notes.example.com",
                objectMapper);

        String json = service.buildPayloadJson("文章一", "https://notes.example.com/blog/p1", "太精彩了", "1.1.1.1");
        assertThat(json).contains("\"msg_type\":\"text\"");
        assertThat(json).contains("\"content\":{\"text\":");
        assertThat(json).contains("【一隅 · 新评论提醒】");
        assertThat(json).contains("《文章一》");
    }

    @Test
    @DisplayName("识别钉钉 Webhook 格式")
    void formatsDingTalkPayload() {
        NotificationService service = new NotificationService(
                "https://oapi.dingtalk.com/robot/send?access_token=xxx",
                "https://notes.example.com",
                objectMapper);

        String json = service.buildPayloadJson("文章二", "https://notes.example.com/blog/p2", "受教了", "1.1.1.1");
        assertThat(json).contains("\"msgtype\":\"text\"");
        assertThat(json).contains("\"text\":{\"content\":");
        assertThat(json).contains("《文章二》");
    }

    @Test
    @DisplayName("识别企业微信机器人格式")
    void formatsWeComPayload() {
        NotificationService service = new NotificationService(
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx",
                "https://notes.example.com",
                objectMapper);

        String json = service.buildPayloadJson("文章三", "https://notes.example.com/blog/p3", "顶一下", "1.1.1.1");
        assertThat(json).contains("\"msgtype\":\"text\"");
        assertThat(json).contains("\"text\":{\"content\":");
        assertThat(json).contains("《文章三》");
    }

    @Test
    @DisplayName("识别 Bark 推送格式")
    void formatsBarkPayload() {
        NotificationService service = new NotificationService(
                "https://api.day.app/my-key",
                "https://notes.example.com",
                objectMapper);

        String json = service.buildPayloadJson("文章四", "https://notes.example.com/blog/p4", "写的真棒", "1.1.1.1");
        assertThat(json).contains("\"title\":\"新评论：《文章四》\"");
        assertThat(json).contains("\"body\":\"写的真棒\"");
        assertThat(json).contains("\"url\":\"https://notes.example.com/blog/p4\"");
    }

    @Test
    @DisplayName("通用 Webhook：携带结构化属性与文本兼容格式")
    void formatsGenericPayload() {
        NotificationService service = new NotificationService(
                "https://example.com/webhook",
                "https://notes.example.com",
                objectMapper);

        String json = service.buildPayloadJson("文章五", "https://notes.example.com/blog/p5", "好文", "1.1.1.1");
        assertThat(json).contains("\"articleTitle\":\"文章五\"");
        assertThat(json).contains("\"commentContent\":\"好文\"");
        assertThat(json).contains("\"clientIp\":\"1.1.1.1\"");
        assertThat(json).contains("\"msg_type\":\"text\"");
    }

    @Test
    @DisplayName("发送失败时捕获异常，绝不抛出")
    void networkFailureHandledGracefully() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(), any())).thenThrow(new IOException("Connection refused"));

        NotificationService service = new NotificationService(
                "https://example.com/webhook",
                "https://notes.example.com",
                objectMapper,
                httpClient);

        assertThatCode(() -> service.notifyNewComment("标题", "slug", "内容", "1.1.1.1"))
                .doesNotThrowAnyException();
    }
}
