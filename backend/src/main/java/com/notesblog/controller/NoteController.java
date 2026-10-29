package com.notesblog.controller;

import com.notesblog.common.ApiResponse;
import com.notesblog.domain.NoteStatus;
import com.notesblog.dto.BatchIdsRequest;
import com.notesblog.dto.BatchResultDto;
import com.notesblog.dto.FolderNodeDto;
import com.notesblog.dto.GenerateMetaBatchRequest;
import com.notesblog.dto.LibraryCountDto;
import com.notesblog.dto.MetaBatchReportDto;
import com.notesblog.dto.NoteDetailDto;
import com.notesblog.dto.NoteSummaryDto;
import com.notesblog.dto.NoteTagCountDto;
import com.notesblog.dto.CreateNoteRequest;
import com.notesblog.dto.PreflightReport;
import com.notesblog.dto.RelatedNoteDto;
import com.notesblog.dto.ReindexReportDto;
import com.notesblog.dto.UpdateNoteRequest;
import jakarta.validation.Valid;
import com.notesblog.service.NoteMetaBatchService;
import com.notesblog.service.NoteMetaService;
import com.notesblog.service.NoteService;
import com.notesblog.service.PublishPreflightService;
import com.notesblog.service.RelatedNoteService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notes")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService noteService;
    private final NoteMetaService noteMetaService;
    private final NoteMetaBatchService noteMetaBatchService;
    private final RelatedNoteService relatedNoteService;
    private final PublishPreflightService publishPreflightService;

    /** 单页上限，与前端列表页的每页条数解耦，防止一次拉走整库。 */
    private static final int MAX_PAGE_SIZE = 100;

    @GetMapping
    public ApiResponse<Page<NoteSummaryDto>> list(
            @RequestParam(required = false) NoteStatus status,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String library,
            @RequestParam(required = false, name = "q") String keyword,
            @RequestParam(required = false) List<String> tag,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // 参数来自查询串，别让 page=-1 / size=0 把 PageRequest 抛成 500
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        // library 是 path 的旧名字（只支持第一段），保留是为了旧的链接和书签不断
        String scope = (path != null && !path.isBlank()) ? path : library;
        return ApiResponse.ok(noteService.list(status, scope, keyword, tag, pageable));
    }

    /** 左侧「库」导航：库名与篇数。字面量路径，不会被 /{slug} 抢走。 */
    @GetMapping("/libraries")
    public ApiResponse<List<LibraryCountDto>> libraries() {
        return ApiResponse.ok(noteService.libraries());
    }

    /**
     * 左侧「目录」导航：多级目录树，每层带篇数。
     *
     * <p>和 {@code /libraries} 的区别：那个只有第一段（单级），这个按 sourcePath
     * 逐段展开成树，前端可以折叠。{@code status} 让树上的数字跟着「全部/已发布/草稿」变。
     */
    @GetMapping("/folders")
    public ApiResponse<List<FolderNodeDto>> folders(@RequestParam(required = false) NoteStatus status) {
        return ApiResponse.ok(noteService.folders(status));
    }

    /**
     * 左侧「分类」导航：标签与篇数。
     *
     * <p>支持按 {@code path}（或旧名 {@code library}）收窄：选中某个目录时只显示这个目录里
     * 出现过的标签，否则在 c++ 里看到「宋词」会很迷惑。
     */
    @GetMapping("/tags")
    public ApiResponse<List<NoteTagCountDto>> tags(
            @RequestParam(required = false) NoteStatus status,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String library) {
        String scope = (path != null && !path.isBlank()) ? path : library;
        return ApiResponse.ok(noteService.tagFacets(status, scope));
    }

    @GetMapping("/{slug}")
    public ApiResponse<NoteDetailDto> detail(@PathVariable String slug) {
        return ApiResponse.ok(noteService.getBySlug(slug));
    }

    /**
     * 相关笔记推荐，复用分片向量、不调 embedding 接口。
     * 笔记还没建索引时返回空数组，前端不渲染这一块。
     */
    @GetMapping("/{id}/related")
    public ApiResponse<List<RelatedNoteDto>> related(
            @PathVariable Long id, @RequestParam(required = false) Integer limit) {
        relatedNoteService.requireExists(id);
        return ApiResponse.ok(relatedNoteService.related(id, limit));
    }

    @PostMapping
    public ApiResponse<NoteDetailDto> create(@Valid @RequestBody CreateNoteRequest request) {
        return ApiResponse.ok(noteService.create(request));
    }

    @PatchMapping("/{id}")
    public ApiResponse<NoteDetailDto> update(
            @PathVariable Long id, @Valid @RequestBody UpdateNoteRequest request) {
        return ApiResponse.ok(noteService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        noteService.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<NoteDetailDto> publish(@PathVariable Long id) {
        return ApiResponse.ok(noteService.publish(id));
    }

    /**
     * 自动生成摘要与标签。默认只填空白字段（不覆盖手写内容）；
     * {@code force=true} 时两者都重写（编辑页「重新生成」按钮）。
     */
    @PostMapping("/{id}/generate-meta")
    public ApiResponse<NoteDetailDto> generateMeta(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean force) {
        return ApiResponse.ok(noteMetaService.generate(id, force));
    }

    /**
     * 批量补摘要/标签。
     *
     * <p>{@code ids} 为空即「库里缺什么补什么」，用于一次性补齐历史笔记；
     * 单批最多 10 篇，前端循环调用直到 {@code hasMore} 为假。
     * 每篇一个独立事务，所以允许部分成功——失败的 id 会出现在 {@code failures} 里，可直接重试。
     */
    @PostMapping("/generate-meta/batch")
    public ApiResponse<MetaBatchReportDto> generateMetaBatch(
            @Valid @RequestBody GenerateMetaBatchRequest request) {
        return ApiResponse.ok(noteMetaBatchService.generateBatch(request));
    }

    @PostMapping("/{id}/unpublish")
    public ApiResponse<NoteDetailDto> unpublish(@PathVariable Long id) {
        return ApiResponse.ok(noteService.unpublish(id));
    }

    /**
     * 批量发布前的预检：告诉调用方这批里哪些发不出去（BLOCK）、
     * 哪些发得出去但有风险（WARN：疑似凭据/隐私、没摘要、没标签）。
     * 只读操作，不改任何状态。
     */
    @PostMapping("/preflight")
    public ApiResponse<PreflightReport> preflight(@Valid @RequestBody BatchIdsRequest request) {
        return ApiResponse.ok(publishPreflightService.check(request.ids()));
    }

    @PostMapping("/reindex")
    public ApiResponse<ReindexReportDto> reindex() {
        int queued = noteService.reindexMissing();
        // 带上余量：队列只有 20 个槽位，整库缺分片时前端要循环点，得知道还剩多少
        return ApiResponse.ok(new ReindexReportDto(queued, noteService.countMissingChunks()));
    }

    /**
     * 批量发布。路径里有两段字面量，PathPattern 的匹配优先级里字面量段高于
     * {@code {id}} 变量段，所以不会被 {@code /{id}/publish} 抢走。
     */
    @PostMapping("/batch/publish")
    public ApiResponse<BatchResultDto> publishBatch(@Valid @RequestBody BatchIdsRequest request) {
        return ApiResponse.ok(noteService.publishBatch(request.ids()));
    }

    @PostMapping("/batch/unpublish")
    public ApiResponse<BatchResultDto> unpublishBatch(@Valid @RequestBody BatchIdsRequest request) {
        return ApiResponse.ok(noteService.unpublishBatch(request.ids()));
    }
}
