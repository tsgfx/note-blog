package com.notesblog.controller;

import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.repository.NoteRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 给爬虫的 sitemap.xml 与 robots.txt。
 *
 * <p>为什么放在后端而不是 frontend/public：这两个文件都要带站点的**绝对地址**
 * （sitemap 的 loc、robots 的 Sitemap 行），而站点地址是部署期才定的（现在是 IP，
 * 以后换域名）。放静态文件就得在构建时注入，一旦和 {@code site.base-url} 不同步，
 * 爬虫拿到的就是指向别处的链接；RSS 早就踩过同一个坑，所以这里同样由后端生成。
 *
 * <p>nginx 侧对 /sitemap.xml 与 /robots.txt 做精确别名转发到 /api/blog/*，
 * 否则 SPA 的 try_files 会把它们兜底成 index.html——返回 200 + HTML，
 * 爬虫拿到假成功比拿到 404 更糟：它会认定这个站点没有可用 sitemap。
 */
@RestController
@RequestMapping("/api/blog")
@RequiredArgsConstructor
public class SitemapController {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final NoteRepository noteRepository;

    @Value("${site.base-url}")
    private String baseUrl;

    /** sitemap 单文件上限按协议是 5 万条，这里给个可配的保守值，免得站点长大后撑爆。 */
    @Value("${app.sitemap.max-items:5000}")
    private int maxItems;

    @GetMapping(value = "/sitemap.xml", produces = "application/xml;charset=UTF-8")
    @Transactional(readOnly = true)
    public String sitemap() {
        String base = siteUrl();
        List<Note> posts = noteRepository.findByStatus(NoteStatus.PUBLISHED,
                PageRequest.of(0, Math.max(maxItems, 1), Sort.by(Sort.Direction.DESC, "publishedAt")))
                .getContent();

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        // 首页（名片）与博客列表：这两页是常驻入口，没有 lastmod 语义，不硬编时间
        xml.append(url(base + "/", null, "1.0"));
        xml.append(url(base + "/blog", null, "0.9"));
        for (Note note : posts) {
            xml.append(url(base + "/blog/" + note.getSlug(), lastmod(note), "0.8"));
        }
        return xml.append("</urlset>\n").toString();
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public String robots() {
        StringBuilder txt = new StringBuilder();
        txt.append("# 公开内容只有首页名片和博客；管理面全部挡掉\n")
                .append("User-agent: *\n")
                .append("Allow: /\n")
                .append("Allow: /blog/\n")
                .append("Disallow: /notes\n")
                .append("Disallow: /editor\n")
                .append("Disallow: /settings\n")
                .append("Disallow: /admin\n")
                .append("Disallow: /login\n")
                .append("Disallow: /ask\n");
        String base = siteUrl();
        // Sitemap 指令要求绝对地址；没配 base-url 就不写，宁缺毋滥
        if (!base.isEmpty()) {
            txt.append("\nSitemap: ").append(base).append("/sitemap.xml\n");
        }
        return txt.toString();
    }

    private static String url(String loc, String lastmod, String priority) {
        StringBuilder sb = new StringBuilder("  <url><loc>").append(escape(loc)).append("</loc>");
        if (lastmod != null) {
            sb.append("<lastmod>").append(lastmod).append("</lastmod>");
        }
        return sb.append("<priority>").append(priority).append("</priority></url>\n").toString();
    }

    /** 优先用更新时间：正文改过但没重新发布，也是页面内容变了。 */
    private static String lastmod(Note note) {
        LocalDateTime time = note.getUpdatedAt() != null ? note.getUpdatedAt() : note.getPublishedAt();
        return time == null ? null : DATE.format(time);
    }

    private String siteUrl() {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
