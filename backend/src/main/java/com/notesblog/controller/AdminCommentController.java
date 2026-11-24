package com.notesblog.controller;

import com.notesblog.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.notesblog.domain.CommentStatus;
import com.notesblog.dto.AdminCommentDto;
import com.notesblog.service.CommentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评论管理（仅管理员，受 {@code /api} 默认鉴权保护）。
 *
 * <p>路径在 {@code /api/admin/**} 下：{@link com.notesblog.config.SecurityConfig}
 * 只对 {@code /api/blog/**} 和 {@code /api/auth/**} 放行，其余一律要认证，
 * 所以这里不加任何注解也是受保护的。
 *
 * <p>两个动作的区别：
 * <ul>
 *   <li>隐藏：{@code PATCH .../visibility}，软隐藏，内容留着，可撤销</li>
 *   <li>回复：{@code POST .../reply}，以「博主」身份公开回复，只允许回复顶层评论</li>
 *   <li>删除：{@code DELETE}，真删，不可撤销</li>
 * </ul>
 * 默认给的是「隐藏」这条更轻的路——误操作可以放回来，而删除没有回头路。
 */
@RestController
@RequestMapping("/api/admin/comments")
@RequiredArgsConstructor
public class AdminCommentController {

    private final CommentService commentService;

    @GetMapping
    public ApiResponse<Page<AdminCommentDto>> list(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(commentService.adminList(page, size));
    }

    /**
     * 改可见性。
     *
     * <p>请求体形如 {@code {"status":"HIDDEN"}}。用字符串而不是 boolean，
     * 是为了以后要加第三种状态（比如「置顶」）时不必改接口形状。
     */
    @PatchMapping("/{id}/visibility")
    public ApiResponse<AdminCommentDto> setVisibility(@PathVariable Long id,
                                                      @RequestBody VisibilityRequest request) {
        CommentStatus status;
        try {
            status = CommentStatus.valueOf(String.valueOf(request.status()).toUpperCase());
        } catch (IllegalArgumentException e) {
            // 用 400 而不是 500：这是调用方传错了值，不是服务端故障
            throw new IllegalArgumentException("status 只能是 VISIBLE 或 HIDDEN");
        }
        return ApiResponse.ok(commentService.setStatus(id, status));
    }

    /**
     * 以博主身份回复某条评论。
     *
     * <p>回复本身也是一条评论（{@code author_reply=true}），公开页上紧跟父评论显示，
     * 这样隐藏父评论时回复会一起消失，不需要另一套「回复」表去同步状态。
     */
    @PostMapping("/{id}/reply")
    public ApiResponse<AdminCommentDto> reply(@PathVariable Long id,
                                              @Valid @RequestBody ReplyRequest request) {
        return ApiResponse.ok(commentService.reply(id, request.content()));
    }

    /** 真删除。不可撤销。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        commentService.delete(id);
        return ApiResponse.ok();
    }

    public record VisibilityRequest(String status) {
    }

    public record ReplyRequest(
            @NotBlank(message = "回复内容不能为空")
            @Size(max = 2000, message = "回复不能超过 2000 个字符")
            String content) {
    }
}
