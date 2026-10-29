package com.notesblog.controller;

import com.notesblog.common.ApiResponse;
import com.notesblog.common.ClientIp;
import com.notesblog.dto.CommentDto;
import com.notesblog.dto.CreateCommentRequest;
import com.notesblog.service.CommentService;
import com.notesblog.service.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开评论接口，挂在 {@code /api/blog/**} 下所以天然 permitAll，不需要改 SecurityConfig。
 *
 * <p><b>防刷三层，成本递增：</b>
 * <ol>
 *   <li>蜜罐字段（{@link CreateCommentRequest#website}）——零成本，挡掉绝大多数脚本</li>
 *   <li>短时限流：每 IP 每分钟 1 条——挡住「蜜罐没中但还是狂发」的</li>
 *   <li>每日上限：每 IP 每天 10 条——挡住慢速渗透</li>
 * </ol>
 * 三层都在这里做，不进 {@code RateLimitInterceptor}：那个只支持按路径的固定规则，
 * 而这里要「每分钟」和「每天」两种窗口并存。
 */
@RestController
@RequestMapping("/api/blog")
@RequiredArgsConstructor
public class CommentController {

    /** 每分钟每条 IP 一条：正常人写完一条评论不会立刻再写一条。 */
    private static final int PER_MINUTE = 1;
    /** 每天每条 IP 十条，含被隐藏的。 */
    private static final int PER_DAY = 10;

    private final CommentService commentService;
    private final RateLimiter rateLimiter;

    /**
     * 列出某篇文章的公开评论。
     *
     * <p>路径用 {@code /posts/{slug}/comments} 而不是 {@code /comments?note=}：
     * slug 已在公开接口里用惯，且这样一眼能看出「评论属于某篇文章」。
     */
    @GetMapping("/posts/{slug}/comments")
    public ApiResponse<Page<CommentDto>> list(@PathVariable String slug,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(commentService.publicList(slug, page, size));
    }

    /**
     * 游客发评论。
     *
     * @return 成功 200 + 评论内容；蜜罐命中也是 200（静默丢弃，见 CommentService）
     */
    @PostMapping("/posts/{slug}/comments")
    public ResponseEntity<ApiResponse<CommentDto>> create(@PathVariable String slug,
                                                          @Valid @RequestBody CreateCommentRequest request,
                                                          HttpServletRequest http) {
        String ip = ClientIp.of(http);

        // 蜜罐先判：命中了直接静默成功，不占限流额度，也不落库。
        // 报错等于告诉对方「这个字段是陷阱，下次别填」。
        if (request.honeypotFilled()) {
            commentService.create(slug, request, ip);
            return ResponseEntity.ok(ApiResponse.ok(null));
        }

        RateLimiter.Decision minute = rateLimiter.acquire(
                "comment:1m:" + ip, PER_MINUTE, Duration.ofMinutes(1));
        if (!minute.allowed()) {
            return tooMany("发得太快了，请稍后再试", minute.retryAfterSeconds());
        }
        RateLimiter.Decision day = rateLimiter.acquire(
                "comment:1d:" + ip, PER_DAY, Duration.ofDays(1));
        if (!day.allowed()) {
            return tooMany("今天的评论次数已用完", day.retryAfterSeconds());
        }

        CommentDto created = commentService.create(slug, request, ip);
        return ResponseEntity.ok(ApiResponse.ok(created));
    }

    private static ResponseEntity<ApiResponse<CommentDto>> tooMany(String message, long retryAfterSeconds) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(429, message));
    }
}
