package com.notesblog.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 探 OpenAI 兼容 embedding 服务（如硅基流动）是否可用。
 *
 * <p>用 {@code GET /models} 探活：轻量、几乎必有、顺便验证 API key 有效
 * （key 无效会返回 401，ping 同样判 false——key 错和宕机对调用方都是"embedding 不可用"）。
 *
 * <p>超时必须短（1s），理由同 OllamaProbe：监控不能成为新的故障源。
 */
@Component
@ConditionalOnProperty(name = "notes.embedding.backend", havingValue = "openai")
public class OpenAiEmbeddingProbe implements EmbeddingProbe {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final HttpClient client;
    private final String baseUrl;
    private final String apiKey;

    public OpenAiEmbeddingProbe(@Value("${notes.embedding.base-url:}") String baseUrl,
                                @Value("${notes.embedding.api-key:}") String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public boolean ping() {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/models"))
                    .timeout(TIMEOUT)
                    .GET();
            if (!apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
