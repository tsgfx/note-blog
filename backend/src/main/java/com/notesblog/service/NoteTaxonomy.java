package com.notesblog.service;

import com.notesblog.domain.Note;
import com.notesblog.domain.Tag;

/** 笔记分类派生规则。只放跨服务共享的领域概念，避免成为另一个工具杂物间。 */
final class NoteTaxonomy {

    private NoteTaxonomy() {
    }

    /** 「专栏:xxx」与「系列:xxx」标签的权威解释；返回 xxx，不携带前缀。 */
    static String seriesName(Note note) {
        if (note == null || note.getTags() == null) return null;
        for (Tag tag : note.getTags()) {
            String name = tag.getName();
            if (name != null && (name.startsWith("专栏:") || name.startsWith("系列:"))) {
                return name.substring(3).trim();
            }
        }
        return null;
    }
}
