package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.notesblog.dto.LibraryCountDto;
import com.notesblog.repository.NoteChunkRepository;
import com.notesblog.repository.NoteRepository;
import com.notesblog.repository.TagRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 库名折算与聚合。折算规则必须和前端 libraryOf 一致，否则两边分类对不上。 */
@ExtendWith(MockitoExtension.class)
class NoteLibraryTest {

    @Mock
    private NoteRepository noteRepository;
    @Mock
    private TagRepository tagRepository;
    @Mock
    private NoteChunkRepository chunkRepository;
    @Mock
    private NoteIndexService noteIndexService;
    @Mock
    private AuditService auditService;
    @Mock
    private VectorIndex vectorIndex;

    private NoteService service;

    @BeforeEach
    void setUp() {
        service = new NoteService(noteRepository, tagRepository, chunkRepository,
                noteIndexService, auditService, vectorIndex);
    }

    @Test
    @DisplayName("库里名称折算：null → 未归档，无斜杠 → 根目录，否则取第一段")
    void libraryNameFolding() {
        assertThat(NoteService.libraryOf(null)).isEqualTo(NoteService.LIBRARY_UNFILED);
        assertThat(NoteService.libraryOf("  ")).isEqualTo(NoteService.LIBRARY_UNFILED);
        assertThat(NoteService.libraryOf("单一文件.md")).isEqualTo(NoteService.LIBRARY_ROOT);
        assertThat(NoteService.libraryOf("/开头就是斜杠.md")).isEqualTo(NoteService.LIBRARY_ROOT);
        assertThat(NoteService.libraryOf("c++/C++后端项目实战/assets/a.png")).isEqualTo("c++");
        assertThat(NoteService.libraryOf("科研/VLA/0-5")).isEqualTo("科研");
    }

    @Test
    @DisplayName("同一库下的多个路径会合并计数，并按篇数倒序")
    void aggregatesByLibrary() {
        when(noteRepository.countBySourcePath()).thenReturn(List.<Object[]>of(
                new Object[] {"c++/项目A", 3L},
                new Object[] {"c++/项目B", 2L},
                new Object[] {"科研/VLA", 1L},
                new Object[] {null, 4L}));

        List<LibraryCountDto> result = service.libraries();

        assertThat(result).containsExactly(
                new LibraryCountDto("c++", 5),
                new LibraryCountDto(NoteService.LIBRARY_UNFILED, 4),
                new LibraryCountDto("科研", 1));
    }

    @Test
    @DisplayName("标签聚合：按库收窄时只统计该库里的标签，排序按篇数降序")
    void tagFacetsFilteredByLibrary() {
        when(noteRepository.findTagPaths(null)).thenReturn(List.of(
                new Object[]{"C++", "c++/基础/指针.md"},
                new Object[]{"C++", "c++/基础/引用.md"},
                new Object[]{"指针", "c++/基础/指针.md"},
                new Object[]{"宋词", "My Life/风雅颂/词.md"}));

        var all = service.tagFacets(null, null);
        assertThat(all).extracting("name").containsExactly("C++", "宋词", "指针");
        assertThat(all.get(0).count()).isEqualTo(2);

        // 选中 c++ 库时，「宋词」不该出现在分类里
        var inCpp = service.tagFacets(null, "c++");
        assertThat(inCpp).extracting("name").containsExactly("C++", "指针");
        assertThat(inCpp.get(0).count()).isEqualTo(2);

        // 选中更深一层时同样收窄（多级目录导航用的就是这个）
        var inPointer = service.tagFacets(null, "c++/基础");
        assertThat(inPointer).extracting("name").containsExactly("C++", "指针");
    }

    @Test
    @DisplayName("路径前缀匹配：多级目录、根目录、未归档三个分支都要对")
    void pathMatches() {
        // 多级：前缀必须带斜杠，否则 "c" 会匹配到 "c++"
        assertThat(NoteService.pathMatches("c++/项目A/指针.md", "c++")).isTrue();
        assertThat(NoteService.pathMatches("c++/项目A/指针.md", "c++/项目A")).isTrue();
        assertThat(NoteService.pathMatches("c++/项目A/指针.md", "c++/项目B")).isFalse();
        assertThat(NoteService.pathMatches("cpp/项目A/指针.md", "c")).isFalse();

        // 根目录：没有斜杠的笔记
        assertThat(NoteService.pathMatches("单一文件.md", NoteService.LIBRARY_ROOT)).isTrue();
        assertThat(NoteService.pathMatches("c++/指针.md", NoteService.LIBRARY_ROOT)).isFalse();

        // 未归档：null 或空白
        assertThat(NoteService.pathMatches(null, NoteService.LIBRARY_UNFILED)).isTrue();
        assertThat(NoteService.pathMatches("  ", NoteService.LIBRARY_UNFILED)).isTrue();
        assertThat(NoteService.pathMatches("c++/指针.md", NoteService.LIBRARY_UNFILED)).isFalse();

        // 空筛选 = 不过滤
        assertThat(NoteService.pathMatches("任意/路径.md", "")).isTrue();
        assertThat(NoteService.pathMatches("任意/路径.md", null)).isTrue();
    }
}
