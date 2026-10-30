package com.notesblog.service;

import com.notesblog.common.NotFoundException;
import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.domain.Tag;
import com.notesblog.dto.RelatedNoteDto;
import com.notesblog.repository.NoteRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 相关笔记推荐：多路特征加权（向量语义 + 标签重合 + 专栏系列）与类目打散重排。
 *
 * <p>1. 向量相似度：笔记向量均值与候选最强分片的余弦；
 * 2. 标签重合度：Jaccard 相似度；
 * 3. 专栏与类目：同专栏加权提升；
 * 4. 打散（Diversity Re-ranking）：避免同一目录下的文章扎堆霸榜，保持推荐内容丰富度。
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

        // 1. 向量初筛：每篇笔记保留最强分片相似度
        Map<Long, Double> bestVector = new HashMap<>();
        for (VectorIndex.Chunk chunk : chunks) {
            if (chunk.noteId() == noteId) {
                continue;
            }
            bestVector.merge(chunk.noteId(), VectorIndex.dot(query, chunk.vector()), Math::max);
        }

        List<Map.Entry<Long, Double>> ranked = new ArrayList<>(bestVector.entrySet());
        ranked.sort(Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder()));

        // 2. 批量拉取候选详情（多拉一些以满足状态过滤与多路重排）
        int candidatesCount = Math.min(ranked.size(), wanted * CANDIDATE_FACTOR);
        List<Long> ids = new ArrayList<>(candidatesCount);
        for (int i = 0; i < candidatesCount && ranked.get(i).getValue() >= MIN_SCORE; i++) {
            ids.add(ranked.get(i).getKey());
        }
        if (ids.isEmpty()) {
            return List.of();
        }

        Map<Long, Note> loaded = new HashMap<>();
        noteRepository.findAllById(ids).forEach(note -> loaded.put(note.getId(), note));

        Note currentNote = noteRepository.findById(noteId).orElse(null);
        Set<String> currentTags = extractTags(currentNote);
        String currentSeries = extractSeries(currentNote);
        String currentCategory = currentNote != null ? extractCategory(currentNote.getSourcePath()) : "";

        // 3. 多路特征综合打分
        record Candidate(Note note, double score, String category, String reason) {}
        List<Candidate> candidateList = new ArrayList<>();

        for (Map.Entry<Long, Double> entry : ranked) {
            if (entry.getValue() < MIN_SCORE) {
                break;
            }
            Note note = loaded.get(entry.getKey());
            if (note == null) {
                continue;
            }
            if (onlyPublished && note.getStatus() != NoteStatus.PUBLISHED) {
                continue;
            }

            double vectorSim = entry.getValue();
            Set<String> candidateTags = extractTags(note);
            String candidateSeries = extractSeries(note);
            String candidateCategory = extractCategory(note.getSourcePath());

            double tagJaccard = 0.0;
            String sharedTag = null;
            if (!currentTags.isEmpty() && !candidateTags.isEmpty()) {
                Set<String> intersection = new HashSet<>(currentTags);
                intersection.retainAll(candidateTags);
                Set<String> union = new HashSet<>(currentTags);
                union.addAll(candidateTags);
                tagJaccard = (double) intersection.size() / union.size();
                if (!intersection.isEmpty()) {
                    sharedTag = intersection.iterator().next();
                }
            }

            boolean sameSeries = currentSeries != null && currentSeries.equalsIgnoreCase(candidateSeries);
            boolean sameCategory = !currentCategory.isEmpty() && currentCategory.equalsIgnoreCase(candidateCategory);
            double seriesScore = sameSeries ? 1.0 : (sameCategory ? 0.4 : 0.0);

            // 多路加权打分：向量 60% + 标签 25% + 专栏系列 15%
            double finalScore = currentNote != null
                    ? (0.60 * vectorSim + 0.25 * tagJaccard + 0.15 * seriesScore)
                    : vectorSim;

            // 推荐原因
            String reason;
            if (sameSeries) {
                reason = "同专栏 · " + currentSeries;
            } else if (sharedTag != null) {
                reason = "共同标签 #" + sharedTag;
            } else {
                reason = "语义高相关 · " + (int) Math.round(finalScore * 100) + "%";
            }

            candidateList.add(new Candidate(note, finalScore, candidateCategory, reason));
        }

        // 按多路综合分降序排序
        candidateList.sort(Comparator.comparingDouble(Candidate::score).reversed());

        // 4. 类目打散重排（Diversity Re-ranking）：避免同目录扎堆
        List<Candidate> selected = new ArrayList<>();
        List<Candidate> deferred = new ArrayList<>();
        Map<String, Integer> categoryCounts = new HashMap<>();
        int maxPerCategory = Math.max(2, wanted / 2);

        for (Candidate cand : candidateList) {
            if (selected.size() == wanted) {
                break;
            }
            String cat = cand.category();
            int count = categoryCounts.getOrDefault(cat, 0);
            if (!cat.isEmpty() && count >= maxPerCategory) {
                deferred.add(cand);
            } else {
                selected.add(cand);
                if (!cat.isEmpty()) {
                    categoryCounts.put(cat, count + 1);
                }
            }
        }

        // 若打散后未填满 wanted，用候补队列补齐
        for (Candidate cand : deferred) {
            if (selected.size() == wanted) {
                break;
            }
            selected.add(cand);
        }

        List<RelatedNoteDto> result = new ArrayList<>(selected.size());
        for (Candidate cand : selected) {
            Note note = cand.note();
            double roundedScore = Math.round(cand.score() * 1000) / 1000.0;
            result.add(new RelatedNoteDto(
                    note.getId(), note.getTitle(), note.getSlug(), note.getSummary(),
                    note.getSourcePath(), roundedScore, cand.reason()));
        }
        return result;
    }

    private static Set<String> extractTags(Note note) {
        if (note == null || note.getTags() == null) {
            return Collections.emptySet();
        }
        Set<String> set = new HashSet<>();
        for (Tag tag : note.getTags()) {
            set.add(tag.getName().toLowerCase());
        }
        return set;
    }

    static String extractSeries(Note note) {
        if (note == null || note.getTags() == null) {
            return null;
        }
        for (Tag tag : note.getTags()) {
            String name = tag.getName();
            if (name.startsWith("专栏:") || name.startsWith("系列:")) {
                return name.substring(3).trim();
            }
        }
        return null;
    }

    static String extractCategory(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return "";
        }
        String clean = sourcePath.replace('\\', '/').trim();
        int lastSlash = clean.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "";
        }
        return clean.substring(0, lastSlash);
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
