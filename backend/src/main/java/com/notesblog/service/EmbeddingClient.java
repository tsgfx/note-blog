package com.notesblog.service;

import java.util.List;

/**
 * 向量化客户端的抽象。实现按 notes.embedding.backend 装配：
 * ollama（默认，本机 Ollama）或 openai（OpenAI 兼容 API，如硅基流动）。
 *
 * <p>两个实现都必须返回 1024 维向量——库里 VECTOR(1024) 是硬约束，
 * 换维度等于重建整个 note_chunks 索引。
 */
public interface EmbeddingClient {

    List<Double> embed(String text);
}
