package org.luo.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.luo.ai.dto.KbCitation;
import org.luo.ai.dto.StreamEvent;
import org.luo.ai.dto.TurnBranch;
import org.luo.ai.entity.Conversation;
import org.luo.ai.entity.TaskTemplate;
import org.luo.ai.agent.handler.AgentRoundHandler;
import org.luo.ai.agent.handler.PlannerRoundHandler;
import org.luo.ai.agent.handler.ReviewRoundHandler;
import org.luo.ai.agent.handler.RoundHandler;
import org.luo.ai.agent.handler.RoundResult;
import org.luo.ai.trace.RoundTrace;
import org.luo.ai.trace.SelfEvalService;
import org.luo.ai.trace.TraceService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.luo.ai.agent.MemoryMergeService;
import org.luo.ai.agent.PromptService;
import org.luo.ai.chat.ChatComposer;
import org.luo.ai.memory.DbChatMemory;
import org.luo.ai.memory.MemoryViewService;

/**
 * 对话编排服务（门面）：一轮对话统一走 {@link #runRound}，按会话形态选 {@link RoundHandler} 策略
 * （{@link AgentRoundHandler} / {@link PlannerRoundHandler} / {@link ReviewRoundHandler}）执行，
 * 产出 {@link RoundResult} 后统一输出收尾。
 * 请求组装、摘要合并、持久化、追踪分别委托 {@link ChatComposer} / {@link MemoryMergeService} /
 * {@link ConversationService} / {@link TraceService}，本类只做编排。
 * <p>
 * <b>收尾顺序（勿乱）</b>：推回复 → 推引用 → 落库附件/引用 → 异步落库追踪 → 异步合并记忆。
 * 一切旁路数据都排在用户看到答案<b>之后</b>。
 * <p>
 * <b>输出侧内容安全</b>：所有出口正文都过一遍 {@link ContentSafetyService#checkOutput}（护栏关闭时零开销）。
 * 命中即<b>替换为提示文案</b>并落 WARN，不静默放行、也不假装回答成功。
 * <b>已知边界</b>：替换只作用于本轮推送与展示；普通对话的助手消息由记忆 Advisor 在模型返回时即写入
 * {@code chat_message}，故<b>库里保留的仍是模型原始输出</b>。要让落库内容也同步替换，需把护栏下沉到
 * Advisor 层改写 response —— 那会牵动 token/工具元数据的重建，本版本刻意不做，此处如实标注。
 */
@Slf4j
@Service
public class ChatService {

    /** 本轮形态：普通/智能体对话。 */
    private static final String MODE_AGENT = "agent";
    /** 本轮形态：规划模式。 */
    private static final String MODE_PLANNER = "planner";
    /** 本轮形态：并行评审（多候选作答 + 裁决综合）。 */
    private static final String MODE_REVIEW = "review";

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
    private final ReviewRoundHandler reviewRoundHandler;
    private final TraceService traceService;
    /** 内容安全护栏（输出侧）：所有出口正文在推送/返回前过一遍；护栏关闭时完全短路。 */
    private final ContentSafetyService safetyService;
    /** 记忆窗口视图（只读）：把「本轮会注入哪些历史」采进追踪，供页面回答「它为什么记得/不记得」。 */
    private final MemoryViewService memoryViewService;
    /** 长期事实条目：采集「本轮注入了多少字的事实段」用（与注入侧同一段文本，见其 injectableFactsText）。 */
    private final ConversationFactService factService;
    /** 线上回答自评（元认知）：回复交付之后按采样抽检一次，结果补写进追踪行；开关默认关。 */
    private final SelfEvalService selfEvalService;

    public ChatService(ConversationService conversationService,
                       MemoryMergeService memoryMergeService,
                       AgentRoundHandler agentRoundHandler,
                       PlannerRoundHandler plannerRoundHandler,
                       ReviewRoundHandler reviewRoundHandler,
                       TraceService traceService,
                       ContentSafetyService safetyService,
                       MemoryViewService memoryViewService,
                       ConversationFactService factService,
                       SelfEvalService selfEvalService) {
        this.conversationService = conversationService;
        this.memoryMergeService = memoryMergeService;
        this.agentRoundHandler = agentRoundHandler;
        this.plannerRoundHandler = plannerRoundHandler;
        this.reviewRoundHandler = reviewRoundHandler;
        this.traceService = traceService;
        this.safetyService = safetyService;
        this.memoryViewService = memoryViewService;
        this.factService = factService;
        this.selfEvalService = selfEvalService;
    }

    /**
     * 同步对话（带附件）。{@code material} 仅当轮注入模型；{@code attachmentsJson} 仅落库供历史回看，
     * 两者都不进记忆、不占 token。<b>{@code userId}</b> 为当前登录用户：会话归属校验与（不存在时的）
     * 补建都靠它，故必须由 HTTP 线程取出后传进来（异步线程读不到 {@code AuthContext}）。
     */
    public String chat(String conversationId, String message, String material, String attachmentsJson,
                       Boolean planner, Long userId, TurnBranch branch) {
        log.info("同步对话：会话={}，userId={}，planner={}，有附件={}", conversationId, userId, planner,
                material != null && !material.isBlank());
        Conversation conv = conversationService.ensureConversation(conversationId, userId);
        // 落库前水位：附件/引用/分支标记只写本轮新增消息，避免误挂历史消息
        Long watermark = needsWatermark(conv, attachmentsJson, branch)
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
        persistBranch(conversationId, watermark, branch);   // 同步接口无事件通道，失败只能落日志
        String delivered = screen(out.reply());
        log.info("同步对话完成：回复长度={}，引用={}", delivered != null ? delivered.length() : 0,
                out.citations().size());
        afterReply(conversationId, message);
        // 自评要 UPDATE 追踪行，故串在「追踪确实落库」之后（详见 SelfEvalService#maybeEvaluate）
        CompletableFuture<Void> traceSaved = traceService.saveAsync(trace);
        if (selfEvalWorthy(out)) {
            selfEvalService.maybeEvaluate(trace, delivered, traceSaved);
        }
        return delivered;
    }

    /**
     * 流式对话（带附件），执行体见 {@link #doStream}：跑在 {@link Schedulers#boundedElastic()} 上，
     * HTTP 线程立即返回、SSE 先建立，进度才能实时推送。<b>{@code userId}</b> 由调用方在 HTTP 线程取出后传入。
     */
    public Flux<StreamEvent> stream(String conversationId, String message, String material, String attachmentsJson,
                                    Boolean planner, Long userId, TurnBranch branch) {
        return Flux.<StreamEvent>create(sink -> {
                    try {
                        doStream(conversationId, message, material, attachmentsJson, sink, planner, userId, branch);
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

    /**
     * 显式续跑未完成任务（流式）：按会话定位唯一 RUNNING 任务，回填已完成步骤、只跑剩余步骤。
     * 事件类型与 {@link #stream} 一致（progress 播报 / token 正文 / citations 引用 / error 错误）。
     */
    public Flux<StreamEvent> resume(String conversationId, Long userId) {
        return Flux.<StreamEvent>create(sink -> {
                    try {
                        doResume(conversationId, sink, userId);
                    } catch (Exception e) {
                        log.error("续跑任务失败：会话={}，错误={}", conversationId, e.getMessage(), e);
                        sink.next(StreamEvent.error("续跑失败：" + e.getMessage()));
                    } finally {
                        sink.complete();
                    }
                }, FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.boundedElastic())
                .delayElements(Duration.ofMillis(TYPING_FRAME_MS));
    }

    /**
     * 局部重规划未完成任务：只重排「第一个未成功步骤及其之后」的一段，已完成步骤与其产出保持不动。
     * <p>
     * 与 {@link #resume} 不同，这里<b>不执行</b>任何步骤——只改库并返回新计划，等用户确认后再走续跑通路。
     * 因此是同步接口（一次模型往返），不必开 SSE；也正因如此它不写会话记忆。
     *
     * @return 重排后的计划 JSON + 变更说明；无可重排内容或模型未产出可用步骤时返回 null
     */
    public PlannerRoundHandler.ReplanOutcome replan(String conversationId) {
        log.info("局部重规划：会话={}", conversationId);
        return plannerRoundHandler.replanTask(conversationId);
    }

    /**
     * 套用规划模板：按模板的步骤骨架在当前会话落库一个新任务（<b>不执行</b>），返回计划 JSON 供前端渲染卡片。
     * <p>
     * 同步接口、<b>没有任何模型调用</b>—— 这正是模板存在的意义（省掉一次规划往返）。落库后由用户点
     * 「执行计划」触发 {@link #resume}，与「先看计划」「局部重规划」共用同一条执行通路。
     *
     * @param tpl 模板（归属校验由 controller 完成，本方法只负责落库）
     */
    public PlannerRoundHandler.ApplyOutcome applyTemplate(String conversationId, TaskTemplate tpl, String goal) {
        log.info("套用规划模板：会话={}，模板={}（{}）", conversationId, tpl.getId(), tpl.getName());
        return plannerRoundHandler.applyTemplate(conversationId, tpl, goal);
    }

    /** 续跑执行体（跑在弹性线程上），结果经 sink 推送。 */
    private void doResume(String conversationId, FluxSink<StreamEvent> sink, Long userId) {
        log.info("续跑任务：会话={}", conversationId);
        Conversation conv = conversationService.ensureConversation(conversationId, userId);
        Consumer<String> progress = text -> sink.next(StreamEvent.progress(text));
        RoundTrace trace = startTrace(conversationId, "");
        trace.mode(MODE_PLANNER);
        trace.onProgress(progress);
        RoundResult out;
        try {
            out = plannerRoundHandler.resumeTask(conv, conversationId, progress, trace);
            if (out == null || out.isFallback()) {
                sink.next(StreamEvent.error("没有未完成的任务，无需续跑"));
                traceService.saveAsync(trace);
                return;
            }
            trace.citations(out.citations());
            // 续跑是「任务」的专属操作，不改变会话形态；回复推送后补写引用与收尾
            // 审批关卡暂停：先推 approval 事件（渲染审批卡片），再推暂停说明正文
            if (out.hasApproval()) {
                sink.next(StreamEvent.approval(out.approvalJson()));
            }
            // 跨会话召回：属于「本轮用了什么素材」，排在正文之前（与 citations 的「答案角标」性质不同）
            if (out.hasRecall()) {
                sink.next(StreamEvent.recall(out.recallJson()));
            }
            emitChunks(sink, screen(out.reply()));
            String citationsJson = KbCitation.toJson(out.citations());
            if (!citationsJson.isEmpty()) {
                sink.next(StreamEvent.citations(citationsJson));
            }
            persistCitations(conversationId, null, out.citations());
            afterReply(conversationId, "");
        } catch (RuntimeException | Error e) {
            trace.markError(e.getMessage());
            traceService.saveAsync(trace);
            throw e;
        }
        // 自评要 UPDATE 追踪行，故串在「追踪确实落库」之后（详见 SelfEvalService#maybeEvaluate）
        CompletableFuture<Void> traceSaved = traceService.saveAsync(trace);
        if (selfEvalWorthy(out)) {
            selfEvalService.maybeEvaluate(trace, screen(out.reply()), traceSaved);
        }
    }

    /** 流式执行体（跑在弹性线程上，可阻塞调用 LLM），结果经 sink 推送。 */
    private void doStream(String conversationId, String message, String material, String attachmentsJson,
                          FluxSink<StreamEvent> sink, Boolean planner, Long userId, TurnBranch branch) {
        log.info("流式对话：会话={}，userId={}，planner={}，有附件={}", conversationId, userId, planner,
                material != null && !material.isBlank());
        Conversation conv = conversationService.ensureConversation(conversationId, userId);
        // 落库前水位：附件/引用/分支标记只写本轮新增消息，避免误挂历史消息
        Long watermark = needsWatermark(conv, attachmentsJson, branch)
                ? conversationService.maxMessageId(conversationId) : null;
        Consumer<String> progress = text -> sink.next(StreamEvent.progress(text));
        RoundTrace trace = startTrace(conversationId, message);
        // 执行过程经 Advisor / 各前置环节回调进 trace 播报：流式才挂（同步接口无通道，见 NO_PROGRESS）
        trace.onProgress(progress);
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
            sink.next(StreamEvent.token(screen(out.reply())));
        } else {
            // 规划暂停（「先看计划」）：先推 plan 事件让前端把卡片渲染出来，再推正文（计划清单文本）。
            // 顺序不能反——卡片要挂在气泡上方，正文先到会让用户先看到一段没有交互入口的清单。
            if (out.hasPlan()) {
                sink.next(StreamEvent.plan(out.planJson()));
            }
            // 执行到审批关卡暂停：同理，先推 approval 事件把审批卡片渲染出来，再推暂停说明正文
            if (out.hasApproval()) {
                sink.next(StreamEvent.approval(out.approvalJson()));
            }
            // 并行评审候选：必须早于正文——候选是「结论怎么来的」，正文是结论，反了会先看到一个没有出处的答案
            if (out.hasReview()) {
                sink.next(StreamEvent.review(out.reviewJson()));
            }
            // 跨会话召回：本轮用到的历史素材，同样排在正文之前
            if (out.hasRecall()) {
                sink.next(StreamEvent.recall(out.recallJson()));
            }
            emitChunks(sink, screen(out.reply()));
        }
        // 引用：正文推完后单独发一次，前端渲染 [n] 角标与来源列表
        String citationsJson = KbCitation.toJson(out.citations());
        if (!citationsJson.isEmpty()) {
            sink.next(StreamEvent.citations(citationsJson));
        }
        // 附件元数据 / 引用写入本轮消息（仅历史回看，不进 LLM 上下文）
        persistAttachments(conversationId, watermark, attachmentsJson);
        persistCitations(conversationId, watermark, out.citations());
        // 分支版本打标：本轮消息确实落库之后才让旧版本失效（失败要播报，不能静默——否则用户以为重发生效了）
        String branchWarn = persistBranch(conversationId, watermark, branch);
        if (branchWarn != null) {
            sink.next(StreamEvent.progress(branchWarn));
        }
        // 数据优先于记忆：正文推完再收尾（见 afterReply）
        afterReply(conversationId, message);
        // 自评要 UPDATE 追踪行，故串在「追踪确实落库」之后（详见 SelfEvalService#maybeEvaluate）
        CompletableFuture<Void> traceSaved = traceService.saveAsync(trace);
        if (selfEvalWorthy(out)) {
            selfEvalService.maybeEvaluate(trace, screen(out.reply()), traceSaved);
        }
    }

    /** 是否有附件展示元数据（空串/null 视为无）。 */
    private static boolean hasAttachments(String attachmentsJson) {
        return attachmentsJson != null && !attachmentsJson.isBlank();
    }

    /**
     * 本轮是否需要「落库前水位」：凡是要按「本轮新增的那几条消息」定位的后补写操作都需要它 ——
     * 附件元数据、RAG 引用，以及分支版本标记。RAG 那一项以 {@code ragEnabled} 廉价预判，
     * 避免关着 RAG 也白查一次 max(id)。
     */
    private static boolean needsWatermark(Conversation conv, String attachmentsJson, TurnBranch branch) {
        return hasAttachments(attachmentsJson) || branch != null
                || (conv != null && Boolean.TRUE.equals(conv.getRagEnabled()));
    }

    /** 建本轮追踪上下文（纯内存对象，失败不影响对话）。 */
    private static RoundTrace startTrace(String conversationId, String message) {
        return new RoundTrace(conversationId, message);
    }

    /**
     * 采集本轮注入的记忆构成（旁路观测，供页面回答「它为什么记得 / 不记得」）。
     * <p>
     * <b>为什么在业务侧算，而不是让 Advisor 上报</b>：Spring AI 的 {@code ChatMemory.get} 只收
     * conversationId，拿不到 advisor 上下文里的 trace；而这里调的是与它<b>同一个</b>
     * {@link DbChatMemory#snapshot}，且采集时机就在模型调用之前、期间没有任何写入，故两者数值一致。
     * 代价是每轮多一次「最近 N 条」的索引查询 —— 属于可接受的观测开销。
     * <p>
     * 失败只记日志：追踪是纯旁路，缺数据可以接受，报错不可以。
     */
    private void captureMemoryInjection(RoundTrace trace, Conversation conv, String conversationId) {
        if (trace == null || conv == null) return;
        try {
            // 第三段（事实）与注入侧取的是同一段文本：条目优先、旧归档兜底，判据在 ConversationFactService
            trace.memoryInjection(memoryViewService.window(conversationId),
                    conv.getSummary(),
                    factService.injectableFactsText(conversationId, conv.getCoreFacts()));
        } catch (Exception e) {
            log.warn("记忆注入采集失败：会话={}", conversationId, e);
        }
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
     * 分支版本打标：把本轮新落库的消息记为 {@code branch} 指定的版本并置为生效，同组旧版本随之失效。
     * <p>
     * <b>不走「失败只记日志」那套</b>（附件/引用是纯展示元数据，坏了不影响对话本身；分支标记是结构性的）：
     * 打标失败时旧版本仍是生效版本，用户以为「重发生效了」其实没有 —— 故返回一条提示文案，
     * 由流式出口<b>播报</b>给用户。同步接口没有事件通道，只能落日志。
     *
     * @return 需要播报给用户的警告；一切正常返回 {@code null}
     */
    private String persistBranch(String conversationId, Long watermark, TurnBranch branch) {
        if (branch == null) return null;
        try {
            int marked = conversationService.markRoundBranch(conversationId, watermark,
                    branch.groupId(), branch.version());
            return marked == 0 ? "⚠️ 本轮没有新消息落库，该轮保持原版本" : null;
        } catch (Exception e) {
            log.error("分支版本标记失败：会话={}，组={}，版本={}", conversationId, branch.groupId(), branch.version(), e);
            return "⚠️ 新版本未能登记（本轮内容已保留为普通消息），详见服务日志";
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
        trace.mode(handler == plannerRoundHandler ? MODE_PLANNER
                : (handler == reviewRoundHandler ? MODE_REVIEW : MODE_AGENT));
        // 记忆注入采集：只在「整轮只注入一次」的形态下采（agent）。规划模式每一步各自组装 prompt、各自注入
        // 同一个窗口，评审模式每个候选也各注入一次 —— 那种情况下「一轮一份」的清单代表不了任何一次调用，
        // 与其给一份看着合理却对不上的数字，不如留空（前端会明确说「未采集」）。
        if (handler == agentRoundHandler) {
            captureMemoryInjection(trace, conv, conversationId);
        }
        RoundResult r = handler.handle(conv, conversationId, message, material, progress, trace);
        // 规划策略返回回退信号（fallback，reply 为 null）：转普通对话策略兜底
        if (r == null || r.isFallback()) {
            trace.mode(MODE_AGENT);
            // 规划/评审回退成普通对话：形态在这一刻才确定，注入清单也在此刻采（覆盖语义，只采一次）。
            // 此刻采还有个好处——规划器可能已往 chat_message 写过东西，执行前采反而与真实注入不符。
            if (handler != agentRoundHandler) {
                captureMemoryInjection(trace, conv, conversationId);
            }
            r = agentRoundHandler.handle(conv, conversationId, message, material, progress, trace);
        }
        // 引用由检索环节产出、不在模型调用链上，Advisor 采集不到：必须在此显式回写，
        // 否则 agent_trace.citations_json 恒为 NULL（静默失效）
        if (trace != null && r != null) {
            trace.citations(r.citations());
        }
        return r;
    }

    /**
     * 选择本轮策略：请求级 planner 优先，否则回退会话默认形态。
     * <p>
     * <b>评审与规划互斥</b>：两者都是「编排形态」（一个决定怎么拆、一个决定谁来答），同时开语义会打架。
     * 故评审只在「本轮非规划」时才生效（请求显式带 {@code planner=true} 时规划优先）；开关层面另有强制
     * 互斥（见 {@code ConversationService#updateReviewEnabled}），此处是第二道防御。
     */
    private RoundHandler selectHandler(Conversation conv, Boolean planner) {
        boolean plan = (planner != null) ? planner
                : (conv != null && Boolean.TRUE.equals(conv.getPlanner()));
        if (plan) return plannerRoundHandler;
        if (conv != null && Boolean.TRUE.equals(conv.getReviewEnabled())) return reviewRoundHandler;
        return agentRoundHandler;
    }

    /**
     * 输出侧内容安全：命中规则即替换为提示文案（护栏关闭时零开销直接返回）。
     * <p>
     * 只改「推给用户看的那一份」，<b>不改库</b>——普通对话的助手消息由记忆 Advisor 在模型返回时就已落库，
     * 本方法跑在它之后。这个边界写在类注释里，不是遗漏。
     */
    private String screen(String reply) {
        if (reply == null || reply.isEmpty() || !safetyService.enabled()) return reply;
        String blocked = safetyService.checkOutput(reply);
        return blocked == null ? reply : blocked;
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

    /**
     * 本轮产出的是否是「值得自评的回答」。
     * <p>
     * 澄清追问、计划清单、审批暂停说明都不是「对用户问题的回答」，而自评的锚点是「有没有答到点上」——
     * 拿清单去评分只会得到一堆低分，把可观测面板的「低分轮次」变成噪声来源，反而掩盖真正的问题轮次。
     * 故这三类一律不评（{@code self_eval_score} 留 NULL，面板按「未自评」计数，与「评了低分」分开）。
     */
    private static boolean selfEvalWorthy(RoundResult out) {
        return out != null && !out.clarified() && !out.hasPlan() && !out.hasApproval();
    }
}
