package com.notesblog.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 基于物品的协同过滤推荐算法（Item-based Collaborative Filtering, ItemCF）。
 *
 * <p>核心理论基础：
 * 1. 物品相似度源于群体用户行为的共现性——「同时读过物品 A 和物品 B 的读者越多，说明二者越相关」；
 * 2. 引入 IUF（Inverse User Frequency）惩罚机制：活跃度极高的用户（如爬虫或走马观花的访客）
 *    对两个物品共现的贡献权重应予以对数衰减惩罚 1 / log(1 + |N(u)|)；
 * 3. 相似度矩阵归一化（Cosine Normalization）：防止超级热门大类霸榜。
 */
@Component
public class ItemCollaborativeFiltering {

    public record ScoredItem(Long itemId, double score) {}

    /** 物品被阅读的用户集合：itemId -> Set of user/session keys */
    private final Map<Long, Set<String>> itemUsers = new HashMap<>();

    /** 物品与物品的相似度矩阵：itemIdA -> (itemIdB -> similarity) */
    private final Map<Long, Map<Long, Double>> itemSimilarity = new HashMap<>();

    /**
     * 根据群体行为会话序列训练/构建物品相似度矩阵。
     *
     * @param userSessions 用户/会话历史，key 为用户或设备标识，value 为该用户阅读过的笔记 ID 集合
     */
    public synchronized void train(Map<String, ? extends Collection<Long>> userSessions) {
        itemUsers.clear();
        itemSimilarity.clear();

        if (userSessions == null || userSessions.isEmpty()) {
            return;
        }

        // 1. 建立物品 -> 用户倒排索引
        for (Map.Entry<String, ? extends Collection<Long>> entry : userSessions.entrySet()) {
            String user = entry.getKey();
            Collection<Long> items = entry.getValue();
            if (items == null || items.isEmpty()) {
                continue;
            }
            // 单会话内去重
            Set<Long> uniqueItems = new HashSet<>(items);
            for (Long item : uniqueItems) {
                itemUsers.computeIfAbsent(item, k -> new HashSet<>()).add(user);
            }
        }

        // 2. 计算共现次数与带有 IUF 惩罚的共现权重
        // C[i][j] = sum( 1 / log(1 + |I(u)|) )
        Map<Long, Map<Long, Double>> coOccurrence = new HashMap<>();
        for (Map.Entry<String, ? extends Collection<Long>> entry : userSessions.entrySet()) {
            Collection<Long> items = entry.getValue();
            if (items == null || items.size() < 2) {
                continue;
            }
            Set<Long> unique = new HashSet<>(items);
            double iufWeight = 1.0 / Math.log1p(unique.size()); // 惩罚刷量/超活跃用户

            List<Long> itemList = new ArrayList<>(unique);
            for (int i = 0; i < itemList.size(); i++) {
                Long itemA = itemList.get(i);
                for (int j = i + 1; j < itemList.size(); j++) {
                    Long itemB = itemList.get(j);

                    coOccurrence.computeIfAbsent(itemA, k -> new HashMap<>())
                            .merge(itemB, iufWeight, Double::sum);
                    coOccurrence.computeIfAbsent(itemB, k -> new HashMap<>())
                            .merge(itemA, iufWeight, Double::sum);
                }
            }
        }

        // 3. 余弦归一化计算相似度：W(i, j) = C[i][j] / sqrt( N(i) * N(j) )
        for (Map.Entry<Long, Map<Long, Double>> entry : coOccurrence.entrySet()) {
            Long itemA = entry.getKey();
            int countA = itemUsers.getOrDefault(itemA, Collections.emptySet()).size();
            if (countA == 0) continue;

            Map<Long, Double> neighbors = entry.getValue();
            Map<Long, Double> simMap = itemSimilarity.computeIfAbsent(itemA, k -> new HashMap<>());

            for (Map.Entry<Long, Double> neighbor : neighbors.entrySet()) {
                Long itemB = neighbor.getKey();
                int countB = itemUsers.getOrDefault(itemB, Collections.emptySet()).size();
                if (countB == 0) continue;

                double cosine = neighbor.getValue() / Math.sqrt((double) countA * countB);
                simMap.put(itemB, cosine);
            }
        }
    }

    /**
     * 查询与指定物品最相似的物品列表（I2I 单品相似）。
     */
    public List<ScoredItem> getSimilarItems(Long itemId, int limit) {
        Map<Long, Double> neighbors = itemSimilarity.get(itemId);
        if (neighbors == null || neighbors.isEmpty()) {
            return Collections.emptyList();
        }

        List<ScoredItem> list = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : neighbors.entrySet()) {
            list.add(new ScoredItem(entry.getKey(), entry.getValue()));
        }
        list.sort(Comparator.comparingDouble(ScoredItem::score).reversed());
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    /**
     * 根据当前用户的历史阅读偏好，推荐 Top-K 物品。
     *
     * <p>打分公式：P(u, j) = sum_{i in History(u)} r(u, i) * W(i, j)
     *
     * @param userHistory 用户已阅读的物品集合（及其权重，如最近看权重更高；为空时均权设为 1.0）
     * @param limit 期望返回的数量上限
     * @return 排序后的候选推荐列表（自动排除历史中已看过的物品）
     */
    public List<ScoredItem> recommend(Map<Long, Double> userHistory, int limit) {
        if (userHistory == null || userHistory.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> seen = userHistory.keySet();
        Map<Long, Double> candidateScores = new HashMap<>();

        for (Map.Entry<Long, Double> historyEntry : userHistory.entrySet()) {
            Long historyItemId = historyEntry.getKey();
            Double interestWeight = historyEntry.getValue();
            if (interestWeight == null || interestWeight <= 0) {
                interestWeight = 1.0;
            }

            Map<Long, Double> relatedItems = itemSimilarity.get(historyItemId);
            if (relatedItems == null) {
                continue;
            }

            for (Map.Entry<Long, Double> simEntry : relatedItems.entrySet()) {
                Long candidateId = simEntry.getKey();
                // 排除用户已经看过的物品
                if (seen.contains(candidateId)) {
                    continue;
                }
                double score = interestWeight * simEntry.getValue();
                candidateScores.merge(candidateId, score, Double::sum);
            }
        }

        if (candidateScores.isEmpty()) {
            return Collections.emptyList();
        }

        List<ScoredItem> results = new ArrayList<>(candidateScores.size());
        for (Map.Entry<Long, Double> entry : candidateScores.entrySet()) {
            results.add(new ScoredItem(entry.getKey(), entry.getValue()));
        }

        results.sort(Comparator.comparingDouble(ScoredItem::score).reversed());
        return results.size() > limit ? results.subList(0, limit) : results;
    }
}
