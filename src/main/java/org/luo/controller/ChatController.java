package org.luo.controller;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.luo.dto.ChatAttachment;
import org.luo.dto.ChatRequest;
import org.luo.dto.StreamEvent;
import org.luo.service.ChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.luo.agent.handler.RoundHandler;
import org.luo.chat.ChatComposer;

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
        // 一次遍历同时抽出「当轮材料」与「展示元数据」：纯提问走记忆/路由，两者分别注入 system / 落库，互不影响
        AttachmentBundle bundle = extractAttachments(request.attachments());
        String reply = chatService.chat(conversationId, request.message(),
                bundle.material(), bundle.metaJson(), request.planner());
        return Map.of("content", reply);
    }

    /**
     * 流式对话：以 SSE 推送事件，前端逐字渲染。
     * <p>
     * 每条事件的 data 都是 JSON，字段名即事件类型：
     * <ul>
     *   <li>{@code {"token":"..."}} —— 正文分片，前端累加进消息气泡，且是唯一写入会话记忆的内容；</li>
     *   <li>{@code {"progress":"..."}} —— 执行过程（规划步骤、每步进展），前端单独展示为「执行过程」，
     *       不属于消息正文、不写入会话记忆，刷新会话后不再出现。</li>
     * </ul>
     *
     * @param request 会话 ID（可空，空则用默认会话）+ 用户消息（可带 attachments）
     * @return SSE 事件流
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        String conversationId = resolveConversationId(request.conversationId());
        // 一次遍历同时抽出「当轮材料」与「展示元数据」：纯提问走记忆/路由，两者分别注入 system / 落库，互不影响
        AttachmentBundle bundle = extractAttachments(request.attachments());
        Flux<StreamEvent> flux = chatService.stream(conversationId, request.message(),
                bundle.material(), bundle.metaJson(), request.planner());

        Disposable subscription = flux.doOnNext(event -> {
                    try {
                        // 用 JSON 包裹文本：内容中的换行/空行会被 JSON 转义，
                        // 避免内容里的 \n\n 被前端误判为 SSE 事件边界导致数据错乱/丢字。
                        // 字段名用事件类型（token / progress / error），前端据此决定渲染到正文还是执行过程区。
                        emitter.send(Map.of(event.type(), event.text() == null ? "" : event.text()));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                })
                .doOnComplete(emitter::complete)
                .doOnError(emitter::completeWithError)
                .subscribe();

        // 客户端断开（或超时）时取消订阅：停止后续事件推送与延迟任务，
        // 避免 LLM 侧已排队的调用继续空烧 token（SSE 连接关闭会触发 onCompletion）。
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        return emitter;
    }

    /**
     * 一次性抽取附件的两类产物（单次遍历，避免同一列表被反复扫描）：
     * <ul>
     *   <li>{@code material} —— 解析文本块（图片 caption / 文档正文），由 {@code ChatComposer} 注入本轮
     *       system prompt，<b>仅当轮可见、不进会话记忆</b>（记忆 Advisor 只持久化 {@code .user()} 的纯提问）；</li>
     *   <li>{@code metaJson} —— 展示元数据 JSON（type/filename/storedName/size），写入独立列
     *       {@code chat_message.attachments_json}，仅供历史回看渲染缩略图 / 下载，<b>不含正文、不进 LLM</b>。</li>
     * </ul>
     * 无附件时两者均为空串（调用方据此跳过注入与落库）。
     */
    private AttachmentBundle extractAttachments(List<ChatAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return AttachmentBundle.EMPTY;
        StringBuilder material = new StringBuilder(512);
        JSONArray meta = new JSONArray();
        for (int i = 0; i < attachments.size(); i++) {
            ChatAttachment a = attachments.get(i);
            // 产物 1：当轮材料（逐条带序号与文件名标注，便于模型区分多份附件）
            material.append(attachmentLabel(a.type())).append(i + 1);
            if (a.filename() != null && !a.filename().isBlank()) {
                material.append("（").append(a.filename()).append("）");
            }
            material.append("：\n").append(a.content() == null ? "" : a.content()).append("\n\n");
            // 产物 2：展示元数据（不含正文，仅历史渲染所需的最小字段）
            JSONObject o = new JSONObject();
            o.set("type", a.type() == null ? "file" : a.type());
            o.set("filename", a.filename());
            o.set("storedName", a.storedName());
            o.set("size", a.size());
            meta.add(o);
        }
        return new AttachmentBundle(material.toString().strip(), meta.toString());
    }

    /** 附件抽取结果：{@code material}=当轮注入模型的解析文本；{@code metaJson}=落库供历史回看的展示元数据。 */
    private record AttachmentBundle(String material, String metaJson) {
        static final AttachmentBundle EMPTY = new AttachmentBundle("", "");
    }

    /** 附件类型 → 中文标签（图片 / 文档 / 文件）。 */
    private static String attachmentLabel(String type) {
        return switch (type == null ? "" : type) {
            case "image" -> "图片";
            case "text" -> "文档";
            default -> "文件";
        };
    }

    /** 会话 ID 为空时回退到默认会话，避免前端首次对话未建会话。 */
    private String resolveConversationId(String id) {
        return (id == null || id.isBlank()) ? "default" : id;
    }
}