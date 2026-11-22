package com.notesblog.service;

import com.notesblog.repository.NoteChunkRepository;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 分片与向量的内存缓存。向量在加载时做 L2 归一化，余弦相似度即点积。
 * 分片变化（重建索引、删除笔记）后调用 invalidate()：快照立即作废（保证发布/下线的
 * 可见性立刻切换），同时在后台预热一份新的，免得把几秒的全量加载压在第一个检索请求上。
 *
 * <p><b>草稿也在索引里</b>：分片是按笔记建的，不区分状态。要在公开检索里
 * 只取已发布的，调用方必须用 {@link Chunk#published()} 过滤——
 * 别指望这里替你藏起来，索引本身不承担权限判断。
 */
@Slf4j
@Component
public class VectorIndex {

    /** 预热前的静默期：批量重建会连着失效十几次，合并成一次加载。 */
    private static final long WARM_DEBOUNCE_MILLIS = 400L;

    /**
     * @param published 所属笔记是否已发布。公开检索（游客问答、游客搜索）必须只用 true 的，
     *                  否则会把草稿正文泄露给匿名访客。
     */
    public record Chunk(long id, long noteId, String title, String slug, String heading,
                        String content, String searchText, float[] vector, boolean published) {
    }

    private final NoteChunkRepository chunkRepository;
    private final ExecutorService warmExecutor;
    /** 同一时间只跑一次预热；批量重建会连着 invalidate 很多次，不该排成一串全量加载。 */
    private final AtomicBoolean warming = new AtomicBoolean();
    private volatile List<Chunk> snapshot;

    public VectorIndex(NoteChunkRepository chunkRepository,
                       @Qualifier("sideEffectExecutor") ExecutorService warmExecutor) {
        this.chunkRepository = chunkRepository;
        this.warmExecutor = warmExecutor;
    }

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
        warm();
    }

    /**
     * 启动后先把索引热起来：重启后的第一个提问不该替全站付那 2~4 秒的全量加载。
     * 用 ApplicationReadyEvent 而不是 @PostConstruct，免得拖慢启动与健康检查。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmOnStartup() {
        warm();
    }

    /**
     * 后台预热。与读方共用 {@link #chunks()} 里的同一把锁，所以不会出现「预热加载一遍、
     * 首个请求又加载一遍」的重复；预热被线程池拒绝也不要紧，读方仍会自己同步加载。
     */
    private void warm() {
        if (!warming.compareAndSet(false, true)) {
            return;
        }
        try {
            warmExecutor.execute(this::warmNow);
        } catch (RejectedExecutionException rejected) {
            warming.set(false);
            log.debug("index_warm_rejected");
        }
    }

    private void warmNow() {
        boolean loaded = false;
        try {
            // 静默期：批量重建期间会连着失效，等这一小段过去再一次性加载。
            Thread.sleep(WARM_DEBOUNCE_MILLIS);
            chunks();
            loaded = true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            // 加载失败不要立刻重试，否则数据库不可用时这里会变成忙循环；
            // 读方拿到异常，下一次 invalidate 或下一个请求会再试。
            log.warn("index_warm_failed", failure);
        } finally {
            warming.set(false);
            if (loaded && snapshot == null) {
                // 预热途中又被失效（例如刚发布了一篇），追一次差。
                warm();
            }
        }
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
