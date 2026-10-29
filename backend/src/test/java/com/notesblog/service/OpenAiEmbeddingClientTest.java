package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notesblog.config.NotesProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 用 JDK 内置 HttpServer 起一个假的 OpenAI 兼容 embeddings 端点，
 * 走真实 HTTP 链路验证请求构造（模型名、鉴权头）与响应解析、维度校验。
 */
class OpenAiEmbeddingClientTest {

    private HttpServer server;
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final List<byte[]> requestBodies = new CopyOnWriteArrayList<>();
    private String responseBody = "{}";
    private OpenAiEmbeddingClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBodies.add(exchange.getRequestBody().readAllBytes());
            byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (var out = exchange.getResponseBody()) {
                out.write(resp);
            }
        });
        server.start();
        NotesProperties properties = new NotesProperties(
                new NotesProperties.Embedding("openai",
                        "http://localhost:" + server.getAddress().getPort() + "/v1",
                        "sk-test-key", "BAAI/bge-m3"),
                new NotesProperties.Llm("", "", "", ""));
        client = new OpenAiEmbeddingClient(
                org.springframework.web.client.RestClient.builder(), properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static String vectorJson(int dims) {
        StringBuilder sb = new StringBuilder("{\"data\":[{\"embedding\":[");
        for (int i = 0; i < dims; i++) {
            if (i > 0) sb.append(',');
            sb.append(i * 0.001);
        }
        return sb.append("]}],\"usage\":{\"total_tokens\":7}}").toString();
    }

    @Test
    @DisplayName("正常返回 → 解析出 1024 维，带模型名与 Bearer 头")
    void parsesEmbedding() {
        responseBody = vectorJson(1024);

        List<Double> vector = client.embed("测试文本");

        assertThat(vector).hasSize(1024);
        assertThat(authHeaders).containsExactly("Bearer sk-test-key");
        String body = new String(requestBodies.get(0), StandardCharsets.UTF_8);
        assertThat(body).contains("\"model\":\"BAAI/bge-m3\"").contains("\"input\":\"测试文本\"");
    }

    @Test
    @DisplayName("维度不是 1024 → 拒绝并提示检查模型")
    void rejectsWrongDimensions() {
        responseBody = vectorJson(512);

        assertThatThrownBy(() -> client.embed("测试文本"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1024");
    }

    @Test
    @DisplayName("响应缺 data 数组 → 明确报错而不是 NPE")
    void rejectsMissingData() {
        responseBody = "{\"error\":\"quota exceeded\"}";

        assertThatThrownBy(() -> client.embed("测试文本"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embedding API");
    }
}
