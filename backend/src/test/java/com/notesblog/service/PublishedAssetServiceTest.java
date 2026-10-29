package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.notesblog.domain.NoteStatus;
import com.notesblog.repository.NoteRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PublishedAssetServiceTest {

    private final NoteRepository noteRepository = mock(NoteRepository.class);
    private final PublishedAssetService service = new PublishedAssetService(noteRepository);

    @Test
    @DisplayName("从已发布正文提取 /api/assets/ 引用并做 URL 解码")
    void extractsAndDecodesAssetRefs() {
        when(noteRepository.findContentMdByStatus(NoteStatus.PUBLISHED)).thenReturn(List.of(
                "![image.png](/api/assets/Java/Java%E5%9F%BA%E7%A1%80/assets/Ajax-1.png)",
                "直接写也认：/api/assets/uploads/2026/uuid.png 后面跟句号也算。",
                "无关内容，没有引用"));

        service.refresh();

        assertThat(service.isPubliclyReadable("Java/Java基础/assets/Ajax-1.png")).isTrue();
        assertThat(service.isPubliclyReadable("uploads/2026/uuid.png")).isTrue();
        assertThat(service.isPubliclyReadable("Java/Java基础/assets/Ajax-2.png")).isFalse();
        assertThat(service.isPubliclyReadable("")).isFalse();
        assertThat(service.isPubliclyReadable(null)).isFalse();
    }

    @Test
    @DisplayName("空库与空正文都不报错")
    void toleratesEmptyLibrary() {
        when(noteRepository.findContentMdByStatus(NoteStatus.PUBLISHED)).thenReturn(java.util.Arrays.asList("", null));

        service.refresh();

        assertThat(service.isPubliclyReadable("anything.png")).isFalse();
    }
}
