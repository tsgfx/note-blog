package com.notesblog.controller;

import com.notesblog.common.XmlEscape;
import com.notesblog.config.SiteUrls;
import com.notesblog.service.PublicBlogService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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

    private final PublicBlogService publicBlogService;
    private final SiteUrls siteUrls;

    @Value("${site.title}")
    private String title;

    /** 订阅源最多输出多少条。RSS 阅读器不适合塞整站内容，所以给个上限，但不再是散落的魔法数字。 */
    @Value("${app.rss.max-items:50}")
    private int maxItems;

    @GetMapping(value = {"/rss.xml", "/feed.xml", "/atom.xml"}, produces = "application/rss+xml;charset=UTF-8")
    @Transactional(readOnly = true)
    public String feed() {
        String base = siteUrls.base();
        List<PublicBlogService.FeedItem> posts = publicBlogService.feedItems(maxItems);
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<rss version=\"2.0\"><channel>\n")
                .append("<title>").append(XmlEscape.text(title)).append("</title>\n")
                .append("<link>").append(XmlEscape.text(base)).append("</link>\n")
                .append("<description>").append(XmlEscape.text(title)).append("</description>\n")
                .append("<lastBuildDate>").append(rfc1123(LocalDateTime.now())).append("</lastBuildDate>\n");
        for (PublicBlogService.FeedItem note : posts) {
            String link = siteUrls.post(note.slug());
            xml.append("<item>\n")
                    .append("<title>").append(XmlEscape.text(note.title())).append("</title>\n")
                    .append("<link>").append(XmlEscape.text(link)).append("</link>\n")
                    .append("<guid isPermaLink=\"true\">").append(XmlEscape.text(link)).append("</guid>\n")
                    .append("<description>").append(XmlEscape.text(description(note))).append("</description>\n");
            if (note.publishedAt() != null) {
                xml.append("<pubDate>").append(rfc1123(note.publishedAt())).append("</pubDate>\n");
            }
            for (String tag : note.tags()) {
                xml.append("<category>").append(XmlEscape.text(tag)).append("</category>\n");
            }
            xml.append("</item>\n");
        }
        return xml.append("</channel></rss>\n").toString();
    }

    private String description(PublicBlogService.FeedItem note) {
        if (note.summary() != null && !note.summary().isBlank()) {
            return note.summary();
        }
        // 正文可能为空（历史数据或先建后补），不能让 RSS 整个 500
        if (note.contentMd() == null || note.contentMd().isBlank()) {
            return note.title();
        }
        String plain = note.contentMd().replaceAll("[#*_`>\\[\\]()!-]", " ").replaceAll("\\s+", " ").trim();
        return plain.length() > 200 ? plain.substring(0, 200) + "…" : plain;
    }

    private static String rfc1123(LocalDateTime time) {
        return RFC_1123.format(ZonedDateTime.of(time, ZONE));
    }

}
