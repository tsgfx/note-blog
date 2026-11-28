package com.notesblog.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.notesblog.config.NotesProperties;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 探一下 Ollama 还活着没有。
 *
 * <p>用 {@code /api/tags} 而不是探端口：端口能连上不代表服务可用，
 * 而这个接口是 Ollama 自己的公开列表接口，成本极低、不需要模型加载。
 *
 * <p>超时必须短（1s）。健康检查本身不能成为拖慢页面的原因——
 * 检查一个已经挂掉的服务时，等它超时比直接报 false 更糟。
 */
@Component
@ConditionalOnProperty(name = "notes.embedding.backend", havingValue = "ollama", matchIfMissing = true)
public class OllamaProbe implements EmbeddingProbe {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final HttpClient client;
    private final String baseUrl;

    /**
     * 配置对象已经有 typed canonical 来源；这里只保留监控降级语义：
     * 读不到地址时退化成本地默认值、报告 ollama 不可用，
     * 而不是让整个应用启动失败——监控不该成为新的故障源。
     */
    public OllamaProbe(NotesProperties properties) {
        String configured = properties.embedding().baseUrl();
        String normalized = configured == null || configured.isBlank() ? "http://localhost:11434" : configured.trim();
        this.baseUrl = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
        this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public boolean ping() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // 连不上、超时、DNS 失败都算不可用，不需要区分
            return false;
        }
    }
}
