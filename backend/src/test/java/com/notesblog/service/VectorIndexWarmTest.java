package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notesblog.repository.NoteChunkRepository;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 预热的行为约束：失效后由后台线程补一份快照，批量失效要合并，线程池拒绝也不能让读方挂掉。
 *
 * <p>这些断言都依赖「后台线程跑完了没有」，所以不用固定 sleep，而是轮询到条件成立为止，
 * 超时再失败——固定 sleep 在慢机器上会变成偶发红灯。
 */
class VectorIndexWarmTest {

    private static final long WAIT_TIMEOUT_MILLIS = 5_000L;

    private NoteChunkRepository repository;
    private ExecutorService executor;
    private VectorIndex index;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(NoteChunkRepository.class);
        when(repository.findAllWithEmbedding()).thenReturn(List.<Object[]>of(row(1L, 10L, "PUBLISHED")));
        executor = Executors.newSingleThreadExecutor();
        index = new VectorIndex(repository, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void invalidateWarmsTheSnapshotInTheBackground() {
        index.invalidate();

        awaitChunksLoaded();
        // 读方拿到的就是预热好的那份，不该再查一次库
        assertThat(index.chunks()).hasSize(1);
        verify(repository, times(1)).findAllWithEmbedding();
    }

    @Test
    void startupWarmsWithoutAnyRequest() {
        index.warmOnStartup();

        awaitChunksLoaded();
        verify(repository, times(1)).findAllWithEmbedding();
    }

    @Test
    void burstOfInvalidationsIsCoalesced() {
        for (int i = 0; i < 12; i++) {
            index.invalidate();
        }

        awaitChunksLoaded();
        // 静默期把 12 次失效合并成一次加载（最多补一次差）
        verify(repository, Mockito.atMost(2)).findAllWithEmbedding();
    }

    @Test
    void rejectedWarmStillLetsReaderLoadSynchronously() {
        VectorIndex rejected = new VectorIndex(repository, new RejectingExecutor());

        rejected.invalidate();
        assertThat(rejected.chunks()).hasSize(1);
        verify(repository, times(1)).findAllWithEmbedding();
    }

    private void awaitChunksLoaded() {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (loaded()) {
                    // 仓库刚被调用时快照还在构建，留一小段让预热线程把 snapshot 写完
                    Thread.sleep(200L);
                    return;
                }
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待预热时被打断", interrupted);
            }
        }
        throw new AssertionError("预热在 " + WAIT_TIMEOUT_MILLIS + "ms 内没有完成");
    }

    /** 预热完成的判据：仓库只被查一次且 chunks() 已能立刻返回（不再触发同步加载）。 */
    private boolean loaded() {
        return Mockito.mockingDetails(repository).getInvocations().stream()
                .anyMatch(invocation -> invocation.getMethod().getName().equals("findAllWithEmbedding"));
    }

    private static Object[] row(long id, long noteId, String status) {
        byte[] embedding = ByteBuffer.allocate(4 * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(0.6f).putFloat(0.8f).putFloat(0f).putFloat(0f)
                .array();
        return new Object[] {id, noteId, "标题", "slug-" + id, "小节", "正文", embedding, status};
    }

    private static final class RejectingExecutor implements ExecutorService {
        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("queue full");
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return true;
        }

        @Override
        public boolean isTerminated() {
            return true;
        }

        @Override
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
            return true;
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
            throw new RejectedExecutionException("queue full");
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
            throw new RejectedExecutionException("queue full");
        }

        @Override
        public java.util.concurrent.Future<?> submit(Runnable task) {
            throw new RejectedExecutionException("queue full");
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            throw new RejectedExecutionException("queue full");
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks, long timeout,
                java.util.concurrent.TimeUnit unit) {
            throw new RejectedExecutionException("queue full");
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            throw new RejectedExecutionException("queue full");
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
                               long timeout, java.util.concurrent.TimeUnit unit) {
            throw new RejectedExecutionException("queue full");
        }
    }
}