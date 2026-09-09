package org.luo.service;

import lombok.extern.slf4j.Slf4j;
import org.luo.dto.StreamEvent;
import org.luo.entity.Conversation;
import org.luo.service.handler.AgentRoundHandler;
import org.luo.service.handler.PlannerRoundHandler;
import org.luo.service.handler.RoundHandler;
import org.luo.service.handler.RoundResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * 对话编排服务（门面 / 协调者）。
 * <p>
 * 本类只负责「编排」：把一次对话拆成几步，分别委托给专职子服务，自身不含业务细节：
 * <ul>
 *   <li>{@link AgentRoundHandler} —— 普通对话策略（智能路由 / 追问 / 正式回答）；</li>
 *   <li>{@link PlannerRoundHandler} —— 规划模式策略（动态规划 / 多步顺序执行）；</li>
 *   <li>{@link ChatComposer} —— 两条路径共用的 LLM 请求组装（记忆 / 工具 / 模型参数）；</li>
 *   <li>{@link MemoryMergeService} —— 对话结束后的滚动摘要合并；</li>
 *   <li>{@link ConversationService} —— 会话/消息持久化与窗口记忆读取（由 DbChatMemory 提供）。</li>
 * </ul>
 * 一轮对话统一走 {@link #runRound}：按会话形态（planner 或普通）选择 RoundHandler 策略执行，
 * 产出统一为 {@link RoundResult}，再由本类的 {@code chat}/{@code doStream} 统一输出并收尾。
 * 公共入口：{@link #chat}、{@link #stream}——两者都支持按请求指定 planner 模式
 * （见 {@link #chat(String, String, Boolean)}）。
 */
@Slf4j
@Service
public class ChatService {

    private final ConversationService conversationService;
    private final MemoryMergeService memoryMergeService;
    private final PromptService promptService;
    private final AgentRoundHandler agentRoundHandler;
    private final PlannerRoundHandler plannerRoundHandler;

    public ChatService(ConversationService conversationService,
                       MemoryMergeService memoryMergeService,
                       PromptService promptService,
                       AgentRoundHandler agentRoundHandler,
                       PlannerRoundHandler plannerRoundHandler) {
        this.conversationService = conversationService;
        this.memoryMergeService = memoryMergeService;
        this.promptService = promptService;
        this.agentRoundHandler = agentRoundHandler;
        this.plannerRoundHandler = plannerRoundHandler;
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
        return chat(conversationId, message, null);
    }

    /**
     * 同步对话：把用户消息发给大模型，等待完整回复后返回。
     * 历史消息的注入与本轮消息的落库由 MessageChatMemoryAdvisor 自动完成。
     *
     * @param conversationId 会话 ID
     * @param message        当前用户输入
     * @param planner        本轮是否按规划模式处理（null=跟随会话默认形态；非空=覆盖并写回会话，
     *                       供前端输入框「智能规划」开关使用）
     * @return AI 的完整回复文本
     */
    public String chat(String conversationId, String message, Boolean planner) {
        log.info("同步对话：会话={}，planner={}", conversationId, planner);
        Conversation conv = conversationService.ensureConversation(conversationId);
        // 统一编排入口：规划模式 / 普通对话 / 闲聊都由 runRound 按会话形态分发处理，
        // 追问与正式回答的文本都在返回值里，收尾统一由 afterReply 完成（同步接口无事件通道，
        // 执行过程只记日志，进度展示仅流式接口支持，见 stream）。
        RoundResult out = runRound(conv, conversationId, message, NO_PROGRESS, planner);
        log.info("同步对话完成：回复长度={}", out.reply() != null ? out.reply().length() : 0);
        afterReply(conversationId, message);
        return out.reply();
    }

    /**
     * 流式对话：以事件流返回本轮结果。
     * <p>
     * 事件分三类（见 {@link StreamEvent}）：
     * <ul>
     *   <li>{@code progress} —— 执行过程（规划出的步骤、每步开始/完成/失败），<b>仅运行期实时展示，
     *       不写入会话记忆</b>，因此刷新或重新打开会话都不会看到；</li>
     *   <li>{@code token} —— 最终回复的分片，前端累加成消息气泡，<b>只有它计入会话记忆</b>；</li>
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
     * @return 事件流（progress / token）
     */
    public Flux<StreamEvent> stream(String conversationId, String message) {
        return stream(conversationId, message, null);
    }

    /**
     * 流式对话：以事件流返回本轮结果。
     * <p>
     * 事件分三类（见 {@link StreamEvent}）：
     * <ul>
     *   <li>{@code progress} —— 执行过程（规划出的步骤、每步开始/完成/失败），<b>仅运行期实时展示，
     *       不写入会话记忆</b>，因此刷新或重新打开会话都不会看到；</li>
     *   <li>{@code token} —— 最终回复的分片，前端累加成消息气泡，<b>只有它计入会话记忆</b>；</li>
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
     * @param planner        本轮是否按规划模式处理（null=跟随会话默认形态；非空=覆盖并写回会话）
     * @return 事件流（progress / token）
     */
    public Flux<StreamEvent> stream(String conversationId, String message, Boolean planner) {
        return Flux.<StreamEvent>create(sink -> {
                    try {
                        doStream(conversationId, message, sink, planner);
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
     * 结果通过 sink 以事件形式推送：进度用 {@code progress}、正文用 {@code token}。
     */
    private void doStream(String conversationId, String message, FluxSink<StreamEvent> sink, Boolean planner) {
        log.info("流式对话：会话={}，planner={}", conversationId, planner);
        Conversation conv = conversationService.ensureConversation(conversationId);
        Consumer<String> progress = text -> sink.next(StreamEvent.progress(text));
        // 统一编排入口：规划模式 / 普通对话都由 runRound 分发处理，执行过程通过 progress
        // 事件实时推送（只展示、不进记忆）。追问返回澄清文本（整段推）、正式回答返回正文（切片模拟打字机）。
        RoundResult out = runRound(conv, conversationId, message, progress, planner);
        if (out.clarified()) {
            sink.next(StreamEvent.token(out.reply()));
        } else {
            emitChunks(sink, out.reply());
        }
        // 数据优先于记忆：正文推完后再做收尾（touch + 异步摘要合并），见 afterReply
        afterReply(conversationId, message);
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
    private RoundResult runRound(Conversation conv, String conversationId, String message,
                                 Consumer<String> progress, Boolean planner) {
        // 请求级 planner 写回会话（刷新后保持）。planner 与 agentId 互斥：绑定智能体的会话
        // 不持久化规划形态——本轮仍按请求执行规划，但会话保持普通/智能体形态，与 PUT /planner 的防御校验一致
        // （避免绕过 UI 直调 API 造成 agentId + planner 并存的脏状态）。
        if (planner != null && conv != null && !planner.equals(conv.getPlanner())
                && !(Boolean.TRUE.equals(planner) && conv.getAgentId() != null)) {
            conversationService.updatePlanner(conversationId, planner);
            conv.setPlanner(planner);
        }
        RoundResult r = selectHandler(conv, planner).handle(conv, conversationId, message, progress);
        // 规划策略返回回退信号（fallback，reply 为 null）：转普通对话策略兜底
        if (r == null || r.isFallback()) {
            r = agentRoundHandler.handle(conv, conversationId, message, progress);
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
