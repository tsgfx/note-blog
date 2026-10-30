package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notesblog.domain.Note;
import com.notesblog.domain.Tag;
import com.notesblog.dto.QuickCaptureRequest;
import com.notesblog.dto.QuickCaptureResponseDto;
import com.notesblog.repository.NoteRepository;
import com.notesblog.repository.TagRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InboxServiceTest {

    @Mock
    private NoteRepository noteRepository;
    @Mock
    private TagRepository tagRepository;
    @Mock
    private NoteIndexService noteIndexService;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private InboxService inboxService;

    @Test
    @DisplayName("标题智能提取：从正文首个非空行中去除 markdown 标题前缀并截取")
    void extractTitleCleansMarkdownPrefix() {
        String content = "\n\n  ### 深度学习在图搜索中的应用实践  \n正文第一段描述...";
        String title = InboxService.extractTitle(content);
        assertThat(title).isEqualTo("深度学习在图搜索中的应用实践");
    }

    @Test
    @DisplayName("标签智能提取：支持正文内 #标签 语法与显式标签合并去重")
    void extractTagsMergesInlineHashtags() {
        String content = "今天读到一篇好文章 #机器学习 #RAG，值得沉淀。";
        List<String> tags = InboxService.extractTags(content, List.of("灵感", "机器学习"));
        assertThat(tags).containsExactlyInAnyOrder("灵感", "机器学习", "RAG");
    }

    @Test
    @DisplayName("快捷捕捉入库：保存为草稿并归入收件箱目录，触发向量重建与审计日志")
    void captureDraftNote() {
        ReflectionTestUtils.setField(inboxService, "jwtSecret", "test-secret-at-least-32-bytes");
        when(tagRepository.findByName(any())).thenAnswer(inv -> Optional.of(new Tag(inv.getArgument(0))));
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> {
            Note n = inv.getArgument(0);
            n.setId(99L);
            return n;
        });

        QuickCaptureRequest request = new QuickCaptureRequest(
                "## 构思一个新的搜索算法\n采用倒排加向量混合召回 #算法",
                "iOS快捷指令",
                List.of("技术思考")
        );

        QuickCaptureResponseDto res = inboxService.capture(request, "127.0.0.1");

        assertThat(res.id()).isEqualTo(99L);
        assertThat(res.title()).isEqualTo("构思一个新的搜索算法");
        assertThat(res.sourcePath()).startsWith("收件箱/");
        assertThat(res.tags()).contains("算法", "技术思考");

        verify(noteIndexService).reindex(99L);
        verify(auditService).recordAs(any(), any(), any(), any());
    }

    @Test
    @DisplayName("授权校验：匹配显式 Token 与派生 Token")
    void authorizationChecks() {
        ReflectionTestUtils.setField(inboxService, "configuredToken", "my-secret-token-123");
        assertThat(inboxService.isAuthorized(null, "my-secret-token-123", null, null)).isTrue();
        assertThat(inboxService.isAuthorized("Bearer my-secret-token-123", null, null, null)).isTrue();
        assertThat(inboxService.isAuthorized(null, null, "my-secret-token-123", null)).isTrue();
        assertThat(inboxService.isAuthorized(null, "wrong-token", null, null)).isFalse();
    }
}
