package com.notesblog.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

/** 限流配置契约：yml 的键名必须能完整绑定，避免又回到散落字符串。 */
class RateLimitPropertiesTest {

    @Test
    @DisplayName("app.rate-limit.* 按 canonical kebab-case 绑定")
    void bindsCanonicalKeys() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "app.rate-limit.ask-per-minute", 7,
                "app.rate-limit.search-per-minute", 8,
                "app.rate-limit.view-per-minute", 9,
                "app.rate-limit.public-search-per-minute", 10,
                "app.rate-limit.backlinks-per-minute", 11)));

        RateLimitProperties properties = binder.bind("app.rate-limit", RateLimitProperties.class).get();

        assertThat(properties.askPerMinute()).isEqualTo(7);
        assertThat(properties.searchPerMinute()).isEqualTo(8);
        assertThat(properties.viewPerMinute()).isEqualTo(9);
        assertThat(properties.publicSearchPerMinute()).isEqualTo(10);
        assertThat(properties.backlinksPerMinute()).isEqualTo(11);
    }
}
