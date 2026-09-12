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
 * 对话编排服务（门面 / 协调者）。
 * <p>
 * 本类只负责「编排」：把一次对话拆成几步，分别委托给专职子服务，自身不含业务细节：
 * <ul>
 *   <li>{@link AgentRoundHandler} —— 普通对话策略（智能路由 / 追问 / 正式回答）；</li>
 *   <li>{@link PlannerRoundHandler} —— 规划模式策略（动态规划 / 多步顺序执行）；</li>
 *   <li>{@link ChatComposer} —— 两条路径共用的 LLM 请求组装（记忆 / 工具 / 模型参数）；</li>
 *   <li>{@link MemoryMergeService} —— 对话结束后的滚动摘要合并；</li>
 *   <li>{@link ConversationService} —— 会话/消息持久化与窗口记忆读取（由 DbChatMemory 提供）；</li>
 *   <li>{@link TraceService} —— 本轮链路追踪的异步落库（可观测，纯旁路）。</li>
 * </ul>
 * 一轮对话统一走 {@link #runRound}：按会话形态（planner 或普通）选择 RoundHandler 策略执行，
 * 产出统一为 {@link RoundResult}，再由本类的 {@code chat}/{@code doStream} 统一输出并收尾。
 * 公共入口：{@link #chat}、{@link #stream}——两者都支持按请求指定 planner 模式
 * （见 {@link #chat(String, String, Boolean)}）。
 * <p>
 * <b>收尾顺序（勿乱）</b>：<i>推完回复 → 推引用事件 → 落库附件/引用 → 异步落库追踪 → 异步合并记忆</i>。
 * 一切旁路数据（附件元数据、引用来源、追踪、摘要）都排在用户看到答案<b>之后</b>，
 * 既是「数据优先于记忆」，也是「可观测不能挡在体验之前」。
 */
@Slf4j
@Service
public class ChatService {

    /** 本轮形态：普通/智能体对话。 */
    private static final String MODE_AGENT = "agent";
    /** 本轮形态：规划模式。 */
    private static final String MODE_PLANNER = "planner";

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

    /**
     * 同步对话：把用户消息发给大模型，等待完整回复后返回。
     * 历史消息的注入与本轮消息的落库由 MessageChatMemoryAdvisor 自动完成。
     *
     * @param conversationId 会话 ID
     * @param message        当前用户输入
     * @return AI 的完整回复文本
     */
    public String chat(String conversationId, String message) {
        return chat(conversationId, message, "", "", null);
    }

    /**
     * 同步对话：把用户消息发给大模型，等待完整回复后返回。
     * 历史消息的注入与本轮消息的落库由 MessageChatMemoryAdvisor 自动完成。
     *
     * @param conversationId 会话 ID
     * @param message        当前用户输入（纯提问，不含附件内容）
     * @param planner        本轮是否按规划模式处理（null=跟随会话默认形态；非空=覆盖并写回会话，
     *                       供前端输入框「智能规划」开关使用）
     * @return AI 的完整回复文本
     */
    public String chat(String conversationId, String message, Boolean planner) {
        return chat(conversationId, message, "", "", planner);
    }

    /**
     * 同步对话（带附件）：纯提问走记忆与路由；{@code material}（解析文本）仅当轮注入模型；
     * {@code attachmentsJson}（展示元数据）在本轮消息落库后写入用户消息，仅服务历史回看。
     * 三者互不干扰——记忆里只有纯提问，附件既不进历史窗口也不重复消耗 token。
     *
     * @param conversationId  会话 ID
     * @param message         当前用户输入（纯提问文本）
     * @param material        本轮附件材料（图片 caption / 文档解析文本），空串表示无附件
     * @param attachmentsJson 本轮附件展示元数据（JSON 数组，不含正文），空串表示无附件
     * @param planner         本轮是否按规划模式处理
     * @return AI 的完整回复文本
     */
    public String chat(String conversationId, String message, String material, String attachmentsJson,
                       Boolean planner) {
        log.info("同步对话：会话={}，planner={}，有附件={}", conversationId, planner,
                material != null && !material.isBlank());
        Conversation conv = conversationService.ensureConversation(conversationId);
        // 落库前记录水位：附件/引用只写到本轮新增的消息，避免早期失败时误挂历史消息
        Long watermark = needsWatermark(conv, attachmentsJson)
                ? conversationService.maxMessageId(conversationId) : null;
        RoundTrace trace = startTrace(conversationId, message);
        RoundResult out;
        try {
            // 统一编排入口：规划模式 / 普通对话 / 闲聊都由 runRound 按会话形态分发处理，
            // 追问与正式回答的文本都在返回值里，收尾统一由 afterReply 完成（同步接口无事件通道，
            // 执行过程只记日志，进度展示仅流式接口支持，见 stream）。
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

    /**
     * 流式对话：以事件流返回本轮结果。
     * <p>
     * 事件分四类（见 {@link StreamEvent}）：
     * <ul>
     *   <li>{@code progress} —— 执行过程（规划出的步骤、每步开始/完成/失败），<b>仅运行期实时展示，
     *       不写入会话记忆</b>，因此刷新或重新打开会话都不会看到；</li>
     *   <li>{@code token} —— 最终回复的分片，前端累加成消息气泡，<b>只有它计入会话记忆</b>；</li>
     *   <li>{@code citations} —— 本轮 RAG 引用来源（JSON），正文推完后发一次，前端渲染 [n] 角标与来源列表；</li>
     *   <li>{@code error} —— 本轮出错，前端红字提示、不进正文、不进记忆。</li>
     * </ul>
     * 整个执行体放在 {@link Schedulers#boundedElastic()} 上跑：让 HTTP 处理线程立刻返回、
     * SSE 连接先建立，进度才能在执行过程中被真正「实时」推送出去（否则会攒到最后一次性 flush）。
     * <p>
     * <b>数据优先于记忆</b>：所有分支都是「先把 token 推完，再做记忆处理」（见 {@link #afterReply}），
     * 其中滚动摘要合并可能触发一次额外 LLM 调用，已改为异步，不会挡住首字、也不会拖住流结束。
     *
     * @param conversationId 会话 ID
     * @param message        当前用户输入
     * @return 事件流（progress / token / citations）
     */
    public Flux<StreamEvent> stream(String conversationId, String message) {
        return stream(conversationId, message, "", "", null);
    }

    /**
     * 流式对话：以事件流返回本轮结果。
     * <p>
     * 事件分四类（见 {@link StreamEvent}）：
     * <ul>
     *   <li>{@code progress} —— 执行过程（规划步骤、每步的开始/完成/失败等），
     *       仅在本次运行期间实时展示给用户，<b>绝不写入会话记忆</b>：刷新或重新打开会话后不会出现；</li>
     *   <li>{@code token} —— 正文分片，前端累加进消息气泡，且是唯一写入会话记忆的内容；</li>
     *   <li>{@code citations} —— 本轮 RAG 引用来源，正文推完后发一次；</li>
     *   <li>{@code error} —— 本轮出错，前端红字提示、不进正文、不进记忆。</li>
     * </ul>
     * 整个执行体放在 {@link Schedulers#boundedElastic()} 上跑：让 HTTP 处理线程立刻返回、
     * SSE 连接先建立，进度才能在执行过程中被真正「实时」推送出去（否则会攒到最后一次性 flush）。
     * <p>
     * <b>数据优先于记忆</b>：所有分支都是「先把 token 推完，再做记忆处理」（见 {@link #afterReply}），
     * 其中滚动摘要合并可能触发一次额外 LLM 调用，已改为异步，不会挡住首字、也不会拖住流结束。
     *
     * @param conversationId 会话 ID
     * @param message        当前用户输入（纯提问，不含附件内容）
     * @param planner        本轮是否按规划模式处理（null=跟随会话默认形态；非空=覆盖并写回会话）
     * @return 事件流（progress / token / citations）
     */
    public Flux<StreamEvent> stream(String conversationId, String message, Boolean planner) {
        return stream(conversationId, message, "", "", planner);
    }

    /**
     * 流式对话（带附件）：纯提问走记忆与路由；{@code material}（解析文本）仅当轮注入模型；
     * {@code attachmentsJson}（展示元数据）在本轮消息落库后写入用户消息，仅服务历史回看。
     *
     * @param conversationId  会话 ID
     * @param message         当前用户输入（纯提问文本）
     * @param material        本轮附件材料（图片 caption / 文档解析文本），空串表示无附件
     * @param attachmentsJson 本轮附件展示元数据（JSON 数组，不含正文），空串表示无附件
     * @param planner         本轮是否按规划模式处理
     * @return 事件流（progress / token / citations）
     */
    public Flux<StreamEvent> stream(String conversationId, String message, String material, String attachmentsJson,
                                    Boolean planner) {
        return Flux.<StreamEvent>create(sink -> {
                    try {
                        doStream(conversationId, message, material, attachmentsJson, sink, planner);
                    } catch (Exception e) {
                        log.error("流式对话失败：会话={}，错误={}", conversationId, e.getMessage(), e);
                        // 错误走独立 error 事件：前端红字提示、不进正文、不进记忆
                        sink.next(StreamEvent.error("对话出错：" + e.getMessage()));
                    } finally {
                        sink.complete();
                    }
                }, FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.boundedElastic())
                // 逐元素间插入微小间隔：正文呈现逐字打字效果；进度事件数量少，额外延迟无感
                .delayElements(Duration.ofMillis(15));
    }

    /**
     * 流式对话的实际执行体（在弹性线程上运行，可以放心阻塞调用 LLM）。
     * 结果通过 sink 以事件形式推送：进度用 {@code progress}、正文用 {@code token}、引用用 {@code citations}。
     */
    private void doStream(String conversationId, String message, String material, String attachmentsJson,
                          FluxSink<StreamEvent> sink, Boolean planner) {
        log.info("流式对话：会话={}，planner={}，有附件={}", conversationId, planner,
                material != null && !material.isBlank());
        Conversation conv = conversationService.ensureConversation(conversationId);
        // 落库前记录水位：附件/引用只写到本轮新增的消息，避免早期失败时误挂历史消息
        Long watermark = needsWatermark(conv, attachmentsJson)
                ? conversationService.maxMessageId(conversationId) : null;
        Consumer<String> progress = text -> sink.next(StreamEvent.progress(text));
        RoundTrace trace = startTrace(conversationId, message);
        RoundResult out;
        try {
            // 统一编排入口：规划模式 / 普通对话都由 runRound 分发处理，执行过程通过 progress
            // 事件实时推送（只展示、不进记忆）。追问返回澄清文本（整段推）、正式回答返回正文（切片模拟打字机）。
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
        // 引用来源：正文推完后单独发一次（不进正文、不进记忆），前端渲染 [n] 角标与来源列表
        String citationsJson = KbCitation.toJson(out.citations());
        if (!citationsJson.isEmpty()) {
            sink.next(StreamEvent.citations(citationsJson));
        }
        // 附件展示元数据 / RAG 引用写入本轮消息（仅历史回看用，不进 LLM 上下文）
        persistAttachments(conversationId, watermark, attachmentsJson);
        persistCitations(conversationId, watermark, out.citations());
        // 数据优先于记忆：正文推完后再做收尾（touch + 异步摘要合并），见 afterReply
        afterReply(conversationId, message);
        traceService.saveAsync(trace);
    }

    /** 是否有附件展示元数据（空串/null 视为无）。 */
    private static boolean hasAttachments(String attachmentsJson) {
        return attachmentsJson != null && !attachmentsJson.isBlank();
    }

    /**
     * 本轮是否需要「落库前水位」。
     * <p>
     * 只有会产生「事后补写」的两类数据才需要水位：附件元数据（挂 user 消息）与 RAG 引用（挂 assistant 消息）。
     * 引用只在 RAG 开启的会话才可能出现，故以 {@code conv.ragEnabled} 作为廉价预判——
     * 常规路径（无附件、未开 RAG）因此不产生任何额外查询，与改造前的开销一致。
     */
    private static boolean needsWatermark(Conversation conv, String attachmentsJson) {
        return hasAttachments(attachmentsJson) || (conv != null && Boolean.TRUE.equals(conv.getRagEnabled()));
    }

    /** 建本轮追踪上下文（纯内存对象，失败不影响对话）。 */
    private static RoundTrace startTrace(String conversationId, String message) {
        return new RoundTrace(conversationId, message);
    }

    /**
     * 把本轮附件展示元数据写入本轮用户消息（见 {@link ConversationService#attachToLatestUserMessage}）。
     * 仅服务历史回看（缩略图 / 下载），不参与记忆读取，对 LLM 上下文零影响；失败只记日志、不影响本轮回复。
     */
    private void persistAttachments(String conversationId, Long watermark, String attachmentsJson) {
        if (!hasAttachments(attachmentsJson)) return;
        try {
            conversationService.attachToLatestUserMessage(conversationId, watermark, attachmentsJson);
        } catch (Exception e) {
            log.error("附件元数据写入失败：会话={}", conversationId, e);
        }
    }

    /**
     * 把本轮 RAG 引用来源写入本轮助手消息（见
     * {@link ConversationService#attachCitationsToLatestAssistantMessage}）。
     * 与附件元数据完全对称：仅服务前端溯源展示，不参与记忆读取；失败只记日志，不影响本轮回复。
     */
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
     * 统一编排入口：所有对话（规划 / 普通 / 闲聊）都先经这里按会话形态选择处理策略，
     * 再由调用方统一输出结果（追问整段推、正式回答切片）并调用 {@link #afterReply} 收尾。
     * 替代原先 chat/doStream 中各自重复的 if(planner) 分叉；新增会话形态只需新增 RoundHandler 实现。
     * <p>
     * 请求级 planner 标志（前端输入框「智能规划」开关）在此统一处理：非空且与会话当前形态不同时，
     * 写回会话（刷新后保持），并同步到内存实体供本轮选择策略使用。
     * 注意：绑定智能体的会话（agentId 非空）不会被写回为规划形态——planner 与 agentId 互斥，
     * 本轮规划请求仅临时生效，不持久化（详见方法内守卫条件）。
     */
    private RoundResult runRound(Conversation conv, String conversationId, String message, String material,
                                Consumer<String> progress, Boolean planner, RoundTrace trace) {
        // 请求级 planner 写回会话（刷新后保持）。planner 与 agentId 互斥：绑定智能体的会话
        // 不持久化规划形态——本轮仍按请求执行规划，但会话保持普通/智能体形态，与 PUT /planner 的防御校验一致
        // （避免绕过 UI 直调 API 造成 agentId + planner 并存的脏状态）。
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
        return r;
    }

    /**
     * 选择本轮处理策略：请求级 planner 标志优先（前端每次发送都带），否则回退会话默认形态
     * （planner=1 的会话走 Planner，其余一律走普通对话策略）。
     */
    private RoundHandler selectHandler(Conversation conv, Boolean planner) {
        boolean plan = (planner != null) ? planner
                : (conv != null && Boolean.TRUE.equals(conv.getPlanner()));
        return plan ? plannerRoundHandler : agentRoundHandler;
    }

    /**
     * 「伪流式」输出：把已拿到的完整答案按 4 字一块切片推送为 {@code token} 事件，前端呈现逐字打字效果。
     * 工具调用本身无法流式（2.0.0 流式合并有缺陷），动态规划也是串行跑完才出结果，故统一在这里切片。
     * <p>
     * <b>本方法只推数据，不做任何记忆处理</b>：记忆落库与摘要合并一律由调用方在推送之后
     * 调用 {@link #afterReply} 完成，确保用户先看到回复（详见该方法说明）。
     */
    private void emitChunks(FluxSink<StreamEvent> sink, String full) {
        if (full == null) full = "";
        log.info("对话完成：回复长度={}", full.length());
        for (int i = 0; i < full.length(); i += 4) {
            sink.next(StreamEvent.token(full.substring(i, Math.min(i + 4, full.length()))));
        }
    }

    /**
     * 回复「已返回给用户之后」的收尾处理，必须放在正文输出之后调用。
     * <p>
     * 拆成两档，避免记忆处理挡在用户看到答案之前：
     * <ul>
     *   <li>{@code touchConversation} —— 一条 DB update，毫秒级，同步执行（顺带保证会话列表排序即时正确）；</li>
     *   <li>{@code maybeMergeMemoryAsync} —— 达到阈值时要多做一次 LLM 摘要调用（秒级），
     *       改为异步执行，既不拖慢首字、也不拖住 SSE 流结束。</li>
     * </ul>
     * 两者失败都只记日志，不影响本轮已经给出的回复。
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
