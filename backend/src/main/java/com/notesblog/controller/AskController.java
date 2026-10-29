package com.notesblog.controller;

import com.notesblog.dto.AskRequest;
import com.notesblog.dto.SearchResultDto;
import com.notesblog.service.LlmException;
import com.notesblog.service.LlmStreamingClient;
import com.notesblog.service.SemanticSearchService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api/ask")
@RequiredArgsConstructor
public class AskController {

    private final SemanticSearchService searchService;
    private final LlmStreamingClient llmClient;

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@Valid @RequestBody AskRequest request) {
        List<SearchResultDto> sources = searchService.search(request.question(), 8);
        SseEmitter emitter = new SseEmitter(180_000L);
        send(emitter, "sources", Map.of("sources", sources));

        CompletableFuture.runAsync(() -> {
            try {
                String context = sources.stream()
                        .map(s -> "[来源: " + s.title()
                                + (s.heading() == null ? "" : " / " + s.heading()) + "]\n"
                                + truncate(s.content(), 1500))
                        .reduce((a, b) -> a + "\n\n" + b).orElse("");
                String system = "你是个人知识库助手。严格依据提供的笔记片段回答。"
                        + "如果片段没有答案，请明确说明。用中文回答，不要输出思考过程。";
                String user = "笔记片段：\n" + context + "\n\n问题：" + request.question();
                llmClient.stream(system, user, piece -> send(emitter, "delta", Map.of("text", piece)));
                send(emitter, "done", Map.of());
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
