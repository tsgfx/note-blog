package com.notesblog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notesblog.common.NotFoundException;
import com.notesblog.domain.Note;
import com.notesblog.repository.NoteRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** 保存笔记后异步重建该笔记的分片和向量。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteIndexService {
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s+(.+?)\\s*$");
    private static final int MAX_CHUNK_CHARS = 1800;
    private static final int MAX_STORE_ATTEMPTS = 3;

    private final NoteRepository noteRepository;
    private final EmbeddingClient embeddingClient;
    private final NoteChunkStore chunkStore;
    private final VectorIndex vectorIndex;
    private final ObjectMapper objectMapper;

    @Async
    public void reindex(Long noteId) {
        try {
            // 调用方都在事务提交后才排队（NoteService#reindexAfterCommit、InboxService 同理），
            // 所以这里直接查即可；以前要轮询 5 次（200ms×n，最长约 3s）纯属为了躲「提交前入池」。
            Note note = noteRepository.findById(noteId)
                    .orElseThrow(() -> new NotFoundException("笔记不存在：" + noteId));
            List<ChunkText> texts = split(note.getContentMd());
            List<NoteChunkStore.ChunkRow> rows = new ArrayList<>();
            for (ChunkText text : texts) {
                String vector = objectMapper.writeValueAsString(embeddingClient.embed(text.content()));
                rows.add(new NoteChunkStore.ChunkRow(text.heading(), text.content(), vector));
            }
            storeWithRetry(noteId, rows);
            vectorIndex.invalidate();
            log.info("note_reindexed noteId={} chunks={}", noteId, rows.size());
        } catch (Exception e) {
            log.error("note_reindex_failed noteId={}", noteId, e);
        }
    }

    /**
     * 补建索引时 4 个线程同时 DELETE+INSERT 同一张表，InnoDB 会挑一个牺牲掉（Deadlock found
     * when trying to get lock）。这里只重试写库这一步——向量已经算好了，重试不该再花钱调一次
     * embedding。三次仍失败就抛出去，让日志里留下 note_reindex_failed。
     */
    private void storeWithRetry(Long noteId, List<NoteChunkStore.ChunkRow> rows) {
        for (int attempt = 1; ; attempt++) {
            try {
                chunkStore.replace(noteId, rows);
                return;
            } catch (CannotAcquireLockException e) {
                if (attempt >= MAX_STORE_ATTEMPTS) {
                    throw e;
                }
                log.warn("note_chunk_deadlock noteId={} attempt={}，退避后重试", noteId, attempt);
                try {
                    Thread.sleep(50L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private List<ChunkText> split(String markdown) {
        List<ChunkText> result = new ArrayList<>();
        Matcher matcher = HEADING.matcher(markdown == null ? "" : markdown);
        int cursor = 0;
        String heading = null;
        while (matcher.find()) {
            if (matcher.start() > cursor) addSplit(result, heading, markdown.substring(cursor, matcher.start()));
            heading = matcher.group(1).trim();
            cursor = matcher.end();
        }
        if (cursor < (markdown == null ? 0 : markdown.length())) addSplit(result, heading, markdown.substring(cursor));
        if (result.isEmpty() && markdown != null && !markdown.isBlank()) addSplit(result, null, markdown);
        return result;
    }

    private void addSplit(List<ChunkText> result, String heading, String text) {
        String clean = text.trim();
        if (clean.isBlank()) return;
        for (int start = 0; start < clean.length(); start += MAX_CHUNK_CHARS) {
            result.add(new ChunkText(heading, clean.substring(start, Math.min(start + MAX_CHUNK_CHARS, clean.length()))));
        }
    }

    private record ChunkText(String heading, String content) {
    }
}
