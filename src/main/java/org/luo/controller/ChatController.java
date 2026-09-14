package org.luo.controller;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.luo.dto.ChatAttachment;
import org.luo.dto.ChatRequest;
import org.luo.dto.StreamEvent;
import org.luo.service.ChatService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话接口（与会话管理业务分离，后者见 ConversationController）。
 * <p>
 * POST /api/chat/send   - 同步返回完整回复；POST /api/chat/stream - SSE 流式返回。
 * <p>
 * <b>传输层兜底</b>（与业务无关，纯防连接被静默挂死）：有限超时（{@code app.sse.timeout-seconds}，默认 300s，
 * 不用 {@code 0L} 永不超时——上游卡死会让连接与异步线程永久泄漏）；心跳（{@code app.sse.heartbeat-seconds}，
 * 默认 15s）——前置链（路由→参数抽取→改写→检索）期间无字节流出，反向代理会当空闲切断长连接，
 * 周期发 {@link StreamEvent#ping()} 保活。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    /** SSE 连接最长存活时间（秒）。必须有限，默认 300s，足够覆盖最长的规划 + 多轮工具调用。 */
    private final long sseTimeoutSeconds;

    /** 心跳间隔（秒），{@code <=0} 表示关闭心跳。 */
    private final long heartbeatSeconds;

    /**
     * 心跳专用调度器（bean {@code sseHeartbeatScheduler}）。
     * <p>
     * 刻意<b>不复用</b> Reactor {@code Schedulers.parallel()}：心跳要调 {@code SseEmitter.send()}，
     * 对慢客户端是<b>阻塞</b>调用；而 {@code parallel()} 同时被打字机与 Reactor 内部使用，
     * 几个卡住的连接就能把它占满、连带全站心跳停摆。用独立调度器隔离风险（见 ExecutorConfig）。
     */
    private final ScheduledExecutorService heartbeatScheduler;

    public ChatController(ChatService chatService,
                          @Value("${app.sse.timeout-seconds:300}") long sseTimeoutSeconds,
                          @Value("${app.sse.heartbeat-seconds:15}") long heartbeatSeconds,
                          @Qualifier("sseHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler) {
        this.chatService = chatService;
        this.sseTimeoutSeconds = sseTimeoutSeconds;
        this.heartbeatSeconds = heartbeatSeconds;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    /** 同步对话：等待完整回复后一次性返回 {@code {"content": "..."}}。 */
    @PostMapping("/send")
    public Map<String, String> send(@RequestBody ChatRequest request) {
        String conversationId = resolveConversationId(request.conversationId());
        // 一次遍历同时抽出「当轮材料」与「展示元数据」：纯提问走记忆/路由，两者分别注入 system / 落库，互不影响
        AttachmentBundle bundle = extractAttachments(request.attachments());
        String reply = chatService.chat(conversationId, request.message(),
                bundle.material(), bundle.metaJson(), request.planner());
        // Map.of 拒绝 null 值：出口再兜一道，避免上游漏判空把一次 200 变成 500
        return Map.of("content", reply == null ? "" : reply);
    }

    /**
     * 流式对话：以 SSE 推送事件，前端逐字渲染。每条事件 data 为 JSON，字段名即事件类型：
     * {@code {"token":"..."}} 正文分片（唯一写入会话记忆的内容）；{@code {"progress":"..."}} 执行过程
     * （规划步骤与进展，不写入记忆、刷新后消失）。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        // 有限超时（而非 0L=永不超时）：上游卡死时连接与异步线程能自动释放，不会永久泄漏
        SseEmitter emitter = new SseEmitter(sseTimeoutSeconds > 0 ? sseTimeoutSeconds * 1000L : 0L);
        String conversationId = resolveConversationId(request.conversationId());
        // 一次遍历同时抽出「当轮材料」与「展示元数据」：纯提问走记忆/路由，两者分别注入 system / 落库，互不影响
        AttachmentBundle bundle = extractAttachments(request.attachments());
        Flux<StreamEvent> flux = chatService.stream(conversationId, request.message(),
                bundle.material(), bundle.metaJson(), request.planner());

        // 流结束标记：心跳任务据此自停；同时保证「流结束后不再往已完成的 emitter 写」
        AtomicBoolean finished = new AtomicBoolean(false);

        Disposable subscription = flux.doOnNext(event -> {
                    try {
                        // 用 JSON 包裹文本：内容里的换行会被转义，避免 \n\n 被前端误判为 SSE 事件边界而丢字。
                        // 字段名即事件类型（token / progress / error），前端据此决定渲染到正文还是执行过程区。
                        emitter.send(Map.of(event.type(), event.text() == null ? "" : event.text()));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                })
                .doOnComplete(() -> {
                    finished.set(true);
                    emitter.complete();
                })
                .doOnError(e -> {
                    finished.set(true);
                    emitter.completeWithError(e);
                })
                .subscribe();

        // 心跳：前置链期间无字节流出，易被反向代理判为空闲切断。用独立周期任务推 ping 保活，与业务流完全解耦
        // （不动流的终止语义——若改用 Flux.merge/interval，无限流会让 emitter 永不 complete）。
        // 调度器为独立线程池（非 Reactor parallel），且用 scheduleWithFixedDelay：下一次从上次执行完成才开始计时，
        // 慢客户端只让该连接的心跳顺延，不在身上堆任务。
        ScheduledFuture<?> heartbeat = heartbeatSeconds > 0
                ? heartbeatScheduler.scheduleWithFixedDelay(() -> {
                    if (finished.get()) {
                        return;
                    }
                    try {
                        emitter.send(Map.of(StreamEvent.TYPE_PING, "1"));
                    } catch (Exception e) {
                        // 客户端已断开：连接即将因 onCompletion 被清理，无需额外处理
                        finished.set(true);
                    }
                }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS)
                : null;

        // 客户端断开（或超时）时取消订阅与心跳：停止后续推送与延迟任务，避免 LLM 侧已排队的调用继续空烧 token。
        Runnable cleanup = () -> {
            finished.set(true);
            subscription.dispose();
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        return emitter;
    }

    /**
     * 一次性抽取附件的两类产物（单次遍历）：
     * <ul>
     *   <li>{@code material} —— 解析文本（图片 caption / 文档正文），由 ChatComposer 注入本轮 system prompt，
     *       <b>仅当轮可见、不进会话记忆</b>；</li>
     *   <li>{@code metaJson} —— 展示元数据 JSON（type/filename/storedName/size），写独立列
     *       {@code chat_message.attachments_json}，仅供历史回看，<b>不含正文、不进 LLM</b>。</li>
     * </ul>
     * 无附件时两者均为空串。
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
