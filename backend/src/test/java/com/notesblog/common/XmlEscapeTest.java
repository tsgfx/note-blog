package com.notesblog.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class XmlEscapeTest {

    @Test
    @DisplayName("统一转义 XML 特殊字符，null 输出为空串")
    void escapesXmlText() {
        assertThat(XmlEscape.text("A&B <x> \"q\" 's'")).isEqualTo("A&amp;B &lt;x&gt; &quot;q&quot; &apos;s&apos;");
        assertThat(XmlEscape.text(null)).isEmpty();
    }
}
