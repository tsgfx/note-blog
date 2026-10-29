package com.notesblog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notesblog.config.NotesProperties;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 调用 Ollama（{@code /api/embed}）把文本转为 1024 维向量。
 * backend=ollama（或缺省）时装配。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "notes.embedding.backend", havingValue = "ollama", matchIfMissing = true)
public class OllamaEmbeddingClient implements EmbeddingClient {

    private final NotesProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public List<Double> embed(String text) {
        RestClient client = RestClient.builder()
                .baseUrl(properties.embedding().baseUrl())
                .build();
        JsonNode body = client.post()
                .uri("/api/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of(
                        "model", properties.embedding().model(),
                        "input", text,
                        "keep_alive", "1h"))
                .retrieve()
                .body(JsonNode.class);
        return extractVector(body);
    }

    private List<Double> extractVector(JsonNode body) {
        if (body == null || !body.has("embeddings") || !body.get("embeddings").isArray()
                || body.get("embeddings").isEmpty()) {
            throw new IllegalStateException("Ollama 未返回 embedding");
        }
        List<Double> vector = objectMapper.convertValue(
                body.get("embeddings").get(0),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Double.class));
        if (vector.size() != 1024) {
            throw new IllegalStateException("embedding 维度错误：" + vector.size());
        }
        return vector;
    }
}
