package com.notesblog.service;

import com.notesblog.common.TransactionSideEffects;
import com.notesblog.common.NotFoundException;
import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.domain.Tag;
import com.notesblog.dto.BatchResultDto;
import com.notesblog.dto.CreateNoteRequest;
import com.notesblog.dto.NoteDetailDto;
import com.notesblog.dto.UpdateNoteRequest;
import com.notesblog.repository.NoteChunkRepository;
import com.notesblog.repository.NoteRepository;
import com.notesblog.repository.TagRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 笔记写生命周期：创建、编辑、删除、发布/下线、批量状态变更与索引副作用协调。
 * 列表筛选和目录导航读模型在 {@link NoteCatalogService}。
 */
@Slf4j
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
        reindexAfterCommit(saved.getId());
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
            invalidateIndexAfterCommit();
        }
        if (request.tags() != null) note.setTags(resolveTags(request.tags()));
        if (note.getStatus() == NoteStatus.PUBLISHED && !before.equals(NoteSnapshot.of(note))) {
            note.setHasUnpublishedEdits(true);
        }
        Note saved = noteRepository.save(note);
        if (request.contentMd() != null) reindexAfterCommit(saved.getId());
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
        invalidateIndexAfterCommit();
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
        // 分片缓存里固化了 published 标志（VectorIndex.Chunk），不发这次失效，
        // 刚发布的文章对公开检索和游客问答就永远不可见，直到进程重启。
        invalidateIndexAfterCommit();
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
        // 下线同样要失效：缓存里那批分片还带着 published=true，不清掉等于
        // 草稿内容继续出现在公开检索结果里——比起搜不到，这条更严重。
        invalidateIndexAfterCommit();
        auditService.record("NOTE_UNPUBLISH", saved.getSlug(), saved.getTitle());
        return NoteDetailDto.from(saved);
    }

    /**
     * 事务提交之后再排索引重建。
     *
     * <p><b>为什么不在事务里直接排：</b>{@code noteIndexService.reindex} 是 {@code @Async}，
     * 一调用就进线程池，而提交可能还没发生——重建线程按 id 查库会查不到这一行（新建）或
     * 读到旧正文（更新），只能靠下游反复轮询重试硬扛，运气不好就放弃。
     * 提交后再排，重建线程一开始读到的就是已落库的内容。
     *
     * <p>队列满（{@code taskExecutor} 只有 20 个槽位）时只记一条告警：正文已经保存成功，
     * 不该因为索引排队失败把整个请求变成 500，缺失的分片可以事后一键补建。
     */
    private void reindexAfterCommit(Long noteId) {
        TransactionSideEffects.afterCommitOrNow(() -> queueReindex(noteId));
    }

    private void queueReindex(Long noteId) {
        try {
            noteIndexService.reindex(noteId);
        } catch (RejectedExecutionException e) {
            log.warn("note_reindex_queue_full noteId={}（分片缺失可事后补建）", noteId);
        }
    }

    /**
     * 事务提交之后再抛弃向量快照。
     *
     * <p><b>为什么不能在事务里直接失效：</b>{@code VectorIndex} 是「失效后下次读重建」的懒加载缓存。
     * 在事务提交前失效，紧接着到达的并发检索会拿**未提交**的旧数据重建快照并缓存下来——
     * 于是刚发布的文章照样搜不到，刚下线的草稿照样出现在结果里，直到下一次有人改笔记。
     * 提交后再失效，重建时读到的就是已落库的新状态。
     *
     * <p>没有事务（例如异步重建线程里调用）时直接失效。
     */
    private void invalidateIndexAfterCommit() {
        TransactionSideEffects.afterCommitOrNow(vectorIndex::invalidate);
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
            // 同上：published 标志固化在分片缓存里，批量发布/下线后必须重建快照
            vectorIndex.invalidate();
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
