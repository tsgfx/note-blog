package com.notesblog.service;

/**
 * embedding 依赖探活的抽象。backend=ollama 探 Ollama /api/tags，
 * backend=openai 探 OpenAI 兼容服务的 /models。
 */
public interface EmbeddingProbe {

    boolean ping();
}
