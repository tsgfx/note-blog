package com.notesblog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定 application.yml 中的 notes.* 配置。
 */
@ConfigurationProperties(prefix = "notes")
public record NotesProperties(Embedding embedding, Llm llm) {

    /**
     * embedding 配置，backend 决定向量化走哪条路：
     * <ul>
     *   <li>{@code ollama}（默认）：base-url 指向 Ollama（本地容器 http://ollama:11434）</li>
     *   <li>{@code openai}：base-url 指向 OpenAI 兼容服务
     *       （如硅基流动 https://api.siliconflow.cn/v1），需要 api-key；
     *       模型必须与建库时一致（bge-m3，1024 维），否则检索全废</li>
     * </ul>
     */
    public record Embedding(String backend, String baseUrl, String apiKey, String model) {
    }

    public record Llm(String baseUrl, String model, String apiKey, String userAgent) {
    }
}
