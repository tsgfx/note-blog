package com.notesblog.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SiteUrlsTest {

    @Test
    @DisplayName("去掉空白和连续末尾斜杠，公开文章地址不出现双斜杠")
    void normalizesTrailingSlashes() {
        SiteUrls urls = new SiteUrls("  https://notes.example.com///  ", "一隅笔记");

        assertThat(urls.base()).isEqualTo("https://notes.example.com");
        assertThat(urls.post("hello-world")).isEqualTo("https://notes.example.com/blog/hello-world");
    }

    @Test
    @DisplayName("统一构造首页、博客列表与 sitemap 地址")
    void buildsCanonicalPublicUrls() {
        SiteUrls urls = new SiteUrls("https://notes.example.com", "一隅笔记");

        assertThat(urls.home()).isEqualTo("https://notes.example.com/");
        assertThat(urls.blogIndex()).isEqualTo("https://notes.example.com/blog");
        assertThat(urls.sitemap()).isEqualTo("https://notes.example.com/sitemap.xml");
        assertThat(urls.title()).isEqualTo("一隅笔记");
    }
}
