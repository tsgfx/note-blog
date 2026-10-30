package com.notesblog.controller;

import com.notesblog.common.ClientIp;
import com.notesblog.dto.AskRequest;
import com.notesblog.dto.SearchResultDto;
import com.notesblog.service.GuestQuotaService;
import com.notesblog.service.LlmException;
import com.notesblog.service.LlmStreamingClient;
import com.notesblog.service.SemanticSearchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 问答。管理员与游客走同一个入口，但检索范围与配额不同：
 *
 * <ul>
 *   <li><b>管理员</b>：全库检索（含草稿），不计数</li>
 *   <li><b>游客</b>：只用已发布分片，按 IP 每日配额</li>
 * </ul>
 *
 * <p><b>检索范围是安全边界，不是体验优化</b>：分片索引是按笔记建的，草稿也在里面。
 * 游客走 {@code searchPublished} 是唯一挡在草稿正文前面的一道。
 *
 * <p><b>为什么配额检查在这里而不是拦截器里：</b>拦截器（{@link com.notesblog.config.RateLimitInterceptor}）
 * 在参数校验之前跑，也不知道调用者是不是管理员——它的规则对全站一视同仁。
 * 这里要「管理员不计数、游客计数」，只能先认出身份。
 *
 * <p><b>为什么返回类型是 {@code Object}：</b>两个分支的媒体类型不同——
 * 放行时是 SSE 流，配额用完时是普通 JSON + 429。配额检查发生在开流之前，
 * 这时开一个空流再关，只会让前端把「次数用完」误当成「生成失败」。
 */
@Slf4j
@RestController
@RequestMapping("/api/ask")
@RequiredArgsConstructor
public class AskController {

    private final SemanticSearchService searchService;
    private final LlmStreamingClient llmClient;
    private final GuestQuotaService guestQuota;

    @PostMapping
    public Object ask(@Valid @RequestBody AskRequest request,
                      HttpServletRequest http,
                      Authentication authentication) {
        boolean admin = isAdmin(authentication);

        // ── 游客：先占配额，再检索。反过来会让被拒的请求白烧一次 embedding
        int remaining = 0;
        if (!admin) {
            String ip = ClientIp.of(http);
            GuestQuotaService.Decision decision = guestQuota.consume(ip);
            if (!decision.allowed()) {
                log.info("guest_quota_exhausted ip={} retryAfter={}s", ip, decision.retryAfterSeconds());
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "code", 429,
                                "message", "今日提问次数已用完，明天再来",
                                "data", Map.of(
                                        "remaining", 0,
                                        "resetInSeconds", decision.retryAfterSeconds())));
            }
            remaining = decision.remaining();
        }

        List<SearchResultDto> sources = admin
                ? searchService.search(request.question(), 8)
                : searchService.searchPublished(request.question(), 8);

        SseEmitter emitter = new SseEmitter(180_000L);
        send(emitter, "sources", Map.of("sources", sources));
        // 游客带一句剩余次数，前端据此显示与本地拦截；管理员不需要
        if (!admin) {
            send(emitter, "quota", Map.of("remaining", remaining));
        }

        int finalRemaining = remaining;
        CompletableFuture.runAsync(() -> {
            try {
                if (sources.isEmpty()) {
                    send(emitter, "delta", Map.of("text", admin
                            ? "笔记库中未检索到与该问题相关的内容。"
                            : "笔记库中未检索到与该问题相关的公开内容。"));
                    send(emitter, "done", Map.of("remaining", admin ? -1 : finalRemaining));
                    emitter.complete();
                    return;
                }
                String context = sources.stream()
                        .map(s -> "[来源: " + s.title()
                                + (s.heading() == null ? "" : " / " + s.heading()) + "]\n"
                                + truncate(s.content(), 1500))
                        .reduce((a, b) -> a + "\n\n" + b).orElse("");
                String system = "你是个人知识库助手。严格依据提供的笔记片段回答。"
                        + "如果片段没有答案，请明确说明。用中文回答，不要输出思考过程。";
                String user = "笔记片段：\n" + context + "\n\n问题：" + request.question();
                llmClient.stream(system, user, piece -> send(emitter, "delta", Map.of("text", piece)));
                send(emitter, "done", Map.of("remaining", admin ? -1 : finalRemaining));
                emitter.complete();
            } catch (LlmException e) {
                log.warn("ask_failed question={} kind={} msg={}", request.question(), e.kind(), e.getMessage());
                send(emitter, "error", Map.of("message", e.getMessage(), "kind", e.kind().name()));
                emitter.complete();
            } catch (Exception e) {
                log.error("ask_failed question={}", request.question(), e);
                send(emitter, "error", Map.of("message", "问答服务暂时不可用，请稍后重试"));
                emitter.complete();
            }
        });
        return emitter;
    }

    /**
     * 管理员判定。
     *
     * <p>只认 ROLE_ADMIN：{@code JwtAuthFilter} 从令牌里取角色，
     * 令牌无效时 SecurityContext 是空的，{@code authentication} 为 null 或匿名。
     */
    private static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static void send(SseEmitter emitter, String type, Object data) {
        try {
            emitter.send(SseEmitter.event().name(type)
                    .data(Map.of("type", type, "data", data)));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
