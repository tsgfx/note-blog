package com.notesblog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notesblog.config.NotesProperties;
import java.util.List;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 调用 Ollama（{@code /api/embed}）把文本转为 1024 维向量。
 * backend=ollama（或缺省）时装配。
 */
@Component
@ConditionalOnProperty(name = "notes.embedding.backend", havingValue = "ollama", matchIfMissing = true)
public class OllamaEmbeddingClient implements EmbeddingClient {

    /**
     * 连接/读取超时。
     *
     * <p>没有读超时的后果是：Ollama 卡住（显存打满、模型加载中）时，
     * 请求线程会无限期挂在那里——问答、重建索引、公开检索都会跟着排队。
     * 60 秒足够冷加载 bge-m3，又不会让人等到怀疑站点挂了。
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final NotesProperties properties;
    private final ObjectMapper objectMapper;

    /** 每次调用都新建 RestClient 会丢掉底层连接池，改成构造一次复用。 */
    private final RestClient client;

    public OllamaEmbeddingClient(NotesProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.client = RestClient.builder()
                .baseUrl(properties.embedding().baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public List<Double> embed(String text) {
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
