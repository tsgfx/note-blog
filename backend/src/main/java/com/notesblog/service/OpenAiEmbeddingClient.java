package com.notesblog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notesblog.config.NotesProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 调用 OpenAI 兼容的 {@code POST /v1/embeddings}（如硅基流动）把文本转为 1024 维向量。
 * backend=openai 时装配。
 *
 * <p>模型由 notes.embedding.model 决定（硅基流动上写作 BAAI/bge-m3）。
 * 云端部署用它替代 Ollama，省下 ~1.5G 内存；本地与 API 都是 bge-m3，向量互通。
 */
@Component
@ConditionalOnProperty(name = "notes.embedding.backend", havingValue = "openai")
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient client;
    private final NotesProperties properties;
    private final ObjectMapper objectMapper;

    public OpenAiEmbeddingClient(RestClient.Builder builder, NotesProperties properties,
                                 ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.client = builder
                .baseUrl(properties.embedding().baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.embedding().apiKey())
                .build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Double> embed(String text) {
        JsonNode body = client.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of(
                        "model", properties.embedding().model(),
                        "input", text))
                .retrieve()
                .body(JsonNode.class);
        return extractVector(body);
    }

    private List<Double> extractVector(JsonNode body) {
        if (body == null || !body.has("data") || !body.get("data").isArray()
                || body.get("data").isEmpty()) {
            throw new IllegalStateException("embedding API 未返回向量");
        }
        List<Double> vector = objectMapper.convertValue(
                body.get("data").get(0).get("embedding"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Double.class));
        if (vector.size() != 1024) {
            throw new IllegalStateException("embedding 维度错误：" + vector.size() + "（应为 1024，检查模型是否为 bge-m3）");
        }
        return vector;
    }
}
