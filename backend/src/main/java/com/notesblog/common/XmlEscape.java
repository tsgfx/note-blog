package com.notesblog.common;

/** XML 文本节点的统一转义。RSS 与 sitemap 只允许这一种实现，避免边角字符行为分叉。 */
public final class XmlEscape {

    private XmlEscape() {}

    public static String text(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
