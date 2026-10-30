package com.notesblog.controller;

import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.domain.Tag;
import com.notesblog.repository.NoteRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

/** 公开博客的 RSS 2.0 订阅源。 */
@RestController
@RequestMapping("/api/blog")
@RequiredArgsConstructor
public class RssController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME;

    private final NoteRepository noteRepository;

    @Value("${site.base-url}")
    private String baseUrl;

    @Value("${site.title}")
    private String title;

    /** 订阅源最多输出多少条。RSS 阅读器不适合塞整站内容，所以给个上限，但不再是散落的魔法数字。 */
    @Value("${app.rss.max-items:50}")
    private int maxItems;

    @GetMapping(value = {"/rss.xml", "/feed.xml", "/atom.xml"}, produces = "application/rss+xml;charset=UTF-8")
    @Transactional(readOnly = true)
    public String feed() {
        String base = siteUrl();
        int limit = Math.max(maxItems, 1);
        List<Note> posts = noteRepository.findByStatus(NoteStatus.PUBLISHED,
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "publishedAt"))).getContent();
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<rss version=\"2.0\"><channel>\n")
                .append("<title>").append(escape(title)).append("</title>\n")
                .append("<link>").append(escape(base)).append("</link>\n")
                .append("<description>").append(escape(title)).append("</description>\n")
                .append("<lastBuildDate>").append(rfc1123(LocalDateTime.now())).append("</lastBuildDate>\n");
        for (Note note : posts) {
            String link = base + "/blog/" + note.getSlug();
            xml.append("<item>\n")
                    .append("<title>").append(escape(note.getTitle())).append("</title>\n")
                    .append("<link>").append(escape(link)).append("</link>\n")
                    .append("<guid isPermaLink=\"true\">").append(escape(link)).append("</guid>\n")
                    .append("<description>").append(escape(description(note))).append("</description>\n");
            if (note.getPublishedAt() != null) {
                xml.append("<pubDate>").append(rfc1123(note.getPublishedAt())).append("</pubDate>\n");
            }
            if (note.getTags() != null) {
                for (Tag tag : note.getTags()) {
                    if (tag.getName() != null && !tag.getName().isBlank()) {
                        xml.append("<category>").append(escape(tag.getName())).append("</category>\n");
                    }
                }
            }
            xml.append("</item>\n");
        }
        return xml.append("</channel></rss>\n").toString();
    }

    /** 站点地址去掉结尾斜杠，避免拼出 https://example.com//blog/xxx 这种双斜杠链接。 */
    private String siteUrl() {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String description(Note note) {
        if (note.getSummary() != null && !note.getSummary().isBlank()) {
            return note.getSummary();
        }
        // 正文可能为空（历史数据或先建后补），不能让 RSS 整个 500
        if (note.getContentMd() == null || note.getContentMd().isBlank()) {
            return note.getTitle();
        }
        String plain = note.getContentMd().replaceAll("[#*_`>\\[\\]()!-]", " ").replaceAll("\\s+", " ").trim();
        return plain.length() > 200 ? plain.substring(0, 200) + "…" : plain;
    }

    private static String rfc1123(LocalDateTime time) {
        return RFC_1123.format(ZonedDateTime.of(time, ZONE));
    }

    private static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
