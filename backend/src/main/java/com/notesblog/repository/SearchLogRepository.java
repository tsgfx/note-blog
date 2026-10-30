package com.notesblog.repository;

import com.notesblog.domain.SearchLog;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SearchLogRepository extends JpaRepository<SearchLog, Long> {

    long countByCreatedAtAfter(LocalDateTime since);

    long countByHitCountAndCreatedAtAfter(int hitCount, LocalDateTime since);

    /**
     * 零结果词聚合统计：未满足的搜索词（按频次降序）。
     */
    @Query("""
            select s.query, count(s), max(s.createdAt)
            from SearchLog s
            where s.hitCount = 0 and s.createdAt >= :since
            group by s.query
            order by count(s) desc, max(s.createdAt) desc
            """)
    List<Object[]> findTopZeroHitQueries(@Param("since") LocalDateTime since, Pageable pageable);

    /**
     * 热门检索词聚合统计：搜索频次与平均命中条数。
     */
    @Query("""
            select s.query, count(s), avg(s.hitCount)
            from SearchLog s
            where s.createdAt >= :since
            group by s.query
            order by count(s) desc
            """)
    List<Object[]> findTopQueries(@Param("since") LocalDateTime since, Pageable pageable);
}
