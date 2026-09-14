package org.luo.service;

import lombok.extern.slf4j.Slf4j;
import org.luo.dto.KbCitation;
import org.luo.dto.StreamEvent;
import org.luo.entity.Conversation;
import org.luo.agent.handler.AgentRoundHandler;
import org.luo.agent.handler.PlannerRoundHandler;
import org.luo.agent.handler.RoundHandler;
import org.luo.agent.handler.RoundResult;
import org.luo.trace.RoundTrace;
import org.luo.trace.TraceService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.luo.agent.MemoryMergeService;
import org.luo.agent.PromptService;
import org.luo.chat.ChatComposer;
import org.luo.memory.DbChatMemory;

/**
 * 对话编排服务（门面）：一轮对话统一走 {@link #runRound}，按会话形态选 {@link RoundHandler} 策略
 * （{@link AgentRoundHandler} / {@link PlannerRoundHandler}）执行，产出 {@link RoundResult} 后统一输出收尾。
 * 请求组装、摘要合并、持久化、追踪分别委托 {@link ChatComposer} / {@link MemoryMergeService} /
 * {@link ConversationService} / {@link TraceService}，本类只做编排。
 * <p>
 * <b>收尾顺序（勿乱）</b>：推回复 → 推引用 → 落库附件/引用 → 异步落库追踪 → 异步合并记忆。
 * 一切旁路数据都排在用户看到答案<b>之后</b>。
 */
@Slf4j
@Service
public class ChatService {

    /** 本轮形态：普通/智能体对话。 */
    private static final String MODE_AGENT = "agent";
    /** 本轮形态：规划模式。 */
    private static final String MODE_PLANNER = "planner";

    /** 「打字机」帧间隔（毫秒），与 {@code stream} 的 {@code delayElements} 共用。 */
    private static final long TYPING_FRAME_MS = 15;

    /** 「打字机」总时长上限（毫秒）：分片按长度自适应（见 {@link #chunkSize}），避免延迟随答案长度线性累加。 */
    private static final long TYPING_MAX_MS = 2_000;

    /** 分片下限（字符）：短答案保持 4 字一片的逐字观感，不做自适应放大。 */
    private static final int TYPING_MIN_CHUNK = 4;

    private final ConversationService conversationService;
    private final MemoryMergeService memoryMergeService;
    private final AgentRoundHandler agentRoundHandler;
    private final PlannerRoundHandler plannerRoundHandler;
    private final TraceService traceService;

    public ChatService(ConversationService conversationService,
                       MemoryMergeService memoryMergeService,
                       AgentRoundHandler agentRoundHandler,
                       PlannerRoundHandler plannerRoundHandler,
                       TraceService traceService) {
        this.conversationService = conversationService;
        this.memoryMergeService = memoryMergeService;
        this.agentRoundHandler = agentRoundHandler;
        this.plannerRoundHandler = plannerRoundHandler;
        this.traceService = traceService;
    }

    /** 同步对话（无附件）：历史注入与本轮落库由 MessageChatMemoryAdvisor 自动完成。 */
    public String chat(String conversationId, String message) {
        return chat(conversationId, message, "", "", null);
    }

    /** 同步对话：{@code planner} 非空时覆盖并写回会话形态，null 则跟随会话默认。 */
    public String chat(String conversationId, String message, Boolean planner) {
        return chat(conversationId, message, "", "", planner);
    }

    /**
     * 同步对话（带附件）。{@code material} 仅当轮注入模型；{@code attachmentsJson} 仅落库供历史回看，
     * 两者都不进记忆、不占 token。
     */
    public String chat(String conversationId, String message, String material, String attachmentsJson,
                       Boolean planner) {
        log.info("同步对话：会话={}，planner={}，有附件={}", conversationId, planner,
                material != null && !material.isBlank());
        Conversation conv = conversationService.ensureConversation(conversationId);
        // 落库前水位：附件/引用只写本轮新增消息，避免误挂历史消息
        Long watermark = needsWatermark(conv, attachmentsJson)
                ? conversationService.maxMessageId(conversationId) : null;
        RoundTrace trace = startTrace(conversationId, message);
        RoundResult out;
        try {
            // 同步接口无事件通道：执行过程只记日志，进度展示仅 stream 支持
            out = runRound(conv, conversationId, message, material, NO_PROGRESS, planner, trace);
        } catch (RuntimeException | Error e) {
            // 异常路径也要留下追踪（最需要排查的恰恰是失败轮次），随后原样抛出交由上层错误处理
            trace.markError(e.getMessage());
            traceService.saveAsync(trace);
            throw e;
        }
        persistAttachments(conversationId, watermark, attachmentsJson);
        persistCitations(conversationId, watermark, out.citations());
        log.info("同步对话完成：回复长度={}，引用={}", out.reply() != null ? out.reply().length() : 0,
                out.citations().size());
        afterReply(conversationId, message);
        traceService.saveAsync(trace);
        return out.reply();
    }

    /** 流式对话（无附件）：事件类型见 {@link StreamEvent}（{@code progress} 仅展示不进记忆，{@code token} 唯一进记忆）。 */
    public Flux<StreamEvent> stream(String conversationId, String message) {
        return stream(conversationId, message, "", "", null);
    }

    /** 流式对话：{@code planner} 非空时覆盖并写回会话形态。数据优先于记忆（见 {@link #afterReply}）。 */
    public Flux<StreamEvent> stream(String conversationId, String message, Boolean planner) {
        return stream(conversationId, message, "", "", planner);
    }

    /**
     * 流式对话（带附件），执行体见 {@link #doStream}：跑在 {@link Schedulers#boundedElastic()} 上，
     * HTTP 线程立即返回、SSE 先建立，进度才能实时推送。
     */
    public Flux<StreamEvent> stream(String conversationId, String message, String material, String attachmentsJson,
                                    Boolean planner) {
        return Flux.<StreamEvent>create(sink -> {
                    try {
                        doStream(conversationId, message, material, attachmentsJson, sink, planner);
                    } catch (Exception e) {
                        log.error("流式对话失败：会话={}，错误={}", conversationId, e.getMessage(), e);
                        sink.next(StreamEvent.error("对话出错：" + e.getMessage()));
                    } finally {
                        sink.complete();
                    }
                }, FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.boundedElastic())
                // 帧间隔；分片大小由 emitChunks 自适应，总时长有上界（TYPING_MAX_MS）
                .delayElements(Duration.ofMillis(TYPING_FRAME_MS));
    }

    /** 流式执行体（跑在弹性线程上，可阻塞调用 LLM），结果经 sink 推送。 */
    private void doStream(String conversationId, String message, String material, String attachmentsJson,
                          FluxSink<StreamEvent> sink, Boolean planner) {
        log.info("流式对话：会话={}，planner={}，有附件={}", conversationId, planner,
                material != null && !material.isBlank());
        Conversation conv = conversationService.ensureConversation(conversationId);
        // 落库前水位：附件/引用只写本轮新增消息，避免误挂历史消息
        Long watermark = needsWatermark(conv, attachmentsJson)
                ? conversationService.maxMessageId(conversationId) : null;
        Consumer<String> progress = text -> sink.next(StreamEvent.progress(text));
        RoundTrace trace = startTrace(conversationId, message);
        RoundResult out;
        try {
            // 追问整段推、正式回答切片模拟打字机；progress 只展示不进记忆
            out = runRound(conv, conversationId, message, material, progress, planner, trace);
        } catch (RuntimeException | Error e) {
            trace.markError(e.getMessage());
            traceService.saveAsync(trace);
            throw e;
        }
        if (out.clarified()) {
            sink.next(StreamEvent.token(out.reply()));
        } else {
            emitChunks(sink, out.reply());
        }
        // 引用：正文推完后单独发一次，前端渲染 [n] 角标与来源列表
        String citationsJson = KbCitation.toJson(out.citations());
        if (!citationsJson.isEmpty()) {
            sink.next(StreamEvent.citations(citationsJson));
        }
        // 附件元数据 / 引用写入本轮消息（仅历史回看，不进 LLM 上下文）
        persistAttachments(conversationId, watermark, attachmentsJson);
        persistCitations(conversationId, watermark, out.citations());
        // 数据优先于记忆：正文推完再收尾（见 afterReply）
        afterReply(conversationId, message);
        traceService.saveAsync(trace);
    }

    /** 是否有附件展示元数据（空串/null 视为无）。 */
    private static boolean hasAttachments(String attachmentsJson) {
        return attachmentsJson != null && !attachmentsJson.isBlank();
    }

    /** 本轮是否需要「落库前水位」：只有会后补写的附件元数据与 RAG 引用才需要（后者以 {@code ragEnabled} 廉价预判）。 */
    private static boolean needsWatermark(Conversation conv, String attachmentsJson) {
        return hasAttachments(attachmentsJson) || (conv != null && Boolean.TRUE.equals(conv.getRagEnabled()));
    }

    /** 建本轮追踪上下文（纯内存对象，失败不影响对话）。 */
    private static RoundTrace startTrace(String conversationId, String message) {
        return new RoundTrace(conversationId, message);
    }

    /** 附件元数据写入本轮用户消息（仅历史回看，失败只记日志）。 */
    private void persistAttachments(String conversationId, Long watermark, String attachmentsJson) {
        if (!hasAttachments(attachmentsJson)) return;
        try {
            conversationService.attachToLatestUserMessage(conversationId, watermark, attachmentsJson);
        } catch (Exception e) {
            log.error("附件元数据写入失败：会话={}", conversationId, e);
        }
    }

    /** RAG 引用写入本轮助手消息（仅前端溯源展示，失败只记日志）。 */
    private void persistCitations(String conversationId, Long watermark, List<KbCitation> citations) {
        String json = KbCitation.toJson(citations);
        if (json.isEmpty()) return;
        try {
            conversationService.attachCitationsToLatestAssistantMessage(conversationId, watermark, json);
        } catch (Exception e) {
            log.error("引用来源写入失败：会话={}", conversationId, e);
        }
    }

    /**
     * 统一编排入口：按会话形态选策略执行。新增会话形态只需新增 {@link RoundHandler} 实现。
     * <b>planner 与 agentId 互斥</b>——绑定智能体的会话不持久化规划形态（仅本轮临时生效）。
     */
    private RoundResult runRound(Conversation conv, String conversationId, String message, String material,
                                Consumer<String> progress, Boolean planner, RoundTrace trace) {
        // planner 与 agentId 互斥：绑定智能体的会话不持久化规划形态（与 PUT /planner 防御校验一致）
        if (planner != null && conv != null && !planner.equals(conv.getPlanner())
                && !(planner && conv.getAgentId() != null)) {
            conversationService.updatePlanner(conversationId, planner);
            conv.setPlanner(planner);
        }
        RoundHandler handler = selectHandler(conv, planner);
        trace.mode(handler == plannerRoundHandler ? MODE_PLANNER : MODE_AGENT);
        RoundResult r = handler.handle(conv, conversationId, message, material, progress, trace);
        // 规划策略返回回退信号（fallback，reply 为 null）：转普通对话策略兜底
        if (r == null || r.isFallback()) {
            trace.mode(MODE_AGENT);
            r = agentRoundHandler.handle(conv, conversationId, message, material, progress, trace);
        }
        // 引用由检索环节产出、不在模型调用链上，Advisor 采集不到：必须在此显式回写，
        // 否则 agent_trace.citations_json 恒为 NULL（静默失效）
        if (trace != null && r != null) {
            trace.citations(r.citations());
        }
        return r;
    }

    /** 选择本轮策略：请求级 planner 优先，否则回退会话默认形态。 */
    private RoundHandler selectHandler(Conversation conv, Boolean planner) {
        boolean plan = (planner != null) ? planner
                : (conv != null && Boolean.TRUE.equals(conv.getPlanner()));
        return plan ? plannerRoundHandler : agentRoundHandler;
    }

    /**
     * 「伪流式」输出：把完整答案切片推为 {@code token}（工具调用无法真流式，见 README 已知缺陷）。
     * 只推数据，记忆处理一律由调用方在推送后经 {@link #afterReply} 完成。
     */
    private void emitChunks(FluxSink<StreamEvent> sink, String full) {
        if (full == null) full = "";
        int chunk = chunkSize(full.length());
        log.info("对话完成：回复长度={}，分片 {} 字", full.length(), chunk);
        for (int i = 0; i < full.length(); i += chunk) {
            sink.next(StreamEvent.token(full.substring(i, Math.min(i + chunk, full.length()))));
        }
    }

    /** 按长度自适应分片：帧数上限固定，故总时长不超过 {@link #TYPING_MAX_MS}；短答案仍 4 字一片。 */
    private static int chunkSize(int length) {
        int maxFrames = (int) Math.max(1, TYPING_MAX_MS / TYPING_FRAME_MS);
        if (length <= maxFrames * TYPING_MIN_CHUNK) {
            return TYPING_MIN_CHUNK;
        }
        return (int) Math.ceil((double) length / maxFrames);
    }

    /**
     * 正文输出后的收尾，必须在回复之后调用：{@code touchConversation} 同步（毫秒级，保证列表排序即时），
     * 摘要合并异步（秒级 LLM 调用，不挡首字也不拖住流）；两者失败都只记日志。
     */
    private void afterReply(String conversationId, String message) {
        try {
            conversationService.touchConversation(conversationId, message);
        } catch (Exception e) {
            log.error("会话时间更新失败：会话={}", conversationId, e);
        }
        memoryMergeService.maybeMergeMemoryAsync(conversationId);
    }

    /** 空进度回调：同步接口（无事件通道）使用，执行过程只落日志。 */
    private static final Consumer<String> NO_PROGRESS = text -> {
    };
}
