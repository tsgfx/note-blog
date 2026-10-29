package com.notesblog.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class NoteChunkStore {
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void replace(Long noteId, List<ChunkRow> chunks) {
        jdbcTemplate.update("DELETE FROM note_chunks WHERE note_id = ?", noteId);
        for (int i = 0; i < chunks.size(); i++) {
            ChunkRow chunk = chunks.get(i);
            jdbcTemplate.update("""
                    INSERT INTO note_chunks (note_id, heading, ordinal, content, embedding)
                    VALUES (?, ?, ?, ?, STRING_TO_VECTOR(?))
                    """, noteId, chunk.heading(), i, chunk.content(), chunk.vectorJson());
        }
    }

    public record ChunkRow(String heading, String content, String vectorJson) {
    }
}
