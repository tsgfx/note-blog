package com.notesblog.repository;

import com.notesblog.domain.NoteChunk;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NoteChunkRepository extends JpaRepository<NoteChunk, Long> {

    List<NoteChunk> findByNoteIdOrderByOrdinal(Long noteId);

    void deleteByNoteId(Long noteId);

    /** 补索引时用来判断「这篇还没分片」——嵌入服务挂过一次，就会留下一批这样的笔记。 */
    boolean existsByNoteId(Long noteId);

    /** 全量读取分片与向量，仅供内存缓存（VectorIndex）加载使用。 */
    @Query(value = """
            SELECT c.id AS id, c.note_id AS noteId, n.title AS title, n.slug AS slug,
                   c.heading AS heading, c.content AS content, c.embedding AS embedding
            FROM note_chunks c
            JOIN notes n ON n.id = c.note_id
            """, nativeQuery = true)
    List<Object[]> findAllWithEmbedding();
}
