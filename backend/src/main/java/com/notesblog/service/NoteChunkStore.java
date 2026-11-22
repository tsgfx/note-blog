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

    /**
     * 重建某篇笔记的分片。
     *
     * <p>用 {@code batchUpdate} 而不是逐行 {@code update}：一篇长笔记几十个分片，
     * 逐行发就是几十次网络往返，全在持有写锁的事务里——期间任何读这条笔记的请求都在等。
     * 批量把往返压成一次，锁的持有时间跟着降一个数量级。
     */
    @Transactional
    public void replace(Long noteId, List<ChunkRow> chunks) {
        jdbcTemplate.update("DELETE FROM note_chunks WHERE note_id = ?", noteId);
        if (chunks.isEmpty()) {
            return;
        }
        List<Object[]> args = new java.util.ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            ChunkRow chunk = chunks.get(i);
            args.add(new Object[] {noteId, chunk.heading(), i, chunk.content(), chunk.vectorJson()});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO note_chunks (note_id, heading, ordinal, content, embedding)
                VALUES (?, ?, ?, ?, STRING_TO_VECTOR(?))
                """, args);
    }

    public record ChunkRow(String heading, String content, String vectorJson) {
    }
}
