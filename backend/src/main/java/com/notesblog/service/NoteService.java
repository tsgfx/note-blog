package com.notesblog.service;

import com.notesblog.common.NotFoundException;
import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.domain.Tag;
import com.notesblog.dto.BatchResultDto;
import com.notesblog.dto.CreateNoteRequest;
import com.notesblog.dto.FolderNodeDto;
import com.notesblog.dto.LibraryCountDto;
import com.notesblog.dto.NoteDetailDto;
import com.notesblog.dto.NoteSummaryDto;
import com.notesblog.dto.NoteTagCountDto;
import com.notesblog.dto.UpdateNoteRequest;
import com.notesblog.repository.NoteChunkRepository;
import com.notesblog.repository.NoteRepository;
import com.notesblog.repository.TagRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NoteService {

    private final NoteRepository noteRepository;
    private final TagRepository tagRepository;
    private final NoteChunkRepository chunkRepository;
    private final NoteIndexService noteIndexService;
    private final AuditService auditService;
    private final VectorIndex vectorIndex;

    /** 左侧"库"导航用的特殊名称，必须与前端 libraryOf 的折算规则一致。 */
    public static final String LIBRARY_UNFILED = "未归档";
    public static final String LIBRARY_ROOT = "根目录";

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
        Specification<Note> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
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
        return cb.or(titleMatch, contentMatch, cb.exists(tagged));
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 按库聚合篇数，顺序与前端一致：多的在前。 */
    public List<LibraryCountDto> libraries() {        Map<String, Long> counts = new LinkedHashMap<>();
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

    @Transactional
    public NoteDetailDto create(CreateNoteRequest request) {
        Note note = new Note();
        note.setTitle(request.title().trim());
        note.setSlug("note-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        note.setSummary(blankToNull(request.summary()));
        note.setContentMd(request.contentMd());
        note.setStatus(NoteStatus.DRAFT);
        note.setTags(resolveTags(request.tags()));
        Note saved = noteRepository.save(note);
        noteIndexService.reindex(saved.getId());
        auditService.record("NOTE_CREATE", saved.getSlug(), saved.getTitle());
        return NoteDetailDto.from(saved);
    }

    @Transactional
    public NoteDetailDto update(Long id, UpdateNoteRequest request) {
        Note note = noteRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("笔记不存在：" + id));
        // 公开页读的就是这一行，所以「编辑已发布文章」等于直接改线上。
        // 记一份快照，改动落地后比对，只有真的变了才标记「有未发布修改」，
        // 否则每打开一次编辑器保存一下都会亮起提示条，很快就没人看了。
        NoteSnapshot before = NoteSnapshot.of(note);
        if (request.title() != null) note.setTitle(request.title().trim());
        if (request.summary() != null) note.setSummary(blankToNull(request.summary()));
        if (request.contentMd() != null) {
            note.setContentMd(request.contentMd());
            // 内容改变后旧向量不能继续使用，等待异步重建。
            chunkRepository.deleteByNoteId(id);
            vectorIndex.invalidate();
        }
        if (request.tags() != null) note.setTags(resolveTags(request.tags()));
        if (note.getStatus() == NoteStatus.PUBLISHED && !before.equals(NoteSnapshot.of(note))) {
            note.setHasUnpublishedEdits(true);
        }
        Note saved = noteRepository.save(note);
        if (request.contentMd() != null) noteIndexService.reindex(saved.getId());
        auditService.record("NOTE_UPDATE", saved.getSlug(), saved.getTitle());
        return NoteDetailDto.from(saved);
    }

    @Transactional
    public void delete(Long id) {
        Note note = noteRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("笔记不存在：" + id));
        auditService.record("NOTE_DELETE", note.getSlug(), note.getTitle());
        // 切块是独立行，没有外键级联。不显式删就会留下查不到主人的向量块：
        // 检索可能命中已删笔记的片段，表也会一直变大。（tags 走 @ManyToMany
        // 的关联表，Hibernate 自己会清，不用管。）
        chunkRepository.deleteByNoteId(id);
        noteRepository.delete(note);
        vectorIndex.invalidate();
    }

    @Transactional
    public NoteDetailDto publish(Long id) {
        Note note = noteRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("笔记不存在：" + id));
        note.setStatus(NoteStatus.PUBLISHED);
        note.setPublishedAt(LocalDateTime.now());
        // 发布即把当前内容推上线，标记随之清空
        note.setHasUnpublishedEdits(false);
        Note saved = noteRepository.save(note);
        auditService.record("NOTE_PUBLISH", saved.getSlug(), saved.getTitle());
        return NoteDetailDto.from(saved);
    }

    @Transactional
    public NoteDetailDto unpublish(Long id) {
        Note note = noteRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("笔记不存在：" + id));
        note.setStatus(NoteStatus.DRAFT);
        note.setPublishedAt(null);
        // 下线后不再有「线上内容」可比，标记留着只会让人困惑
        note.setHasUnpublishedEdits(false);
        Note saved = noteRepository.save(note);
        auditService.record("NOTE_UNPUBLISH", saved.getSlug(), saved.getTitle());
        return NoteDetailDto.from(saved);
    }

    /** 批量操作的单次上限，与 {@code BatchIdsRequest} 的 @Size 保持一致。 */
    public static final int BATCH_LIMIT = 200;

    /**
     * 给「有正文但没有任何分片」的笔记补建索引，返回补了几篇。
     *
     * <p>什么时候会出现这种笔记：导入时嵌入服务不可用（硅基流动欠费、Ollama 没起来），
     * 分片写入失败但笔记本身保存成功了；或者刚从备份恢复（备份不含分片数据）。
     * 这类笔记在列表里看着正常，语义检索却永远搜不到，属于静默失效，
     * 所以给一个可以手动触发的一键补建。
     *
     * <p>索引是异步的（{@code NoteIndexService.reindex}），这里只负责排队，不等结果。
     *
     * <p>线程池的队列只有 20 个槽位。整库缺分片时一次性全排会被
     * {@code RejectedExecutionException} 打断并让接口 500——所以排到满就停，
     * 把「还剩多少」留给下一次调用，而不是让整个请求失败。
     */
    @Transactional
    public int reindexMissing() {
        // limit+1 只是为了让下面的循环有个上界；真正决定停不停的是队列是否已满
        List<Long> missing = noteRepository.findMissingChunkNoteIds(PageRequest.of(0, 200));
        int queued = 0;
        boolean queueFull = false;
        for (Long noteId : missing) {
            try {
                noteIndexService.reindex(noteId);
                queued++;
            } catch (RejectedExecutionException e) {
                queueFull = true;
                break;
            }
        }
        if (queued > 0) {
            auditService.record("NOTE_REINDEX", "all",
                    "补建缺失索引 " + queued + " 篇" + (queueFull ? "（队列已满，余下的需再次触发）" : ""));
        }
        return queued;
    }

    /** 全库仍缺分片的篇数，供调用方判断「还要不要再点一次」。 */
    public long countMissingChunks() {
        return noteRepository.countMissingChunks();
    }

    /** 批量发布。正文为空的笔记会被跳过并给出原因。 */
    @Transactional
    public BatchResultDto publishBatch(List<Long> ids) {
        return changeStatus(ids, NoteStatus.PUBLISHED);
    }

    /** 批量下线。已是草稿的会被跳过。 */
    @Transactional
    public BatchResultDto unpublishBatch(List<Long> ids) {
        return changeStatus(ids, NoteStatus.DRAFT);
    }

    private BatchResultDto changeStatus(List<Long> ids, NoteStatus target) {
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        // 一次批量取，避免在循环里逐条 findById（200 篇就是 200 次往返）
        Map<Long, Note> found = noteRepository.findAllById(distinct).stream()
                .collect(Collectors.toMap(Note::getId, note -> note));

        List<BatchResultDto.SkippedItem> skipped = new ArrayList<>();
        List<Note> changed = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (Long id : distinct) {
            Note note = found.get(id);
            if (note == null) {
                skipped.add(new BatchResultDto.SkippedItem(id, null, "笔记不存在"));
                continue;
            }
            if (note.getStatus() == target) {
                skipped.add(new BatchResultDto.SkippedItem(id, note.getTitle(),
                        target == NoteStatus.PUBLISHED ? "已经是发布状态" : "已经是草稿"));
                continue;
            }
            if (target == NoteStatus.PUBLISHED
                    && (note.getContentMd() == null || note.getContentMd().isBlank())) {
                skipped.add(new BatchResultDto.SkippedItem(id, note.getTitle(), "正文为空，不能发布"));
                continue;
            }
            note.setStatus(target);
            note.setPublishedAt(target == NoteStatus.PUBLISHED ? now : null);
            // 批量发布同样把标记清掉；下线后标记没有意义
            note.setHasUnpublishedEdits(false);
            changed.add(note);
        }

        if (!changed.isEmpty()) {
            noteRepository.saveAll(changed);
        }
        if (!changed.isEmpty() || !skipped.isEmpty()) {
            // 只记一条汇总：200 篇写 200 条会把审计表刷屏，翻页全变成发布记录
            auditService.record(
                    target == NoteStatus.PUBLISHED ? "NOTE_BATCH_PUBLISH" : "NOTE_BATCH_UNPUBLISH",
                    null,
                    "成功 " + changed.size() + " 篇，跳过 " + skipped.size() + " 篇");
        }
        return new BatchResultDto(distinct.size(), changed.size(), skipped.size(), List.copyOf(skipped));
    }

    private LinkedHashSet<Tag> resolveTags(List<String> names) {
        LinkedHashSet<Tag> tags = new LinkedHashSet<>();
        if (names == null) return tags;
        names.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> s.substring(0, Math.min(s.length(), 64)))
                .distinct()
                .forEach(name -> tags.add(tagRepository.findByName(name).orElseGet(() -> {
                    Tag tag = new Tag();
                    tag.setName(name);
                    return tagRepository.save(tag);
                })));
        return tags;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 判断「这次保存到底改了没」用的快照。
     *
     * <p>只比会出现在公开页上的四个字段；status、sourcePath 这些变了不算「内容改动」。
     * 用 record 拿到 equals 语义，省得手写四段 Objects.equals。
     */
    private record NoteSnapshot(String title, String summary, String contentMd, Set<String> tags) {
        static NoteSnapshot of(Note note) {
            return new NoteSnapshot(
                    note.getTitle(),
                    note.getSummary(),
                    note.getContentMd(),
                    note.getTags().stream().map(Tag::getName).collect(Collectors.toSet()));
        }
    }
}
