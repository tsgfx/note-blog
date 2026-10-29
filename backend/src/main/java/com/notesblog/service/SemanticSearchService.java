package com.notesblog.service;

import com.notesblog.dto.SearchResultDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 混合检索：语义向量召回 + 精确关键词召回，用 RRF（倒数排名融合）合并。
 * 关键词召回专门解决 sm_120、MAX_JOBS 这类标识符语义向量匹配不稳的问题。
 */
@Service
@RequiredArgsConstructor
public class SemanticSearchService {

    private static final int CANDIDATES = 40;
    private static final int RRF_K = 60;
    private static final int PER_NOTE_LIMIT = 2;
    /** 关键词命中在融合时加权，使精确标识符在排名平手时胜出。 */
    private static final double KEYWORD_WEIGHT = 1.5;
    private static final Pattern ASCII_TOKEN = Pattern.compile("[A-Za-z0-9_.\\-]{3,}");
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]{2,}");

    private final EmbeddingClient embeddingClient;
    private final VectorIndex index;

    public List<SearchResultDto> search(String question, int limit) {
        int wanted = Math.min(Math.max(limit, 1), 20);
        List<VectorIndex.Chunk> chunks = index.chunks();
        float[] query = toFloats(embeddingClient.embed(question));
        VectorIndex.normalize(query);

        List<VectorIndex.Chunk> byVector = top(chunks, c -> VectorIndex.dot(query, c.vector()), CANDIDATES, 0);
        Set<String> tokens = keywordTokens(question);
        List<VectorIndex.Chunk> byKeyword = top(chunks, c -> keywordScore(tokens, c.searchText()),
                CANDIDATES, 0);

        Map<Long, Double> fused = new HashMap<>();
        Map<Long, VectorIndex.Chunk> byId = new HashMap<>();
        accumulate(byVector, 1.0, fused, byId);
        accumulate(byKeyword, KEYWORD_WEIGHT, fused, byId);

        List<Map.Entry<Long, Double>> ranked = new ArrayList<>(fused.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        Map<Long, Integer> perNote = new HashMap<>();
        List<SearchResultDto> results = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : ranked) {
            VectorIndex.Chunk chunk = byId.get(entry.getKey());
            int used = perNote.getOrDefault(chunk.noteId(), 0);
            if (used >= PER_NOTE_LIMIT) {
                continue;
            }
            perNote.put(chunk.noteId(), used + 1);
            results.add(new SearchResultDto(chunk.noteId(), chunk.title(), chunk.slug(),
                    chunk.heading(), chunk.content(), entry.getValue()));
            if (results.size() == wanted) {
                break;
            }
        }
        return results;
    }

    private static void accumulate(List<VectorIndex.Chunk> ranking, double weight,
                                   Map<Long, Double> fused, Map<Long, VectorIndex.Chunk> byId) {
        for (int rank = 0; rank < ranking.size(); rank++) {
            VectorIndex.Chunk chunk = ranking.get(rank);
            byId.put(chunk.id(), chunk);
            fused.merge(chunk.id(), weight / (RRF_K + rank + 1), Double::sum);
        }
    }

    /** 用小顶堆取前 k 个，避免对全部分片排序。score <= minScore 的分片不参与。 */
    private static List<VectorIndex.Chunk> top(List<VectorIndex.Chunk> chunks,
                                               java.util.function.ToDoubleFunction<VectorIndex.Chunk> scorer,
                                               int k, double minScore) {
        record Scored(VectorIndex.Chunk chunk, double score) {
        }
        PriorityQueue<Scored> heap = new PriorityQueue<>((a, b) -> Double.compare(a.score(), b.score()));
        for (VectorIndex.Chunk chunk : chunks) {
            double score = scorer.applyAsDouble(chunk);
            if (score <= minScore) {
                continue;
            }
            if (heap.size() < k) {
                heap.add(new Scored(chunk, score));
            } else if (score > heap.peek().score()) {
                heap.poll();
                heap.add(new Scored(chunk, score));
            }
        }
        List<Scored> sorted = new ArrayList<>(heap);
        sorted.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<VectorIndex.Chunk> result = new ArrayList<>(sorted.size());
        sorted.forEach(s -> result.add(s.chunk()));
        return result;
    }

    /** 英文/标识符整词命中权重 1，中文二元组命中权重 0.1（中文二元组过于常见）。 */
    static Set<String> keywordTokens(String question) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher ascii = ASCII_TOKEN.matcher(question);
        while (ascii.find()) {
            tokens.add(ascii.group().toLowerCase(Locale.ROOT));
        }
        Matcher cjk = CJK_RUN.matcher(question);
        while (cjk.find()) {
            String run = cjk.group();
            for (int i = 0; i + 2 <= run.length(); i++) {
                tokens.add(run.substring(i, i + 2));
            }
        }
        return tokens;
    }

    static double keywordScore(Set<String> tokens, String searchText) {
        double score = 0;
        for (String token : tokens) {
            if (searchText.contains(token)) {
                score += token.length() == 2 && token.codePointAt(0) >= 0x4e00 ? 0.1 : 1.0;
            }
        }
        return score;
    }

    private static float[] toFloats(List<Double> values) {
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i).floatValue();
        }
        return out;
    }
}
