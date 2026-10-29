package com.notesblog.service;

import com.notesblog.dto.GenerateMetaBatchRequest;
import com.notesblog.dto.MetaBatchReportDto;
import com.notesblog.repository.NoteRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 批量补摘要/标签。
 *
 * <p>为什么要单开一个服务：{@link NoteMetaService#generate} 带着
 * {@code @Transactional}，而同类内部方法互调不走 Spring 代理，事务会静默失效。
 * 把循环放在另一个 bean 里，每一次 {@code generate} 都是独立事务——
 * 一篇要等模型两三秒，十篇串在一个事务里就是把行锁持有一分钟。
 *
 * <p>另一个好处是失败隔离：某篇模型返回了非 JSON，只影响它自己，其余继续。
 *
 * <p>不引入任务队列：单机单人、单批最多 10 篇，前端循环调用足够；
 * 为此加一张任务表和轮询接口是净负担。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteMetaBatchService {

    /** 失败明细最多回几条：只用于提示，多了没意义。 */
    private static final int MAX_FAILURES = 5;

    private final NoteRepository noteRepository;
    private final NoteMetaService noteMetaService;
    private final AuditService auditService;

    public MetaBatchReportDto generateBatch(GenerateMetaBatchRequest request) {
        boolean force = request.forceOrDefault();
        int limit = request.limitOrDefault();

        // ── 挑出这一轮要处理的 id ────────────────────────
        // 多取一条用来判断 hasMore，省掉一次 count 查询
        List<Long> candidates;
        boolean hasMore;
        List<Long> requestedIds = request.ids();
        if (requestedIds == null || requestedIds.isEmpty()) {
            List<Long> missing = noteRepository.findMissingMetaIds(PageRequest.of(0, limit + 1));
            hasMore = missing.size() > limit;
            candidates = missing.subList(0, Math.min(missing.size(), limit));
        } else {
            List<Long> distinct = requestedIds.stream().filter(Objects::nonNull).distinct().toList();
            hasMore = distinct.size() > limit;
            candidates = distinct.subList(0, Math.min(distinct.size(), limit));
        }

        if (candidates.isEmpty()) {
            return new MetaBatchReportDto(0, 0, 0, 0, false, noteRepository.countMissingMeta(), List.of());
        }

        // ── 一次问清每篇缺什么 ───────────────────────────
        // 判断和写库因此分处两个事务；findMetaState 用 size(tags) 取标签数，
        // 不会把懒加载集合拉出来（否则事务外访问直接抛 LazyInitializationException）
        Map<Long, Object[]> stateById = new HashMap<>();
        for (Object[] row : noteRepository.findMetaState(candidates)) {
            stateById.put(((Number) row[0]).longValue(), row);
        }

        int generated = 0;
        int skipped = 0;
        int failed = 0;
        List<MetaBatchReportDto.Failure> failures = new ArrayList<>();

        for (Long id : candidates) {
            Object[] state = stateById.get(id);
            if (state == null) {
                // 刚被删掉：不算失败，也没有可处理的东西
                skipped++;
                continue;
            }
            String title = (String) state[1];
            boolean missingSummary = Boolean.TRUE.equals(state[2]);
            int tagCount = ((Number) state[3]).intValue();

            if (!force && !missingSummary && tagCount > 0) {
                // 摘要和标签都有，且没要求重写：不花额度
                skipped++;
                continue;
            }
            try {
                noteMetaService.generate(id, force);
                generated++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("meta_batch_failed id={} title={} error={}", id, title, e.toString());
                if (failures.size() < MAX_FAILURES) {
                    failures.add(new MetaBatchReportDto.Failure(id, title, rootMessage(e)));
                }
            }
        }

        if (generated > 0) {
            auditService.record("NOTE_META_BATCH", "all", "批量补元数据 " + generated + " 篇"
                    + (failed > 0 ? "，失败 " + failed + " 篇" : ""));
        }

        long remaining = noteRepository.countMissingMeta();
        return new MetaBatchReportDto(candidates.size(), generated, skipped, failed, hasMore, remaining, failures);
    }

    /** 取最内层原因：LLM 的异常常被包两三层，最外层只会说「调用失败」。 */
    static String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
