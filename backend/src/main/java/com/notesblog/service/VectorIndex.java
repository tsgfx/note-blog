package com.notesblog.service;

import com.notesblog.repository.NoteChunkRepository;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 分片与向量的内存缓存。向量在加载时做 L2 归一化，余弦相似度即点积。
 * 分片变化（重建索引、删除笔记）后调用 invalidate()，下次检索时重新加载。
 *
 * <p><b>草稿也在索引里</b>：分片是按笔记建的，不区分状态。要在公开检索里
 * 只取已发布的，调用方必须用 {@link Chunk#published()} 过滤——
 * 别指望这里替你藏起来，索引本身不承担权限判断。
 */
@Component
@RequiredArgsConstructor
public class VectorIndex {

    /**
     * @param published 所属笔记是否已发布。公开检索（游客问答、游客搜索）必须只用 true 的，
     *                  否则会把草稿正文泄露给匿名访客。
     */
    public record Chunk(long id, long noteId, String title, String slug, String heading,
                        String content, String searchText, float[] vector, boolean published) {
    }

    private final NoteChunkRepository chunkRepository;
    private volatile List<Chunk> snapshot;

    public List<Chunk> chunks() {
        List<Chunk> current = snapshot;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (snapshot == null) {
                snapshot = load();
            }
            return snapshot;
        }
    }

    /**
     * 只要已发布笔记的分片。
     *
     * <p>公开接口（游客问答/搜索）统一走这个，不要自己 filter——
     * 少写一次过滤就是一次草稿泄露。
     */
    public List<Chunk> publishedChunks() {
        return chunks().stream().filter(Chunk::published).toList();
    }

    public void invalidate() {
        snapshot = null;
    }

    /**
     * 余弦相似度即点积（向量加载时已做 L2 归一化，见类注释）。
     * 语义检索与相关笔记共用这一份实现，避免两处各写一个点积循环。
     */
    public static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    private List<Chunk> load() {
        return chunkRepository.findAllWithEmbedding().stream().map(this::toChunk).toList();
    }

    private Chunk toChunk(Object[] row) {
        float[] vector = decode(row[6]);
        normalize(vector);
        String heading = (String) row[4];
        String content = (String) row[5];
        String searchText = ((heading == null ? "" : heading) + "\n" + content).toLowerCase(Locale.ROOT);
        // 第 7 列是 notes.status，见 NoteChunkRepository.findAllWithEmbedding
        boolean published = row[7] != null && "PUBLISHED".equalsIgnoreCase(row[7].toString());
        return new Chunk(((Number) row[0]).longValue(), ((Number) row[1]).longValue(),
                (String) row[2], (String) row[3], heading, content, searchText, vector, published);
    }

    private static float[] decode(Object value) {
        byte[] bytes;
        if (value instanceof byte[] raw) {
            bytes = raw;
        } else {
            try {
                java.sql.Blob blob = (java.sql.Blob) value;
                bytes = blob.getBytes(1, (int) blob.length());
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("读取向量失败", e);
            }
        }
        float[] vector = new float[bytes.length / Float.BYTES];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vector);
        return vector;
    }

    static void normalize(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0) {
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
    }
}
