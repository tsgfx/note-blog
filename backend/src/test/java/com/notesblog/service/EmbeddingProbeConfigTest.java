package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.notesblog.config.NotesProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** probes 必须使用 typed notes.embedding 配置，并按各自服务语义探测。 */
class EmbeddingProbeConfigTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("Ollama probe 使用 canonical base-url 并命中 /api/tags")
    void ollamaProbeUsesCanonicalBaseUrl() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        server = responder(200, exchange -> path.set(exchange.getRequestURI().getPath()));
        String base = baseUrl() + "/";
        OllamaProbe probe = new OllamaProbe(properties(base, ""));

        assertThat(probe.ping()).isTrue();
        assertThat(path.get()).isEqualTo("/api/tags");
    }

    @Test
    @DisplayName("OpenAI probe 使用 canonical base-url/api-key 并命中 /models")
    void openAiProbeUsesCanonicalBaseUrlAndKey() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = responder(200, exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        });
        OpenAiEmbeddingProbe probe = new OpenAiEmbeddingProbe(properties(baseUrl() + "/", "secret"));

        assertThat(probe.ping()).isTrue();
        assertThat(path.get()).isEqualTo("/models");
        assertThat(authorization.get()).isEqualTo("Bearer secret");
    }

    @Test
    @DisplayName("监控探测失败只返回 false，不把外部服务故障扩散成应用异常")
    void unavailableProbeReturnsFalse() {
        OpenAiEmbeddingProbe probe = new OpenAiEmbeddingProbe(properties("http://127.0.0.1:1", ""));
        assertThat(probe.ping()).isFalse();
    }

    private static NotesProperties properties(String baseUrl, String apiKey) {
        return new NotesProperties(
                new NotesProperties.Embedding("ollama", baseUrl, apiKey, "bge-m3"),
                new NotesProperties.Llm("", "", "", ""));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpServer responder(int status, java.util.function.Consumer<com.sun.net.httpserver.HttpExchange> observer)
            throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            observer.accept(exchange);
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        http.start();
        return http;
    }
}
