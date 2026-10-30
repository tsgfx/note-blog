package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.dto.IntegrityReportDto;
import com.notesblog.repository.NoteRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IntegrityCheckServiceTest {

    @Test
    @DisplayName("已发布笔记中引用的本地图片不存在时：准确检出死链资产")
    void detectBrokenAssetsInPublishedNotes() {
        NoteRepository noteRepository = mock(NoteRepository.class);
        NoteService noteService = mock(NoteService.class);
        AssetStorage assetStorage = mock(AssetStorage.class);

        Note n1 = new Note();
        n1.setId(1L);
        n1.setTitle("微服务拆分");
        n1.setSlug("microservices");
        n1.setStatus(NoteStatus.PUBLISHED);
        n1.setContentMd("架构图如下：\n![架构](/api/assets/uploads/2026/arch.png)\n还有一张不存在的图：\n![序列](/api/assets/uploads/2026/missing.png)");

        Note n2 = new Note();
        n2.setId(2L);
        n2.setTitle("草稿思考");
        n2.setSlug("draft-ideas");
        n2.setStatus(NoteStatus.DRAFT);
        n2.setContentMd("草稿随便贴图：![测试](/api/assets/uploads/2026/ignore.png)");

        when(noteRepository.findAll()).thenReturn(List.of(n1, n2));
        when(noteService.countMissingChunks()).thenReturn(0L);

        when(assetStorage.exists("uploads/2026/arch.png")).thenReturn(true);
        when(assetStorage.exists("uploads/2026/missing.png")).thenReturn(false);

        IntegrityCheckService service = new IntegrityCheckService(noteRepository, noteService, assetStorage);
        IntegrityReportDto report = service.check();

        assertThat(report.totalNotes()).isEqualTo(2);
        assertThat(report.publishedNotes()).isEqualTo(1);
        assertThat(report.draftNotes()).isEqualTo(1);
        assertThat(report.brokenAssets()).hasSize(1);
        assertThat(report.brokenAssets().get(0).assetPath()).isEqualTo("uploads/2026/missing.png");
        assertThat(report.brokenAssets().get(0).noteTitle()).isEqualTo("微服务拆分");
    }

    @Test
    @DisplayName("统计正文为空的笔记与缺失分片的笔记")
    void countEmptyNotesAndMissingChunks() {
        NoteRepository noteRepository = mock(NoteRepository.class);
        NoteService noteService = mock(NoteService.class);
        AssetStorage assetStorage = mock(AssetStorage.class);

        Note n1 = new Note();
        n1.setId(10L);
        n1.setStatus(NoteStatus.DRAFT);
        n1.setContentMd("   ");

        when(noteRepository.findAll()).thenReturn(List.of(n1));
        when(noteService.countMissingChunks()).thenReturn(3L);

        IntegrityCheckService service = new IntegrityCheckService(noteRepository, noteService, assetStorage);
        IntegrityReportDto report = service.check();

        assertThat(report.emptyNotes()).isEqualTo(1);
        assertThat(report.missingChunksNotes()).isEqualTo(3);
        assertThat(report.brokenAssets()).isEmpty();
    }
}
