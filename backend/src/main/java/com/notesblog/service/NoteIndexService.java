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
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** 保存笔记后异步重建该笔记的分片和向量。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteIndexService {
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s+(.+?)\\s*$");
    private static final int MAX_CHUNK_CHARS = 1800;

    private final NoteRepository noteRepository;
    private final EmbeddingClient embeddingClient;
    private final NoteChunkStore chunkStore;
    private final VectorIndex vectorIndex;
    private final ObjectMapper objectMapper;

    @Async
    public void reindex(Long noteId) {
        try {
            Note note = loadAfterCommit(noteId)
                    .orElseThrow(() -> new NotFoundException("笔记不存在：" + noteId));
            List<ChunkText> texts = split(note.getContentMd());
            List<NoteChunkStore.ChunkRow> rows = new ArrayList<>();
            for (ChunkText text : texts) {
                String vector = objectMapper.writeValueAsString(embeddingClient.embed(text.content()));
                rows.add(new NoteChunkStore.ChunkRow(text.heading(), text.content(), vector));
            }
            chunkStore.replace(noteId, rows);
            vectorIndex.invalidate();
            log.info("note_reindexed noteId={} chunks={}", noteId, rows.size());
        } catch (Exception e) {
            log.error("note_reindex_failed noteId={}", noteId, e);
        }
    }

    private Optional<Note> loadAfterCommit(Long noteId) throws InterruptedException {
        for (int attempt = 1; attempt <= 5; attempt++) {
            Optional<Note> note = noteRepository.findById(noteId);
            if (note.isPresent()) return note;
            Thread.sleep(200L * attempt);
        }
        return Optional.empty();
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
