package com.notesblog.service;

import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.domain.Tag;
import com.notesblog.dto.NoteSummaryDto;
import com.notesblog.repository.NoteRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 博客首页与推荐流服务：结合隐式兴趣画像、向量语义召回、标签偏好与类目打散重排。
 *
 * <p>1. 冷启动（新访客无历史）：热门度与时效加权，按类目打散输出全局精选；
 * 2. 个性化（访客有浏览足迹）：
 *    - 聚合历史文章的分片向量，构造读者即时兴趣向量；
 *    - 聚合读者偏好的高频标签与专栏；
 *    - 召回候选并计算综合得分，排除已读篇目；
 *    - 类目打散重排，输出带有透传推荐理由（reason）的个性化信息流。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 30;

    private final VectorIndex vectorIndex;
    private final NoteRepository noteRepository;

    public List<NoteSummaryDto> recommend(List<String> historySlugs, Integer limit) {
        int wanted = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);

        // 1. 尝试从历史记录中解析已发布文章
        List<Note> historyNotes = resolveHistoryNotes(historySlugs);

        // 若无有效历史记录，进入冷启动精选分支
        if (historyNotes.isEmpty()) {
            return recommendColdStart(wanted);
        }

        // 2. 个性化兴趣画像推荐
        return recommendPersonalized(historyNotes, wanted);
    }

    /**
     * 冷启动推荐：精选高阅读量且近期发布的优质文章，进行类目打散。
     */
    public List<NoteSummaryDto> recommendColdStart(int wanted) {
        // 取前 40 篇候选
        var pageable = PageRequest.of(0, Math.max(wanted * 3, 30),
                Sort.by(Sort.Order.desc("viewCount"), Sort.Order.desc("publishedAt")));
        List<Note> candidates = noteRepository.findByStatus(NoteStatus.PUBLISHED, pageable).getContent();

        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 类目打散
        List<Note> diversified = diversifyByCategory(candidates, wanted);

        List<NoteSummaryDto> list = new ArrayList<>(diversified.size());
        for (Note note : diversified) {
            String reason = note.getViewCount() > 0
                    ? "热门精选 · " + note.getViewCount() + " 次阅读"
                    : "编辑精选 · 最新发布";
            list.add(NoteSummaryDto.from(note).withRecommendReason(reason));
        }
        return list;
    }

    /**
     * 基于访客足迹的个性化兴趣推荐。
     */
    private List<NoteSummaryDto> recommendPersonalized(List<Note> historyNotes, int wanted) {
        Set<Long> seenIds = new HashSet<>();
        Map<String, Integer> tagFrequency = new HashMap<>();

        for (Note h : historyNotes) {
            seenIds.add(h.getId());
            if (h.getTags() != null) {
                for (Tag tag : h.getTags()) {
                    tagFrequency.merge(tag.getName().toLowerCase(), 1, Integer::sum);
                }
            }
        }

        // 构造用户短期兴趣向量（最近阅读权重更高）
        float[] userInterestVector = buildUserInterestVector(historyNotes);

        List<VectorIndex.Chunk> chunks = vectorIndex.chunks();
        // 如果向量索引暂不可用，降级为基于标签和热度的偏好推荐
        Map<Long, Double> vectorScores = new HashMap<>();
        if (userInterestVector != null) {
            for (VectorIndex.Chunk chunk : chunks) {
                if (seenIds.contains(chunk.noteId())) {
                    continue;
                }
                double dot = VectorIndex.dot(userInterestVector, chunk.vector());
                vectorScores.merge(chunk.noteId(), dot, Math::max);
            }
        }

        // 批量拉取候选文章（只查已发布，排除已读）
        var pageable = PageRequest.of(0, 100, Sort.by(Sort.Order.desc("viewCount")));
        List<Note> allPublished = noteRepository.findByStatus(NoteStatus.PUBLISHED, pageable).getContent();

        record ScoredCandidate(Note note, double score, String reason) {}
        List<ScoredCandidate> scoredList = new ArrayList<>();

        for (Note note : allPublished) {
            if (seenIds.contains(note.getId())) {
                continue;
            }

            double vScore = Math.max(0.0, vectorScores.getOrDefault(note.getId(), 0.0));

            // 标签重合加分
            int tagHitCount = 0;
            String bestHitTag = null;
            if (note.getTags() != null) {
                for (Tag tag : note.getTags()) {
                    String tagName = tag.getName().toLowerCase();
                    if (tagFrequency.containsKey(tagName)) {
                        tagHitCount += tagFrequency.get(tagName);
                        if (bestHitTag == null) {
                            bestHitTag = tag.getName();
                        }
                    }
                }
            }
            double tagScore = Math.min(1.0, tagHitCount * 0.3);

            // 热度温和提权（log 衰减，避免老文章纯靠阅读量霸榜）
            double viewBoost = Math.min(0.2, Math.log1p(note.getViewCount()) / 25.0);

            // 综合打分：向量语义 55% + 标签偏好 30% + 热度 15%
            double finalScore = 0.55 * vScore + 0.30 * tagScore + 0.15 * viewBoost;

            String reason;
            if (bestHitTag != null) {
                reason = "基于你常看的 #" + bestHitTag;
            } else if (vScore > 0.45) {
                reason = "猜你喜欢 · 深度契合";
            } else {
                reason = "个性化精选 · 热度推荐";
            }

            scoredList.add(new ScoredCandidate(note, finalScore, reason));
        }

        scoredList.sort(Comparator.comparingDouble(ScoredCandidate::score).reversed());

        // 提取候选笔记并进行类目打散
        List<Note> topNotes = scoredList.stream().map(ScoredCandidate::note).toList();
        List<Note> diversified = diversifyByCategory(topNotes, wanted);

        Map<Long, String> noteReasons = new HashMap<>();
        for (ScoredCandidate cand : scoredList) {
            noteReasons.put(cand.note().getId(), cand.reason());
        }

        List<NoteSummaryDto> result = new ArrayList<>(diversified.size());
        for (Note note : diversified) {
            String reason = noteReasons.getOrDefault(note.getId(), "个性化推荐");
            result.add(NoteSummaryDto.from(note).withRecommendReason(reason));
        }
        return result;
    }

    /**
     * 类目打散（Diversity Re-ranking）：限制单个类目/父级目录在最终结果中的数量，避免同质化。
     */
    private List<Note> diversifyByCategory(List<Note> input, int wanted) {
        if (input.size() <= wanted) {
            return input;
        }

        int maxPerCategory = Math.max(2, wanted / 3);
        Map<String, Integer> categoryCounts = new HashMap<>();
        List<Note> selected = new ArrayList<>(wanted);
        List<Note> deferred = new ArrayList<>();

        for (Note note : input) {
            if (selected.size() == wanted) {
                break;
            }
            String cat = RelatedNoteService.extractCategory(note.getSourcePath());
            int count = categoryCounts.getOrDefault(cat, 0);
            if (!cat.isEmpty() && count >= maxPerCategory) {
                deferred.add(note);
            } else {
                selected.add(note);
                if (!cat.isEmpty()) {
                    categoryCounts.put(cat, count + 1);
                }
            }
        }

        for (Note note : deferred) {
            if (selected.size() == wanted) {
                break;
            }
            selected.add(note);
        }
        return selected;
    }

    private List<Note> resolveHistoryNotes(List<String> historySlugs) {
        if (historySlugs == null || historySlugs.isEmpty()) {
            return Collections.emptyList();
        }
        List<Note> list = new ArrayList<>();
        for (String slug : historySlugs) {
            if (slug == null || slug.isBlank()) continue;
            Optional<Note> opt = noteRepository.findBySlug(slug.trim());
            if (opt.isPresent() && opt.get().getStatus() == NoteStatus.PUBLISHED) {
                list.add(opt.get());
            }
        }
        return list;
    }

    /**
     * 合并历史笔记分片向量，生成用户短期兴趣向量。
     */
    private float[] buildUserInterestVector(List<Note> historyNotes) {
        List<VectorIndex.Chunk> allChunks = vectorIndex.chunks();
        if (allChunks.isEmpty() || historyNotes.isEmpty()) {
            return null;
        }

        int dim = -1;
        double[] acc = null;
        double totalWeight = 0.0;

        for (int i = 0; i < historyNotes.size(); i++) {
            Note note = historyNotes.get(i);
            // 越新的浏览记录权重越高：1.0, 0.77, 0.62...
            double decay = 1.0 / (1.0 + 0.3 * i);

            for (VectorIndex.Chunk chunk : allChunks) {
                if (Objects.equals(chunk.noteId(), note.getId())) {
                    float[] vec = chunk.vector();
                    if (acc == null) {
                        dim = vec.length;
                        acc = new double[dim];
                    } else if (vec.length != dim) {
                        return null;
                    }
                    for (int d = 0; d < dim; d++) {
                        acc[d] += vec[d] * decay;
                    }
                    totalWeight += decay;
                }
            }
        }

        if (totalWeight <= 0.0 || acc == null) {
            return null;
        }

        float[] result = new float[dim];
        for (int d = 0; d < dim; d++) {
            result[d] = (float) (acc[d] / totalWeight);
        }
        VectorIndex.normalize(result);
        return result;
    }
}
