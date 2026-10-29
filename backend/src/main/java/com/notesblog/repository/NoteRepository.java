package com.notesblog.repository;

import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoteRepository extends JpaRepository<Note, Long>, JpaSpecificationExecutor<Note> {

    Optional<Note> findBySlug(String slug);

    Page<Note> findByStatus(NoteStatus status, Pageable pageable);

    boolean existsBySlug(String slug);

    /** 单行原子递增阅读量，避免长锁或整行实体写覆盖 */
    @Modifying
    @Query("update Note n set n.viewCount = n.viewCount + 1 where n.id = :id")
    void incrementViewCount(@Param("id") Long id);

    /**
     * 已发布文章中的「上一篇」（时间更新或 id 更大，按时间升序取第 1 个）。
     */
    @Query("""
            select n.title, n.slug from Note n
            where n.status = 'PUBLISHED'
              and (n.publishedAt > :publishedAt or (n.publishedAt = :publishedAt and n.id > :id))
            order by n.publishedAt asc, n.id asc
            """)
    List<Object[]> findPrevPublishedPost(
            @Param("publishedAt") LocalDateTime publishedAt,
            @Param("id") Long id,
            Pageable pageable);

    /**
     * 已发布文章中的「下一篇」（时间更早或 id 更小，按时间降序取第 1 个）。
     */
    @Query("""
            select n.title, n.slug from Note n
            where n.status = 'PUBLISHED'
              and (n.publishedAt < :publishedAt or (n.publishedAt = :publishedAt and n.id < :id))
            order by n.publishedAt desc, n.id desc
            """)
    List<Object[]> findNextPublishedPost(
            @Param("publishedAt") LocalDateTime publishedAt,
            @Param("id") Long id,
            Pageable pageable);

    /**
     * 按 sourcePath 分组计数，供前端左侧"库"导航用。
     *
     * <p>只按完整路径分组、把"取第一段"的折算放到 Java 里做：
     * 那个折算规则要和前端一致，写成 JPQL 会很难读也很难测。
     */
    @Query("select n.sourcePath, count(n) from Note n group by n.sourcePath")
    List<Object[]> countBySourcePath();

    /**
     * 按 (sourcePath, status) 分组计数，供目录树使用。
     *
     * <p>把状态也放进分组：左侧树要跟着「全部 / 已发布 / 草稿」变化，
     * 否则切到草稿后树上的数字还是全部篇数，点进去对不上。
     * 行数是「路径数 × 状态数」，几百篇的规模完全够用；
     * 比在 SQL 里写条件聚合（SUM(CASE WHEN ...)）更容易读，也更好测。
     */
    @Query("select n.sourcePath, n.status, count(n) from Note n group by n.sourcePath, n.status")
    List<Object[]> countBySourcePathAndStatus();

    /** 首页统计：某个状态下的篇数 */
    long countByStatus(NoteStatus status);

    /**
     * 已发布文章用到的标签数（去重）。
     *
     * <p>用 {@code join n.tags t} 而不是在 Java 里合并：
     * 标签是多对多，取回整批 Note 再算会把整库正文拉进内存。
     */
    @Query("select count(distinct t.id) from Note n join n.tags t where n.status = :status")
    long countDistinctTagsByStatus(@Param("status") NoteStatus status);

    @Query("select max(n.publishedAt) from Note n where n.status = :status")
    LocalDateTime findLatestPublishedAt(@Param("status") NoteStatus status);

    /**
     * 已发布笔记的正文（供资产可见性计算）。
     *
     * <p>只为扫描图片引用，不需要实体映射；几百篇 × 平均几 KB，一次全取没有压力。
     */
    @Query("select n.contentMd from Note n where n.status = :status")
    List<String> findContentMdByStatus(@Param("status") NoteStatus status);

    /**
     * 取出「标签 → 笔记路径」的全部对应关系，供左栏「按分类浏览」统计用。
     *
     * <p>只回两列（标签名 + sourcePath），1575 行左右，很小。
     * **不在 SQL 里按库分组**：库名是「sourcePath 第一段」，那条折算规则在
     * {@link com.notesblog.service.NoteService#libraryOf} 里，SQL 里再写一遍必然跑偏。
     *
     * @param status 限定状态，null 为不限
     */
    @Query("select t.name, n.sourcePath from Note n join n.tags t where (:status is null or n.status = :status)")
    List<Object[]> findTagPaths(@Param("status") NoteStatus status);

    /**
     * 「摘要或标签缺失」的笔记 id，供批量补元数据分页取用。
     *
     * <p>只回 id 不回实体：实体里的 {@code tags} 是懒加载集合，在事务外碰它会
     * 抛 LazyInitializationException，而批量补元数据刻意**不**开长事务
     * （每篇要等模型两三秒，一个事务里串 10 次网络调用会长时间持有行锁）。
     *
     * <p>正文为空的笔记直接排除：模型对着空正文只能编，白烧额度。
     */
    @Query("""
            select n.id from Note n
            where n.contentMd is not null and n.contentMd <> ''
              and ((n.summary is null or n.summary = '') or n.tags is empty)
            order by n.id asc
            """)
    List<Long> findMissingMetaIds(Pageable pageable);

    /**
     * 批量取「这篇还缺什么」的状态，一行一篇。
     *
     * <p>{@code size(n.tags)} 会翻成子查询，不触发懒加载；返回列依次是
     * id、标题、摘要是否空白、标签个数。用它来判断"要不要花额度"，
     * 判断与写库分处两个事务，避免长事务。
     */
    @Query("""
            select n.id, n.title,
                   case when (n.summary is null or n.summary = '') then true else false end,
                   size(n.tags)
            from Note n where n.id in :ids
            """)
    List<Object[]> findMetaState(@Param("ids") List<Long> ids);

    /**
     * 全库仍缺摘要或标签的篇数。
     *
     * <p>批量补元数据时每轮回一个数，前端据此判断「是不是真的在收敛」——
     * 模型偶尔返回没摘要的 JSON，那篇会一直缺、一直被选中，没有这个数就会无限重试烧额度。
     */
    @Query("""
            select count(n) from Note n
            where n.contentMd is not null and n.contentMd <> ''
              and ((n.summary is null or n.summary = '') or n.tags is empty)
            """)
    long countMissingMeta();

    /**
     * 「有正文但一个分片都没有」的笔记 id，供补建索引用。
     *
     * <p>一次查出而不是遍历全部笔记逐条 {@code existsByNoteId}：整库缺分片时（从备份恢复后
     * 就是这种状态）那是 680 次往返，光判断就要十几秒，而线程池队列只有 20 个槽位——
     * 结果每轮只能排几篇，补完 680 篇要半小时。
     */
    @Query("""
            select n.id from Note n
            where n.contentMd is not null and n.contentMd <> ''
              and not exists (select c.id from NoteChunk c where c.noteId = n.id)
            order by n.id asc
            """)
    List<Long> findMissingChunkNoteIds(Pageable pageable);

    /** 全库仍缺分片的篇数，供补建索引用它判断「还要不要再点一次」。 */
    @Query("""
            select count(n) from Note n
            where n.contentMd is not null and n.contentMd <> ''
              and not exists (select c.id from NoteChunk c where c.noteId = n.id)
            """)
    long countMissingChunks();
}
