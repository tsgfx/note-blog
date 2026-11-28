package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.notesblog.domain.Note;
import com.notesblog.domain.Tag;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NoteTaxonomyTest {

    @Test
    @DisplayName("专栏/系列标签统一解释为无前缀系列名")
    void extractsSeriesNameWithoutPrefix() {
        Note note = new Note();
        note.setTags(Set.of(new Tag("系列:推荐系统实战")));
        assertThat(NoteTaxonomy.seriesName(note)).isEqualTo("推荐系统实战");

        note.setTags(Set.of(new Tag("专栏:系统设计")));
        assertThat(NoteTaxonomy.seriesName(note)).isEqualTo("系统设计");
    }

    @Test
    @DisplayName("没有系列标签或标签集合时返回 null，不发明默认系列")
    void missingSeriesStaysNull() {
        assertThat(NoteTaxonomy.seriesName(null)).isNull();
        assertThat(NoteTaxonomy.seriesName(new Note())).isNull();
    }
}
