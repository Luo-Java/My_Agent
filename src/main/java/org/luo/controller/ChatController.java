package org.luo.controller;

import org.luo.dto.ChatRequest;
import org.luo.service.ChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Map;

/**
 * 对话接口（与会话管理业务分离）。
 * <p>
 * 只负责把用户消息交给 ChatService 调用大模型；会话的增删改查与历史读取见 ConversationController。
 * <p>
 * POST /api/chat/send   - 同步返回完整回复
 * POST /api/chat/stream - SSE 流式返回（前端逐字渲染）
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 同步对话：等待完整回复后一次性返回。
     *
     * @param request 会话 ID（可空，空则用默认会话）+ 用户消息
     * @return {@code {"content": "完整回复"}}
     */
    @PostMapping("/send")
    public Map<String, String> send(@RequestBody ChatRequest request) {
        String conversationId = resolveConversationId(request.conversationId());
        String reply = chatService.chat(conversationId, request.message());
        return Map.of("content", reply);
    }

    /**
     * 流式对话：以 SSE 逐 token 推送 AI 回复，前端逐字渲染。
     *
     * @param request 会话 ID（可空，空则用默认会话）+ 用户消息
     * @return SSE 事件流
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        String conversationId = resolveConversationId(request.conversationId());
        Flux<String> flux = chatService.stream(conversationId, request.message());

        flux.doOnNext(token -> {
                    try {
                        // 用 JSON 包裹 token：回复内容中的换行/空行会被 JSON 转义，
                        // 避免内容里的 \n\n 被前端误判为 SSE 事件边界导致数据错乱/丢字。
                        emitter.send(Map.of("token", token));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                })
                .doOnComplete(emitter::complete)
                .doOnError(emitter::completeWithError)
                .subscribe();

        return emitter;
    }

    /** 会话 ID 为空时回退到默认会话，避免前端首次对话未建会话。 */
    private String resolveConversationId(String id) {
        return (id == null || id.isBlank()) ? "default" : id;
    }
}
