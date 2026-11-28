package com.notesblog.service;

import com.notesblog.common.NotFoundException;
import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.domain.Tag;
import com.notesblog.dto.FolderNodeDto;
import com.notesblog.dto.LibraryCountDto;
import com.notesblog.dto.NoteDetailDto;
import com.notesblog.dto.NoteSummaryDto;
import com.notesblog.dto.NoteTagCountDto;
import com.notesblog.repository.NoteRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 笔记目录读模型：管理列表筛选、目录/库/标签导航与按 slug 读取。
 *
 * <p>这个模块只拥有读侧复杂性：筛选规格、路径前缀语义、库名折算和聚合。
 * 写生命周期、发布状态与索引副作用留在 {@link NoteService}，避免把事务边界拆散。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NoteCatalogService {

    private final NoteRepository noteRepository;

    /** 左侧"库"导航用的特殊名称，必须与前端 libraryOf 的折算规则一致。 */
    public static final String LIBRARY_UNFILED = "未归档";
    public static final String LIBRARY_ROOT = "根目录";

    /** 管理端单页上限，防止一次拉走整库；公开列表的上限由 PublicBlogService 单独拥有。 */
    private static final int ADMIN_MAX_PAGE_SIZE = 100;

    /**
     * 管理端列表入口：拥有管理端自己的分页边界与排序语义。
     *
     * <p>控制器只传原始查询参数；{@code page=-1}、{@code size=0} 这类输入不能在这里抛成 500。
     */
    public Page<NoteSummaryDto> adminList(
            NoteStatus status,
            String path,
            String keyword,
            List<String> tags,
            boolean staleOnly,
            String sort,
            int page,
            int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), ADMIN_MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize, adminSort(sort));
        return list(status, path, keyword, tags, staleOnly, pageable);
    }

    /**
     * 管理列表排序。所有排序都补 id 兜底，避免相同时间戳下分页顺序抖动。
     */
    private static Sort adminSort(String sort) {
        return switch (sort == null ? "" : sort) {
            case "views" -> Sort.by(Sort.Order.desc("viewCount"), Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));
            case "published" -> Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("id"));
            default -> Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));
        };
    }

    /**
     * 分页列出笔记，支持按状态、库、关键字筛选。
     *
     * <p>筛选必须做在服务端：列表已分页，前端手里只有当前页，
     * 再在本地过滤只能筛出"当前页里的结果"。
     *
     * @param status  为空表示全部（含草稿）
     * @param path    目录路径（sourcePath 去掉文件名后的前缀），空表示不筛；
     *                传第一段就是按库筛，传多段就是按子目录筛，两者同一个参数
     * @param keyword 匹配标题、正文或标签名，空表示不筛
     */
    public Page<NoteSummaryDto> list(
            NoteStatus status, String path, String keyword, List<String> tags, Pageable pageable) {
        return list(status, path, keyword, tags, false, pageable);
    }

    /**
     * 同上，另加「只看待重发」筛选。
     *
     * <p>待重发 = 已发布、但发布后又改过（{@code hasUnpublishedEdits}）。列表页要给这个状态
     * 单独一个入口：否则这些笔记混在 PUBLISHED 里，行按钮只能显示「下线」，
     * 读者看到的其实还是旧内容，管理员很难发现。
     */
    public Page<NoteSummaryDto> list(
            NoteStatus status,
            String path,
            String keyword,
            List<String> tags,
            boolean staleOnly,
            Pageable pageable) {
        Specification<Note> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (staleOnly) {
                predicates.add(cb.equal(root.get("status"), NoteStatus.PUBLISHED));
                predicates.add(cb.isTrue(root.get("hasUnpublishedEdits")));
            } else if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (path != null && !path.isBlank()) {
                predicates.add(pathPredicate(root, cb, path.trim()));
            }
            if (keyword != null && !keyword.isBlank()) {
                predicates.add(keywordPredicate(root, query, cb, keyword.trim().toLowerCase()));
            }
            if (tags != null) {
                for (String tag : tags) {
                    if (tag == null || tag.isBlank()) continue;
                    // 每个标签一个子查询，语义是 AND：同时选「链表」和「力扣」
                    // = 只要同时带这两个标签的笔记。用 join 会因多对多产生重复行，
                    // 再叠加分页就会算错总数，子查询没有这个问题。
                    Subquery<Long> subquery = query.subquery(Long.class);
                    Root<Note> tagged = subquery.from(Note.class);
                    Join<Note, Tag> join = tagged.join("tags");
                    subquery.select(tagged.get("id")).where(cb.equal(join.get("name"), tag.trim()));
                    predicates.add(root.get("id").in(subquery));
                }
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
        // 实体已经整行取出（含正文），命中片段就在内存里算，不用再查一次库
        String snippetKeyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        return noteRepository.findAll(spec, pageable)
                .map(note -> NoteSummaryDto.from(note, snippetKeyword));
    }

    /**
     * 标签聚合，供左栏「按分类浏览」。
     *
     * <p>按目录过滤放在 Java 里做：目录折算规则和左栏导航、列表筛选共用
     * {@link #pathMatches}。在 SQL 里重写一遍，迟早和它对不上。
     *
     * @param path 目录路径，为 null / 空表示不按目录过滤；传第一段即「按库过滤」
     */
    @Transactional(readOnly = true)
    public List<NoteTagCountDto> tagFacets(NoteStatus status, String path) {
        boolean filterByPath = path != null && !path.isBlank();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : noteRepository.findTagPaths(status)) {
            String tagName = (String) row[0];
            String sourcePath = (String) row[1];
            if (tagName == null || tagName.isBlank()) continue;
            if (filterByPath && !pathMatches(sourcePath, path.trim())) continue;
            counts.merge(tagName, 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new NoteTagCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 多级目录树，供左栏导航。
     *
     * <p>按 (sourcePath, status) 分组取数，再在 Java 里聚合成树：
     * 状态为 null 表示「全部」。这样切到草稿时树上的数字跟着变，
     * 点进去的篇数和数字能对上——树上写着 12 篇、点进去只有 3 篇，是最容易让人失去信任的 bug。
     */
    @Transactional(readOnly = true)
    public List<FolderNodeDto> folders(NoteStatus status) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : noteRepository.countBySourcePathAndStatus()) {
            NoteStatus rowStatus = (NoteStatus) row[1];
            if (status != null && rowStatus != status) continue;
            String sourcePath = (String) row[0];
            long count = ((Number) row[2]).longValue();
            counts.merge(sourcePath, count, Long::sum);
        }
        return FolderTree.build(counts);
    }

    private Predicate pathPredicate(Root<Note> root, CriteriaBuilder cb, String path) {
        Expression<String> sourcePath = root.get("sourcePath");
        if (LIBRARY_UNFILED.equals(path)) {
            return cb.or(cb.isNull(sourcePath), cb.equal(sourcePath, ""));
        }
        if (LIBRARY_ROOT.equals(path)) {
            // 没有斜杠 = 直接放在根目录
            return cb.and(cb.isNotNull(sourcePath), cb.notLike(sourcePath, "%/%"));
        }
        // 前缀匹配包含整棵子树：选 c++/项目A 时，它下面更深目录的笔记也要出得来。
        // 路径里的 % _ \ 要转义，否则 "c%+" 这种目录名会当成通配符
        return cb.like(sourcePath, escapeLike(path) + "/%", '\\');
    }

    /**
     * 目录前缀匹配的 Java 版本，必须与 {@link #pathPredicate} 等价。
     *
     * <p>两处各写一遍是因为一个跑在 SQL、一个跑在内存（标签聚合）；
     * 规则只有一份心智模型，改一边必须改另一边，{@code NoteLibraryTest} 盯着这两个分支。
     */
    static boolean pathMatches(String sourcePath, String path) {
        if (path == null || path.isBlank()) return true;
        String scope = path.trim();
        if (LIBRARY_UNFILED.equals(scope)) {
            return sourcePath == null || sourcePath.isBlank();
        }
        if (sourcePath == null || sourcePath.isBlank()) return false;
        if (LIBRARY_ROOT.equals(scope)) {
            return sourcePath.indexOf('/') < 0;
        }
        return sourcePath.startsWith(scope + "/");
    }

    private Predicate keywordPredicate(Root<Note> root, CriteriaQuery<?> query, CriteriaBuilder cb, String keyword) {
        String like = "%" + escapeLike(keyword) + "%";
        Predicate titleMatch = cb.like(cb.lower(root.get("title")), like, '\\');
        Predicate summaryMatch = cb.like(cb.lower(root.get("summary")), like, '\\');
        // 正文也参与匹配。LIKE '%kw%' 是全表扫描，但 680 篇 × 平均几 KB 的体量
        // 毫秒级就返回；等这个数字涨三个数量级再考虑 ngram 全文索引。
        Predicate contentMatch = cb.like(cb.lower(root.get("contentMd")), like, '\\');

        // 用 exists 子查询而不是 join：join 会让同一篇笔记按标签数重复出现，
        // 分页条数就错了；靠 distinct 补救又会拖慢 count 查询
        Subquery<Long> tagged = query.subquery(Long.class);
        Root<Note> subRoot = tagged.from(Note.class);
        Join<Note, Tag> tagJoin = subRoot.join("tags");
        tagged.select(cb.literal(1L))
                .where(cb.equal(subRoot.get("id"), root.get("id")),
                        cb.like(cb.lower(tagJoin.get("name")), like, '\\'));
        return cb.or(titleMatch, summaryMatch, contentMatch, cb.exists(tagged));
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 按库聚合篇数，顺序与前端一致：多的在前。 */
    public List<LibraryCountDto> libraries() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : noteRepository.countBySourcePath()) {
            String sourcePath = (String) row[0];
            long count = ((Number) row[1]).longValue();
            counts.merge(libraryOf(sourcePath), count, Long::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new LibraryCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    /** 取 sourcePath 的第一段作为库名；null → 未归档，无斜杠 → 根目录。 */
    static String libraryOf(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return LIBRARY_UNFILED;
        }
        int slash = sourcePath.indexOf('/');
        return slash <= 0 ? LIBRARY_ROOT : sourcePath.substring(0, slash);
    }

    public NoteDetailDto getBySlug(String slug) {
        Note note = noteRepository.findBySlug(slug)
                .orElseThrow(() -> new NotFoundException("笔记不存在：" + slug));
        return NoteDetailDto.from(note);
    }

}
