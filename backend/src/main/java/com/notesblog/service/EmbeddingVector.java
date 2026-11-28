package com.notesblog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** embedding 契约：所有 provider 都必须返回 bge-m3 的 1024 维向量。 */
final class EmbeddingVector {
    private static final int DIMENSIONS = 1024;

    private EmbeddingVector() {
    }

    static List<Double> require1024(JsonNode node, ObjectMapper objectMapper) {
        List<Double> vector = objectMapper.convertValue(node,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Double.class));
        if (vector.size() != DIMENSIONS) {
            throw new IllegalStateException("embedding 维度错误：" + vector.size() + "（应为 " + DIMENSIONS + "）");
        }
        return vector;
    }
}
