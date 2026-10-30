package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ItemCollaborativeFilteringTest {

    @Test
    @DisplayName("根据多用户共现行为准确计算物品相似度（余弦归一化）")
    void calculatesItemSimilarityCorrectly() {
        ItemCollaborativeFiltering model = new ItemCollaborativeFiltering();

        // 用户阅读行为矩阵：
        // user1 看过 [101, 102]
        // user2 看过 [101, 102, 103]
        // user3 看过 [101, 104]
        Map<String, Set<Long>> sessions = Map.of(
                "user1", Set.of(101L, 102L),
                "user2", Set.of(101L, 102L, 103L),
                "user3", Set.of(101L, 104L));

        model.train(sessions);

        // 101 和 102 共现了 2 次，101 和 103 仅共现 1 次
        List<ItemCollaborativeFiltering.ScoredItem> similarTo101 = model.getSimilarItems(101L, 10);
        assertThat(similarTo101).isNotEmpty();
        assertThat(similarTo101.get(0).itemId()).isEqualTo(102L);
        assertThat(similarTo101.get(0).score()).isGreaterThan(0.5);
    }

    @Test
    @DisplayName("用户已阅读过的物品被自动排除，只推荐未看过的相关物品")
    void recommendsUnseenItemsBasedOnHistory() {
        ItemCollaborativeFiltering model = new ItemCollaborativeFiltering();

        Map<String, Set<Long>> sessions = Map.of(
                "u1", Set.of(1L, 2L),
                "u2", Set.of(1L, 2L, 3L),
                "u3", Set.of(2L, 3L));

        model.train(sessions);

        // 当前用户看过 1L
        List<ItemCollaborativeFiltering.ScoredItem> recs = model.recommend(Map.of(1L, 1.0), 5);

        // 应该推荐 2L 和 3L，但绝不能包含 1L 本身
        assertThat(recs).extracting(ItemCollaborativeFiltering.ScoredItem::itemId)
                .doesNotContain(1L)
                .contains(2L);
    }

    @Test
    @DisplayName("空历史或单篇无共现时安全返回空列表")
    void handlesEmptyGracefully() {
        ItemCollaborativeFiltering model = new ItemCollaborativeFiltering();
        model.train(Map.of());

        assertThat(model.getSimilarItems(99L, 5)).isEmpty();
        assertThat(model.recommend(Map.of(), 5)).isEmpty();
    }
}
