package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notesblog.domain.Note;
import com.notesblog.repository.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/** 管理端目录分页与排序语义。HTTP 控制器只透传原始参数，不拥有这些规则。 */
@ExtendWith(MockitoExtension.class)
class NoteCatalogPagingTest {

    @Mock
    private NoteRepository noteRepository;

    private NoteCatalogService service;

    @BeforeEach
    void setUp() {
        service = new NoteCatalogService(noteRepository);
        when(noteRepository.findAll(org.mockito.ArgumentMatchers.<Specification<Note>>any(), any(Pageable.class)))
                .thenReturn(Page.empty());
    }

    @Test
    @DisplayName("非法页码回退到第一页，size=0 保持既有语义回退到 1")
    void invalidPagingIsClamped() {
        service.adminList(null, null, null, null, false, null, -1, 0);

        Pageable pageable = capturedPageable();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("管理端单页最多 100，避免一次拉走整库")
    void pageSizeIsCapped() {
        service.adminList(null, null, null, null, false, null, 0, 500);

        assertThat(capturedPageable().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("views / published / 默认排序都带稳定兜底字段")
    void sortMappingIsStable() {
        service.adminList(null, null, null, null, false, "views", 0, 20);
        Pageable views = capturedPageable();
        assertThat(views.getSort().getOrderFor("viewCount")).isNotNull();
        assertThat(views.getSort().getOrderFor("viewCount").isDescending()).isTrue();
        assertThat(views.getSort().getOrderFor("id")).isNotNull();

        service.adminList(null, null, null, null, false, "published", 0, 20);
        Pageable published = capturedPageable();
        assertThat(published.getSort().getOrderFor("publishedAt")).isNotNull();
        assertThat(published.getSort().getOrderFor("id")).isNotNull();

        service.adminList(null, null, null, null, false, "unknown", 0, 20);
        Pageable fallback = capturedPageable();
        assertThat(fallback.getSort().getOrderFor("updatedAt")).isNotNull();
        assertThat(fallback.getSort().getOrderFor("id")).isNotNull();
    }

    private Pageable capturedPageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(noteRepository, org.mockito.Mockito.atLeastOnce())
                .findAll(org.mockito.ArgumentMatchers.<Specification<Note>>any(), captor.capture());
        return captor.getValue();
    }
}
