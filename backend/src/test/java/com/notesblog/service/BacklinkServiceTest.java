package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.domain.Tag;
import com.notesblog.dto.BacklinkDto;
import com.notesblog.repository.NoteRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class BacklinkServiceTest {

    @Mock
    private NoteRepository noteRepository;

    @InjectMocks
    private BacklinkService backlinkService;

    @Test
    @DisplayName("上下文提取：能够准确定位正文中的 slug 链接并提取包含前后文的干净摘要")
    void extractSnippetFindsSlugLink() {
        String content = "# 前言\n\n关于环境配置，请查阅 [Conda常用命令](/blog/post-conda-123)，另外可参考其他工具。";
        String snippet = backlinkService.extractSnippet(content, "post-conda-123", "Conda常用命令");

        assertThat(snippet).isNotNull();
        assertThat(snippet).contains("Conda常用命令");
        assertThat(snippet).doesNotContain("#");
    }

    @Test
    @DisplayName("上下文提取：支持 Wiki-link 语法 [[笔记标题]] 的精准定位")
    void extractSnippetFindsWikiLink() {
        String content = "本章讲解模型微调。前置知识包含 [[深度学习基础]]，请提前阅读。";
        String snippet = backlinkService.extractSnippet(content, "slug-dl-base", "深度学习基础");

        assertThat(snippet).isNotNull();
        assertThat(snippet).contains("深度学习基础");
    }

    @Test
    @DisplayName("公开反向链接：仅检索已发布引用笔记，排除草稿并正确组装 DTO")
    void findBacklinksForPublic() {
        Note target = new Note();
        target.setId(10L);
        target.setTitle("目标文章");
        target.setSlug("target-slug");
        target.setStatus(NoteStatus.PUBLISHED);

        Note citing = new Note();
        citing.setId(20L);
        citing.setTitle("引用文章");
        citing.setSlug("citing-slug");
        citing.setStatus(NoteStatus.PUBLISHED);
        citing.setSourcePath("博客/推荐系统/引用文章.md");
        citing.setContentMd("详细内容可以参见 [目标文章](/blog/target-slug) 的分析。");
        citing.setUpdatedAt(LocalDateTime.now());
        citing.setTags(Set.of(new Tag("专栏:推荐系统实战")));

        when(noteRepository.findBySlug("target-slug")).thenReturn(Optional.of(target));
        when(noteRepository.findBacklinksCandidates(eq(10L), eq(NoteStatus.PUBLISHED), eq("target-slug"), eq("目标文章"), any(Pageable.class)))
                .thenReturn(List.of(citing));

        List<BacklinkDto> backlinks = backlinkService.findBacklinksForPublic("target-slug");
        assertThat(backlinks).hasSize(1);
        BacklinkDto dto = backlinks.get(0);
        assertThat(dto.title()).isEqualTo("引用文章");
        assertThat(dto.slug()).isEqualTo("citing-slug");
        assertThat(dto.series()).isEqualTo("推荐系统实战");
        assertThat(dto.snippet()).contains("目标文章");
    }
}
