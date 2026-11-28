package com.notesblog.service;

import com.notesblog.common.NotFoundException;
import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.domain.Tag;
import com.notesblog.dto.NoteDetailDto;
import com.notesblog.repository.NoteRepository;
import com.notesblog.repository.TagRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 写完笔记后自动生成摘要与标签。
 *
 * <p>策略：默认只填空白字段（不覆盖用户手写的内容）；前端「重新生成」按钮
 * 传 force=true 时两者都重写。LLM 输出强制要求 JSON，解析失败视为临时故障。
 */
@Slf4j
@Service
public class NoteMetaService {

    /** 只把正文开头这么多字符交给模型：摘要和标签不需要全文，也省额度。 */
    private static final int MAX_CONTENT_CHARS = 4000;

    private static final String SYSTEM_PROMPT = """
            你是个人笔记库的整理助手。阅读给定的笔记标题和正文，生成一句话摘要和 3-6 个主题标签。
            只输出一个 JSON 对象，格式：{"summary":"…","tags":["…","…"]}，不要输出 JSON 以外的任何内容。
            摘要不超过 60 字，概括笔记核心内容；标签用简短词（专有名词可保留英文），不要照抄标题。""";

    private final NoteRepository noteRepository;
    private final TagRepository tagRepository;
    private final LlmStreamingClient llmClient;
    private final NoteMetaParser metaParser;
    private final AuditService auditService;

    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public NoteMetaService(NoteRepository noteRepository,
                           TagRepository tagRepository,
                           LlmStreamingClient llmClient,
                           NoteMetaParser metaParser,
                           AuditService auditService,
                           PlatformTransactionManager transactionManager) {
        this.noteRepository = noteRepository;
        this.tagRepository = tagRepository;
        this.llmClient = llmClient;
        this.metaParser = metaParser;
        this.auditService = auditService;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        // 模型侧一次可能要几十秒；绝不能让这段时间占着数据库连接和行锁——
        // 池子只有 10 条，几篇同时点「重新生成」就能把整个站点的读写饿死。
        // 所以拆成「读快照 → 事务外调模型 → 短事务写回」三段。
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    public NoteDetailDto generate(Long id, boolean force) {
        // 1. 只读事务：判断缺什么，并把要喂给模型的正文取出来
        MetaInput input = readTx.execute(status -> {
            Note note = noteRepository.findById(id)
                    .orElseThrow(() -> new NotFoundException("笔记不存在：" + id));
            boolean needSummary = force || isBlank(note.getSummary());
            boolean needTags = force || note.getTags().isEmpty();
            return new MetaInput(note.getId(), note.getTitle(), note.getContentMd(),
                    needSummary, needTags);
        });
        if (input == null) {
            throw new NotFoundException("笔记不存在：" + id);
        }
        if (!input.needSummary() && !input.needTags()) {
            // 都有内容且不强制：不花额度，直接原样返回
            return readTx.execute(status -> NoteDetailDto.from(
                    noteRepository.findById(id)
                            .orElseThrow(() -> new NotFoundException("笔记不存在：" + id))));
        }

        // 2. 事务外调模型（可能几十秒）
        String raw = collectLlm(input.title(), input.contentMd());
        NoteMetaParser.Result meta = metaParser.parse(raw);

        // 3. 短事务写回：重新读一次，用户这期间改过正文也不会被我们的旧实体覆盖
        NoteDetailDto result = writeTx.execute(status -> {
            Note note = noteRepository.findById(input.id())
                    .orElseThrow(() -> new NotFoundException("笔记不存在：" + input.id()));
            boolean changed = false;
            if (input.needSummary() && meta.summary() != null && !meta.summary().isBlank()) {
                note.setSummary(meta.summary());
                changed = true;
            }
            if (input.needTags() && !meta.tags().isEmpty()) {
                note.setTags(resolveTags(meta.tags()));
                changed = true;
            }
            // 已发布的文章补摘要/标签同样会立刻改变线上展示（博客列表就显示摘要），
            // 与「改了正文」是同一件事，所以一并打上「待重发」标记。
            if (changed && note.getStatus() == NoteStatus.PUBLISHED) {
                note.setHasUnpublishedEdits(true);
            }
            Note saved = noteRepository.save(note);
            auditService.record(force ? "NOTE_META_REGEN" : "NOTE_META_AUTO", saved.getSlug(), saved.getTitle());
            return NoteDetailDto.from(saved);
        });
        if (result == null) {
            throw new IllegalStateException("摘要写入未返回结果");
        }
        return result;
    }

    /** 生成摘要/标签时要用到的输入：只带纯字段，不把实体带出事务。 */
    private record MetaInput(Long id, String title, String contentMd,
                             boolean needSummary, boolean needTags) {
    }

    private String collectLlm(String title, String contentMd) {
        StringBuilder out = new StringBuilder();
        String user = "笔记标题：" + (title == null ? "（无标题）" : title)
                + "\n\n笔记正文：\n" + truncate(contentMd, MAX_CONTENT_CHARS);
        llmClient.stream(SYSTEM_PROMPT, user, out::append);
        return out.toString();
    }

    private Set<Tag> resolveTags(java.util.List<String> names) {
        LinkedHashSet<Tag> tags = new LinkedHashSet<>();
        for (String name : names) {
            tags.add(tagRepository.findByName(name).orElseGet(() -> {
                Tag tag = new Tag();
                tag.setName(name);
                return tagRepository.save(tag);
            }));
        }
        return tags;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }
}
