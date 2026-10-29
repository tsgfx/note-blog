package com.notesblog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notesblog.config.NotesProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** OpenAI-compatible SSE 客户端；只把正文增量交给上层。 */
@Component
@RequiredArgsConstructor
public class LlmStreamingClient {

    private final NotesProperties properties;
    private final ObjectMapper objectMapper;

    public void stream(String system, String user, Consumer<String> onText) {
        var config = properties.llm();
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("未配置 LLM_API_KEY");
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(java.util.Map.of(
                    "model", config.model(),
                    "messages", java.util.List.of(
                            java.util.Map.of("role", "system", "content", system),
                            java.util.Map.of("role", "user", "content", user)),
                    "temperature", 0.3,
                    "max_tokens", 4000,
                    "stream", true));
        } catch (IOException e) {
            throw new IllegalStateException("LLM 请求序列化失败", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/chat/completions"))
                .timeout(Duration.ofMinutes(3))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("User-Agent", config.userAgent())
                .header("x-opencode-session", java.util.UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<java.util.stream.Stream<String>> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                String detail = response.body().limit(3).reduce("", (a, b) -> a + b);
                throw toException(response.statusCode(), detail);
            }

            StringBuilder raw = new StringBuilder();
            int sent = 0;
            try (var lines = response.body()) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode root;
                    try {
                        root = objectMapper.readTree(data);
                    } catch (Exception ignored) {
                        continue;
                    }
                    JsonNode content = root.path("choices").path(0).path("delta").path("content");
                    if (!content.isTextual()) {
                        continue;
                    }
                    raw.append(content.asText());
                    String visible = stripThink(raw.toString());
                    if (visible.length() > sent) {
                        onText.accept(visible.substring(sent));
                        sent = visible.length();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.TRANSIENT, "大模型请求被中断", e);
        } catch (IOException e) {
            throw new LlmException(LlmException.Kind.TRANSIENT, "无法连接大模型服务，请检查网络", e);
        }
    }

    /** 把网关错误翻译成可区分的异常：429 属于配额问题，其余按可重试处理。 */
    private static LlmException toException(int status, String detail) {
        String message = "LLM 请求失败：HTTP " + status + " " + detail;
        if (status == 429) {
            if (detail.contains("UsageLimit") || detail.contains("usage limit")) {
                return new LlmException(LlmException.Kind.QUOTA, "大模型订阅额度已用尽，请等待额度重置或更换模型");
            }
            return new LlmException(LlmException.Kind.TRANSIENT, "请求过于频繁，请稍后重试");
        }
        if (status >= 500) {
            return new LlmException(LlmException.Kind.TRANSIENT, "大模型服务暂时不可用，请稍后重试");
        }
        return new LlmException(LlmException.Kind.TRANSIENT, message);
    }

    private String stripThink(String text) {
        String complete = text.replaceAll("(?is)<think(?:ing)?>.*?</think(?:ing)?>", "");
        return complete.replaceAll("(?is)<think(?:ing)?>.*$", "");
    }
}
