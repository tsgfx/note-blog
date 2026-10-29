package com.notesblog.service;

import com.notesblog.dto.HealthReport;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 真实健康检查。
 *
 * <p>之前 {@code /api/health} 返回的是写死的 {@code status: up}——数据库挂了它照样绿。
 * 那比没有健康检查更糟：{@code scripts/deploy.sh} 拿它判断部署是否成功，
 * 一个永远说 ok 的接口会让脚本打印假的成功。
 *
 * <p>两个刻意的设计：
 * <ul>
 *   <li><b>HTTP 永远 200</b>，只在 body 里标 {@code degraded}。返回 5xx 会让容器编排
 *       判定「后端挂了」然后重启，而实际情况可能只是 embedding 服务（Ollama/硅基流动）没起来，
 *       重启后端毫无意义。</li>
 *   <li><b>数据库断了才算真问题，embedding 断了只是降级</b>：向量模型不可用时，
 *       浏览、编辑、发布都还应该能用，只有问答和语义检索受影响。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthService {

    /** 依赖检查的总预算。一个健康检查不该让人等 10 秒。 */
    private static final long BUDGET_MILLIS = 1500;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingProbe embeddingProbe;

    /**
     * 部署版本，由部署脚本写进 .env 的 {@code APP_VERSION}（短 commit）。
     *
     * <p>放在健康检查里是因为「服务器上跑的是哪一版」以前只能靠 ssh 上去翻镜像时间。
     * 没配置时给 {@code dev}，本地开发不必设它。
     */
    @Value("${app.version:dev}")
    private String version;

    public HealthReport check() {
        boolean db = pingDatabase();
        boolean embedding = embeddingProbe.ping();

        Map<String, Boolean> components = new LinkedHashMap<>();
        components.put("db", db);
        components.put("embedding", embedding);

        boolean healthy = db && embedding;
        return new HealthReport(
                healthy ? HealthReport.UP : HealthReport.DEGRADED,
                components,
                version,
                LocalDateTime.now().toString());
    }

    /**
     * {@code select 1}，套一层超时。
     *
     * <p>不能只靠 JDBC 的超时设置：连接池等连接的时间不计入语句超时，
     * 数据库挂掉时取连接可能卡住 30 秒（Hikari 默认 connectionTimeout），
     * 健康检查就成了慢查询本身。这里用 completeOnTimeout 兜死。
     */
    private boolean pingDatabase() {
        try {
            Boolean ok = CompletableFuture
                    .supplyAsync(() -> jdbcTemplate.queryForObject("select 1", Integer.class) != null)
                    .completeOnTimeout(false, BUDGET_MILLIS, TimeUnit.MILLISECONDS)
                    .get();
            return Boolean.TRUE.equals(ok);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("数据库健康检查失败", e);
            return false;
        }
    }
}
