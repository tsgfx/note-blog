package com.notesblog;

import static org.assertj.core.api.Assertions.assertThat;

import com.notesblog.config.NotesProperties;
import com.notesblog.service.HealthService;
import com.notesblog.service.RateLimiter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 上下文启动烟雾测试。
 *
 * <p><b>为什么需要它：</b>2026-10-23 一天之内两次「一百个用例全绿、容器却起不来」：
 * 一次是配置占位符写错（{@code app.ollama.base-url}），一次是四个适配器各有两个公开构造器
 * 却没标 {@code @Autowired}（Spring 报 "No default constructor found"）。
 * 切片测试（{@code @WebMvcTest}）根本不启动完整上下文，抓不到这类错误，
 * 而它们恰恰最致命：测试全过，线上直接挂。
 *
 * <p><b>为什么用真实 MySQL 而不是内存库：</b>要验的是 JPA 映射、启动期 DDL 升级、
 * {@code seedAdmin} 的查询，这些都和 MySQL 方言绑着。换成 H2 等于换一个数据库再测。
 *
 * <p><b>两种运行方式：</b>
 * <ol>
 *   <li>默认：自建一次性 MySQL 容器（需要 Docker），跑完销毁；
 *   <li>指定 {@code SMOKE_MYSQL_URL}（可配 {@code SMOKE_MYSQL_USER}/{@code SMOKE_MYSQL_PASSWORD}）
 *       时改用你给的库——给动态端口转发不通的环境（如 colima 默认配置）留的出口。
 * </ol>
 * 两者都不可用时整个类跳过，保持「mvn test 不需要外部依赖」这条性质。
 *
 * <p><b>隔离性：</b>永远不碰开发/生产库。自建容器是全新实例；
 * 外部库请自己指一个一次性的 schema。
 */
@SpringBootTest(
        properties = {
            // 表结构由实体生成：容器里没有 db/schema.sql（它依赖 MySQL 的 VECTOR 列），
            // 而这个测试关心的是「上下文能否装配起来」，不是迁移脚本本身
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
@EnabledIf("smokeEnabled")
class ApplicationContextSmokeTest {

    private static final String EXTERNAL_URL = System.getenv("SMOKE_MYSQL_URL");

    private static MySQLContainer<?> container;

    /**
     * 默认**跳过**，需要显式开启。理由：
     *
     * <ul>
     *   <li>启动一个 MySQL 容器要几十秒，不该拖慢每次 `mvn test`；
     *   <li>某些环境下动态端口转发不通（colima 默认配置就试过），
     *       默认执行的话会让本地开发每次等 6 分钟再失败。
     * </ul>
     *
     * 开启方式二选一：
     * <pre>
     *   SMOKE_TESTCONTAINERS=1 mvn test              # 自建一次性容器（CI 用这个）
     *   SMOKE_MYSQL_URL=jdbc:mysql://host:port/db mvn test   # 指向一个一次性库
     * </pre>
     */
    static boolean smokeEnabled() {
        if (EXTERNAL_URL != null && !EXTERNAL_URL.isBlank()) return true;
        if (!"1".equals(System.getenv("SMOKE_TESTCONTAINERS"))) return false;
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (EXTERNAL_URL != null && !EXTERNAL_URL.isBlank()) {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username", () -> envOr("SMOKE_MYSQL_USER", "root"));
            registry.add("spring.datasource.password", () -> envOr("SMOKE_MYSQL_PASSWORD", "smoke"));
            return;
        }

        container = new MySQLContainer<>(DockerImageName.parse("mysql:9"))
                .withDatabaseName("notes_blog")
                // Testcontainers 默认会给 mysqld 加 --innodb_log_file_size=5M，
                // 但 MySQL 9.7 已删除该变量，不覆盖命令会直接启动失败
                .withCommand(
                        "mysqld", "--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");
        container.start();

        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
        registry.add("spring.datasource.driver-class-name", container::getDriverClassName);
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @AfterAll
    static void stopContainer() {
        if (container != null) {
            container.stop();
            container = null;
        }
    }

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("完整上下文能启动：Bean 装配、配置占位符、JPA 映射都不报错")
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getBean(HealthService.class)).isNotNull();
        assertThat(context.getBean(RateLimiter.class)).isNotNull();
        assertThat(context.getBean(NotesProperties.class)).isNotNull();
    }

    @Test
    @DisplayName("加载的是真实 application.yml：占位符与默认值都解析成功")
    void realConfigurationIsLoaded() {
        NotesProperties properties = context.getBean(NotesProperties.class);
        assertThat(properties.embedding().backend()).isNotBlank();
        assertThat(properties.embedding().model()).isNotBlank();
    }
}
