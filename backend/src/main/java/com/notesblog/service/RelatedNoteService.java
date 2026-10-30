package com.notesblog.service;

import com.notesblog.common.NotFoundException;
import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.dto.RelatedNoteDto;
import com.notesblog.repository.NoteRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 相关笔记推荐：复用已建好的分片向量，**不调 embedding 接口**。
 *
 * <p>思路：一篇笔记的向量 = 它各分片向量的均值（再归一化）；两篇笔记的相似度 =
 * 当前笔记向量与对方「最相似分片」的余弦。用 max 而不是均值，是因为一篇长笔记
 * 只有其中一段真正相关，把无关分片平均进来只会稀释分数。
 *
 * <p> Whole 流程都在内存索引（{@link VectorIndex}）上做，4715 个分片 × 1024 维
 * 一次点积遍历几十毫秒，请求开销可以忽略；笔记还没建索引（刚保存、异步重建中）
 * 时返回空列表，前端直接不渲染这一块。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RelatedNoteService {

    static final int DEFAULT_LIMIT = 5;
    static final int MAX_LIMIT = 10;
    /** 低于这个相似度的直接不要——宁可少推荐，也不要塞一堆「看起来相关」的噪音。 */
    static final double MIN_SCORE = 0.25;

    /**
     * 候选按目标条数的多少倍批量取。
     *
     * <p>公开页只收已发布文章，草稿会被过滤掉，所以不能只取 {@code wanted} 个；
     * 取 4 倍在「库里绝大多数是草稿」的极端情况下也只多读几十行。
     */
    private static final int CANDIDATE_FACTOR = 4;

    private final VectorIndex index;
    private final NoteRepository noteRepository;

    public List<RelatedNoteDto> related(long noteId, Integer limit) {
        return related(noteId, limit, false);
    }

    /** 公开文章相关推荐：只返回状态为已发布的文章，防止泄露草稿标题与内容。 */
    public List<RelatedNoteDto> relatedPublished(long noteId, Integer limit) {
        return related(noteId, limit, true);
    }

    public List<RelatedNoteDto> related(long noteId, Integer limit, boolean onlyPublished) {
        int wanted = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<VectorIndex.Chunk> chunks = index.chunks();
        float[] query = noteVector(chunks, noteId);
        if (query == null) {
            return List.of();
        }

        // 每篇笔记只保留最强的分片相似度
        Map<Long, Double> best = new HashMap<>();
        for (VectorIndex.Chunk chunk : chunks) {
            if (chunk.noteId() == noteId) {
                continue;
            }
            best.merge(chunk.noteId(), VectorIndex.dot(query, chunk.vector()), Math::max);
        }

        List<Map.Entry<Long, Double>> ranked = new ArrayList<>(best.entrySet());
        ranked.sort(Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder()));

        // 一次批量取回候选，而不是逐个 findById：公开文章页每次都会调这个接口，
        // 逐个查在最坏情况下是几十次往返。多取一些是为了抵消「草稿被过滤掉」的损耗。
        int candidates = Math.min(ranked.size(), wanted * CANDIDATE_FACTOR);
        List<Long> ids = new ArrayList<>(candidates);
        for (int i = 0; i < candidates && ranked.get(i).getValue() >= MIN_SCORE; i++) {
            ids.add(ranked.get(i).getKey());
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Note> loaded = new HashMap<>();
        noteRepository.findAllById(ids).forEach(note -> loaded.put(note.getId(), note));

        List<RelatedNoteDto> result = new ArrayList<>(wanted);
        for (Map.Entry<Long, Double> entry : ranked) {
            if (entry.getValue() < MIN_SCORE || result.size() == wanted) {
                break;
            }
            // 索引快照可能落后于删除：分片还在内存里、笔记已经没了，跳过即可
            Note note = loaded.get(entry.getKey());
            if (note == null) {
                continue;
            }
            if (onlyPublished && note.getStatus() != NoteStatus.PUBLISHED) {
                continue;
            }
            result.add(new RelatedNoteDto(
                    note.getId(), note.getTitle(), note.getSlug(), note.getSummary(),
                    note.getSourcePath(), Math.round(entry.getValue() * 1000) / 1000.0));
        }
        return result;
    }

    /** 当前笔记的分片向量均值（归一化后）。没有分片（未建索引/刚改完内容）返回 null。 */
    private float[] noteVector(List<VectorIndex.Chunk> chunks, long noteId) {
        int dim = -1;
        double[] acc = null;
        int count = 0;
        for (VectorIndex.Chunk chunk : chunks) {
            if (chunk.noteId() != noteId) {
                continue;
            }
            float[] vector = chunk.vector();
            if (acc == null) {
                dim = vector.length;
                acc = new double[dim];
            } else if (vector.length != dim) {
                // 维度不一致说明换过 embedding 模型、旧索引还没重建完，此时宁可不推荐
                return null;
            }
            for (int i = 0; i < dim; i++) {
                acc[i] += vector[i];
            }
            count++;
        }
        if (count == 0) {
            return null;
        }
        float[] mean = new float[dim];
        for (int i = 0; i < dim; i++) {
            mean[i] = (float) (acc[i] / count);
        }
        VectorIndex.normalize(mean);
        return mean;
    }

    /** 详情页侧边栏/底部卡片也会点进 slug，这里给 controller 复用同一套 404 语义。 */
    public void requireExists(long noteId) {
        noteRepository.findById(noteId).orElseThrow(() -> new NotFoundException("笔记不存在：" + noteId));
    }
}
