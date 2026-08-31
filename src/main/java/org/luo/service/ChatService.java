package org.luo.service;

import lombok.extern.slf4j.Slf4j;
import org.luo.advisor.ToolUsageLoggingAdvisor;
import org.luo.dto.StreamEvent;
import org.luo.entity.Agent;
import org.luo.entity.Conversation;
import org.luo.service.PlannerService.PlanStep;
import org.luo.tool.ToolRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 对话编排服务（门面 / 协调者）。
 * <p>
 * 本类只负责「编排」：把一次对话拆成几步，分别委托给专职子服务，自身不含业务细节：
 * <ul>
 *   <li>{@link ParamFillingService} —— 参数补全与追问（声明式 paramSchema）；</li>
 *   <li>{@link AgentRouter} —— 未绑定智能体时的智能路由；</li>
 *   <li>{@link MemoryMergeService} —— 对话结束后的滚动摘要合并；</li>
 *   <li>{@link PromptService} —— 系统提示词解析与生成；</li>
 *   <li>{@link ConversationService} —— 会话/消息持久化与窗口记忆读取（由 DbChatMemory 提供）。</li>
 * </ul>
 * 公共入口只有三个：{@link #chat}、{@link #stream}、{@link #generateAgentPrompt}（后者透传给 PromptService）。
 */
@Slf4j
@Service
public class ChatService {

    /** 带会话记忆的 ChatClient（通过 MessageChatMemoryAdvisor 自动读写历史）。 */
    private final ChatClient chatClient;
    /** 不带会话记忆的 ChatClient：动态规划中间步骤专用，避免中间产物被写进会话历史。 */
    private final ChatClient internalChatClient;
    private final ConversationService conversationService;
    private final AgentService agentService;
    private final PlannerService plannerService;
    private final ParamFillingService paramFillingService;
    private final AgentRouter agentRouter;
    private final MemoryMergeService memoryMergeService;
    private final PromptService promptService;
    private final ToolRegistry toolRegistry;
    private final ChatMemory chatMemory;

    public ChatService(ChatClient.Builder chatClientBuilder,
                       ConversationService conversationService,
                       AgentService agentService,
                       PlannerService plannerService,
                       MessageChatMemoryAdvisor memoryAdvisor,
                       ToolUsageLoggingAdvisor toolUsageLoggingAdvisor,
                       ParamFillingService paramFillingService,
                       AgentRouter agentRouter,
                       MemoryMergeService memoryMergeService,
                       PromptService promptService,
                       ToolRegistry toolRegistry,
                       ChatMemory chatMemory) {
        // 工具挂载不在这里做：在 buildRequest 中按「当前请求是否路由/绑定到具体 Agent」门控挂载
        // （普通对话不挂任何工具，避免把工具定义塞进每次对话的上下文；Agent 对话才挂全局能力池）。
        // 全局能力池由 ToolRegistry 启动时预解析为 ToolCallback，由 AI 在 Agent 范围内自主决定调用哪些，
        // 不按 Agent 手动写死工具列表、也无需 ToolSearch 渐进式披露（避免其反复 search 的往返开销）。
        // 工具使用监控 Advisor 放在链末尾，记录每次对话实际挂载了哪些工具。
        // 中间步骤客户端：先 clone 出一份干净的 builder（clone 须在 defaultAdvisors 之前，避免继承到记忆 Advisor）
        this.internalChatClient = chatClientBuilder.clone().defaultAdvisors(toolUsageLoggingAdvisor).build();
        this.chatClient = chatClientBuilder.defaultAdvisors(memoryAdvisor, toolUsageLoggingAdvisor).build();
        this.conversationService = conversationService;
        this.agentService = agentService;
        this.plannerService = plannerService;
        this.paramFillingService = paramFillingService;
        this.agentRouter = agentRouter;
        this.memoryMergeService = memoryMergeService;
        this.promptService = promptService;
        this.toolRegistry = toolRegistry;
        this.chatMemory = chatMemory;
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
        log.info("同步对话：会话={}", conversationId);
        Conversation conv = conversationService.ensureConversation(conversationId);
        // 规划模式会话：conversation.planner=1，不参与 Agent 路由、也不绑定工作流，
        // 由动态规划器（PlannerService）运行时根据用户目标编排多智能体步骤后顺序执行（见 runPlannerRound）。
        if (conv != null && Boolean.TRUE.equals(conv.getPlanner())) {
            // 同步接口没有事件通道，执行过程只记日志（进度展示仅流式接口支持，见 stream）
            String reply = runPlannerRound(conv, conversationId, message, NO_PROGRESS);
            if (reply != null) return reply;
            // 规划器返回 null（理论不会发生）：落到下方普通流程
        }
        // 普通对话（智能路由 / 追问 / 正式回答）的编排在 runAgentRound 中完成；
        // 追问与正式回答的文本都在返回值里，收尾统一由 afterReply 处理。
        AgentRoundOutcome out = runAgentRound(conv, conversationId, message, NO_PROGRESS);
        log.info("同步对话完成：回复长度={}", out.reply() != null ? out.reply().length() : 0);
        afterReply(conversationId, message);
        return out.reply();
    }

    /**
     * 流式对话：以事件流返回本轮结果。
     * <p>
     * 事件分两类（见 {@link StreamEvent}）：
     * <ul>
     *   <li>{@code progress} —— 执行过程（规划出的步骤、每步开始/完成/失败），<b>仅运行期实时展示，
     *       不写入会话记忆</b>，因此刷新或重新打开会话都不会看到；</li>
     *   <li>{@code token} —— 最终回复的分片，前端累加成消息气泡，<b>只有它计入会话记忆</b>。</li>
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
        return Flux.<StreamEvent>create(sink -> {
                    try {
                        doStream(conversationId, message, sink);
                    } catch (Exception e) {
                        log.error("流式对话失败：会话={}，错误={}", conversationId, e.getMessage(), e);
                        sink.next(StreamEvent.token("对话出错：" + e.getMessage()));
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
    private void doStream(String conversationId, String message, FluxSink<StreamEvent> sink) {
        log.info("流式对话：会话={}", conversationId);
        Conversation conv = conversationService.ensureConversation(conversationId);
        Consumer<String> progress = text -> sink.next(StreamEvent.progress(text));
        // 规划模式会话：动态规划器编排多智能体步骤后顺序执行（见 runPlannerRound）。
        // 执行过程通过 progress 事件实时推送给前端，但不落库、不进记忆。
        if (conv != null && Boolean.TRUE.equals(conv.getPlanner())) {
            String reply = runPlannerRound(conv, conversationId, message, progress);
            if (reply != null) {
                // 先把最终结果切片推给前端（用户立刻看到答案），记忆处理一律放到推送之后
                emitChunks(sink, reply);
                return;
            }
            // 规划器返回 null（理论不会发生）：落到下方普通流程
        }
        // 普通对话编排在 runAgentRound 中完成：追问返回澄清文本（整段推）、正式回答返回正文（切片模拟打字机）。
        AgentRoundOutcome out = runAgentRound(conv, conversationId, message, progress);
        if (out.clarified()) {
            sink.next(StreamEvent.token(out.reply()));
        } else {
            emitChunks(sink, out.reply());
        }
        afterReply(conversationId, message);
    }

    /**
     * 规划模式会话的一整轮处理：动态规划执行 + 记忆写入 + 收尾。
     * 由 {@code chat}/{@code doStream} 共用，差异只在进度回调（同步接口传 {@link #NO_PROGRESS}，流式接口推 progress 事件）。
     *
     * @return 最终回复文本；规划器未产出时返回 null（理论不会发生，调用方回退普通流程）
     */
    private String runPlannerRound(Conversation conv, String conversationId, String message, Consumer<String> progress) {
        PlannerOutcome po = handlePlannerConversation(conv, conversationId, message, progress);
        if (po.reply() == null) return null;
        // 多步执行期间不写记忆，需在回复产出后显式补写「用户原话 → 最终回复」整对
        if (po.needSaveExchange()) savePlannerExchange(conversationId, message, po.reply());
        afterReply(conversationId, message);
        return po.reply();
    }

    /**
     * 执行一轮「普通对话」（非规划模式）：智能路由 → 话题切换预检 → 参数补全/追问 → 正式回答。
     * 由 {@code chat}/{@code doStream} 共用，差异只在进度回调（同步接口传 {@link #NO_PROGRESS}，流式接口推 progress 事件）。
     * <p>
     * 需要落库的交互在本方法内完成：追问时临时绑定 agent（CLARIFY）并落库追问记录；正式回答后解绑
     * （仅 CLARIFY 绑定、显式绑定保持）。调用方负责把 {@link AgentRoundOutcome#reply()} 输出给用户
     * （同步接口直接返回；流式接口按 clarified 分支推整段或切片），并在之后调用 {@link #afterReply} 收尾。
     * 落库/解绑均为毫秒级 DB 操作，发生在 LLM 调用（耗时主体）之后，不影响用户首字感知。
     */
    private AgentRoundOutcome runAgentRound(Conversation conv, String conversationId, String message,
                                            Consumer<String> progress) {
        // 绑定来源：EXPLICIT=用户显式选择（保持粘住，不因话题切换解绑）；CLARIFY=追问流程临时绑定。
        boolean explicitBinding = "EXPLICIT".equals(conv.getAgentBindSource());
        Agent agent = determineAgent(conv, message);

        // 话题切换预检：仅当会话处于「追问绑定(CLARIFY)」时。
        // 携带「待回答的追问」重新审视本轮消息的真实意图，避免「深圳烧鸡味道怎么样」这类含城市词的新话题
        // 被误当成天气补全、进而去查天气。必须放在参数补全之前：原实现依赖「是否凑齐参数」判断，
        // 但新话题里若恰好含城市词会被直接凑齐参数，导致检测彻底进不去。
        // 由路由 LLM 语义判断三态（不做关键词/语气词启发式）：
        //   continueTask=用户在回答追问 → 保持绑定继续补全；
        //   agent() 命中同一 agent → 继续补全；命中另一 agent → 转向（解绑）；
        //   none（未命中且未在回答追问）= 新话题且无 agent 可接 → 转普通对话并解绑。
        if (!explicitBinding && conv.getAgentId() != null && "CLARIFY".equals(conv.getAgentBindSource())) {
            String pendingQuestion = paramFillingService.lastClarifyQuestion(conversationId);
            AgentRouter.RouteDecision rd = agentRouter.route(message, pendingQuestion,
                    buildHistoryContextText(conversationId, RECENT_TURNS, null));
            boolean keepBound = rd.continuation()
                    || (rd.agent() != null && rd.agent().getId().equals(agent.getId()));
            if (!keepBound) {
                // 意图已转向：另一个 agent 或普通对话（agent()==null）。解除追问绑定，按新意图处理。
                conversationService.unbindAgent(conversationId);
                agent = rd.agent();
            }
        }

        // 播报本轮由谁处理（同样属于「执行过程」，只展示、不进记忆）；普通闲聊无 agent，不打扰。
        if (agent != null) {
            progress.accept("🤖 已交由智能体「" + agent.getName() + "」处理");
        }

        ParamFillingService.ClarifyDecision decision = paramFillingService.decideClarify(conversationId, message, agent);
        if (decision.question != null) {
            // 进入追问：把正在补全参数的 agent 临时绑定（CLARIFY），使下一轮追问回答能复用同一 agent。
            // 落库由 saveClarifyExchange 统一完成（避免话题切换分支误落库失效的追问）。
            if (agent != null && !explicitBinding) {
                conversationService.bindAgent(conversationId, agent.getId());
            }
            conversationService.saveClarifyExchange(conversationId, message, decision.question);
            return new AgentRoundOutcome(decision.question, true);
        }
        // 常规单智能体回答（动态规划由 planner 会话单独处理，见 handlePlannerConversation）。
        ChatClient.ChatClientRequestSpec spec = buildRequest(conversationId, message, conv, agent,
                paramFillingService.buildParamBlock(decision));
        String reply = spec.call().content();
        // 路由命中的 agent 在完成回答后解绑，恢复后续轮的正常智能路由；显式绑定的保持不变。
        if (!explicitBinding) conversationService.unbindAgent(conversationId);
        return new AgentRoundOutcome(reply, false);
    }

    /**
     * 普通对话一轮的产出。
     *
     * @param reply     本轮回复文本（clarified=true 时为追问文本）
     * @param clarified true=本轮未给出正式回答、返回的是追问；false=正式回答
     */
    private record AgentRoundOutcome(String reply, boolean clarified) {}

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

    // ==================== 动态规划（Planner） ====================

    /** 空进度回调：同步接口（无事件通道）使用，执行过程只落日志。 */
    private static final Consumer<String> NO_PROGRESS = text -> {
    };

    /** 智能路由上下文最多取的最近消息条数（路由 LLM 的 token 成本控制）。 */
    private static final int RECENT_TURNS = 6;

    /**
     * 数据实时性强化规则：仅对系统提示词声明了「数据实时性」原则的数据分析类智能体（如教育数据智能分析）追加。
     * 纯提示词原则是软约束，模型仍可能直接引用历史数据；此规则以更高优先级的即时指令形式在每轮请求中重申，
     * 与智能体自带提示词叠加，强制「需要数据必须先重新查询数据库」。
     */
    private static final String REALTIME_DATA_RULE = "\n\n【数据实时性强制规则（每轮必须遵守）】\n"
            + "1. 回答任何需要数据的问题（统计、分布、对比、排名、占比、平均分、及格率等），"
            + "必须调用 query 工具重新查询数据库获取最新数据，禁止跳过查询直接回答。\n"
            + "2. 严禁使用对话历史中的旧数据作为回答或图表的数据来源——历史消息中的数值、表格、查询结果仅供理解语境，不得直接引用。\n"
            + "3. 用户要求基于上一轮数据生成图表或继续分析时，同样必须先重新 query 获取最新数据；"
            + "仅本轮对话中已查询并拿到结果的数据可直接复用。";

    /**
     * 规划模式会话处理：由动态规划器（PlannerService）在运行时根据用户目标产出多智能体步骤，
     * 再顺序执行（前一步输出作为后一步输入）。规划器不预配置步骤、不追问参数，对用户最友好。
     * <ul>
     *   <li>规划为空（无可用智能体 / 目标与任何智能体无关）→ 回退普通助手直接回答；</li>
     *   <li>计划中的智能体编码不存在 → 跳过该步；全部不存在 → 回退普通回答；</li>
     *   <li>执行复用 {@link #executeSteps}，记忆由 {@link #savePlannerExchange} 统一落库，工具全程挂载。</li>
     * </ul>
     * 执行过程通过 {@code progress} 回调实时对外播报（规划中 / 计划清单 / 每步开始与完成），
     * 这些文本<b>只用于展示，不写入会话记忆</b>——记忆里只有「用户原话 → 最终回复」。
     * <p>
     * 本方法<b>不写记忆</b>：是否需要显式落库由返回值的 {@code needSaveExchange} 告知调用方，
     * 由调用方在「回复已推送给用户之后」再写（见 {@link PlannerOutcome}）。
     *
     * @param progress 进度回调（流式接口传事件推送，同步接口传 {@link #NO_PROGRESS}）
     * @return 本轮结果（回复文本 + 是否需显式写记忆）
     */
    private PlannerOutcome handlePlannerConversation(Conversation conv, String conversationId, String message,
                                                     Consumer<String> progress) {
        progress.accept("🧭 正在分析目标并规划执行步骤…");
        List<PlanStep> plan = plannerService.plan(message);
        log.info("动态规划：会话={}, 步骤={}", conversationId, plan);
        if (plan == null || plan.isEmpty()) {
            log.info("动态规划：无需编排（无可用智能体或目标无关），普通回答");
            progress.accept("💬 无需多智能体协同，由通用助手直接回答");
            // 回退路径走带记忆的 chatClient，记忆由 Advisor 自动落库，无需显式补写
            return new PlannerOutcome(answerDefault(conv, conversationId, message), false);
        }
        List<StepSpec> specs = new ArrayList<>();
        for (PlanStep s : plan) {
            Agent a = agentService.getByCode(s.agentCode());
            if (a == null) {
                log.warn("动态规划：智能体编码 {} 不存在，跳过该步骤", s.agentCode());
                progress.accept("⚠️ 跳过：智能体「" + s.agentCode() + "」不存在");
                continue;
            }
            specs.add(new StepSpec(a, s.instruction()));
        }
        if (specs.isEmpty()) {
            log.warn("动态规划：计划中的智能体均不存在，回退普通回答");
            progress.accept("💬 计划中的智能体都不可用，改由通用助手回答");
            return new PlannerOutcome(answerDefault(conv, conversationId, message), false);
        }
        log.info("动态规划启动：会话={}，步骤数={}", conversationId, specs.size());
        // 播报计划清单：让用户先看到「准备怎么做」，再看到逐步执行情况
        StringBuilder planText = new StringBuilder("📋 规划完成，共 " + specs.size() + " 步：");
        for (int i = 0; i < specs.size(); i++) {
            StepSpec s = specs.get(i);
            planText.append("\n").append(i + 1).append(". ").append(s.agent().getName());
            if (s.instruction() != null && !s.instruction().isBlank()) {
                planText.append(" —— ").append(s.instruction());
            }
        }
        progress.accept(planText.toString());
        String reply = executeSteps(specs, message, conversationId, conv, null, progress);
        if (reply == null || reply.isBlank()) {
            log.warn("动态规划未产出结果，回退普通回答");
            progress.accept("⚠️ 各步骤均未产出结果，改由通用助手回答");
            return new PlannerOutcome(answerDefault(conv, conversationId, message), false);
        }
        progress.accept("✅ 全部步骤执行完毕，已生成最终结果");
        // 多步规划：执行期间所有步骤都用 internalChatClient（不写记忆），避免「指令+上一步输出」这类合成串
        // 污染会话历史；需由调用方在回复推送后显式补写「用户原话 → 最终回复」整对（needSaveExchange=true）。
        return new PlannerOutcome(reply, true);
    }

    /**
     * 规划模式一轮的产出。
     *
     * @param reply            最终回复文本
     * @param needSaveExchange 是否需要显式把「用户原话 → 最终回复」写入记忆：
     *                         多步执行为 {@code true}（执行期全程不写记忆）；
     *                         回退到通用助手为 {@code false}（记忆 Advisor 已自动落库，重复写会出现两遍）
     */
    private record PlannerOutcome(String reply, boolean needSaveExchange) {}

    /**
     * 把规划模式的一轮对话（用户原话 + 最终回复）写入会话记忆。
     * 顺序执行期间各步骤都走 internalChatClient、不落库，故在这里统一补写，
     * 否则 chat_message 里存的是被污染的「指令+上一步输出」，而非用户真正的问题。
     */
    private void savePlannerExchange(String conversationId, String userMessage, String assistantReply) {
        try {
            chatMemory.add(conversationId, List.of(
                    new UserMessage(userMessage),
                    new AssistantMessage(assistantReply)));
            log.debug("规划记忆写入：会话={}", conversationId);
        } catch (Exception e) {
            log.error("规划记忆写入失败：会话={}", conversationId, e);
        }
    }

    /**
     * 通用助手兜底回答（无智能体绑定、不挂载工具）：用于规划模式回退、或规划目标与任何智能体无关时。
     * 复用带记忆的 {@link #chatClient}，保证用户原始目标被写入会话历史。
     */
    private String answerDefault(Conversation conv, String conversationId, String message) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .system(promptService.resolveSystemPrompt(null) + buildLongTermMemoryText(conv))
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId));
        String reply = spec.call().content();
        return reply != null ? reply : "";
    }

    /**
     * 顺序执行一组步骤（智能体 + 指令）：前一步输出作为后一步输入。
     * 供「动态规划（运行时规划步骤）」复用，避免重复的执行逻辑。
     * <ul>
     *   <li>第 1 步输入 = firstInput（用户原始目标），并注入长期记忆与已确认参数（paramBlock）；</li>
     *   <li>后续步骤输入 = 本步指令 + 上一步输出；</li>
     *   <li>所有步骤均用 {@link #internalChatClient}（无记忆），不写会话历史，避免中间产物/合成串污染；
     *       最后一步额外注入近期窗口历史（historyContext）以替代记忆 Advisor 的上下文读取，保证回答连贯；</li>
     *   <li>执行结束后由 {@link #savePlannerExchange} 把「用户原话 → 最终回复」整对落库；</li>
     *   <li>任一步骤失败/返回空则沿用上一步结果；全部失败返回 null；</li>
     *   <li>每步的开始/完成/失败通过 {@code progress} 回调实时播报，这些文本只用于展示、不进记忆。</li>
     * </ul>
     */
    private String executeSteps(List<StepSpec> steps, String firstInput, String conversationId,
                                Conversation conv, String paramBlock, Consumer<String> progress) {
        if (steps == null || steps.isEmpty()) return null;
        // 近期窗口历史（替代记忆 Advisor 的读取）：仅注入到最后一步，避免合成输入被误写入记忆。
        String historyContext = buildHistoryContextText(conversationId, 0,
                "\n\n[近期对话] 以下为本轮之前同一会话的近期上下文（仅供参考，请勿复述）：\n");
        String previous = null;   // 上一步的输出（供下一步作为输入）
        String last = null;       // 最后一个成功步骤的输出（即最终结果）
        for (int i = 0; i < steps.size(); i++) {
            StepSpec s = steps.get(i);
            boolean isLast = (i == steps.size() - 1);
            String stepTag = "步骤 " + (i + 1) + "/" + steps.size() + " · " + s.agent().getName();
            progress.accept("▶ " + stepTag + " 执行中…");
            String userInput = (i == 0) ? firstInput : "上一步的输出：\n" + previous;
            if (s.instruction() != null && !s.instruction().isBlank()) {
                userInput = s.instruction() + "\n\n" + userInput;
            }
            try {
                // 所有步骤都不挂记忆 Advisor：中间产物绝不写入会话历史
                ChatClient client = internalChatClient;
                // 长期记忆（核心信息/历史摘要）对所有步骤注入：用户偏好应贯穿整条流水线；
                // 已确认参数只在第一步注入（后续步骤以上一步产物为输入，无关参数只会造成干扰）；
                // 最后一步追加近期窗口历史，保持与单智能体对话一致的上下文连贯性。
                String system = promptService.resolveSystemPrompt(s.agent())
                        + buildLongTermMemoryText(conv)
                        + (i == 0 && paramBlock != null ? paramBlock : "")
                        + (isLast && !historyContext.isBlank() ? historyContext : "");
                if (system.contains("数据实时性")) {
                    system += REALTIME_DATA_RULE;
                }
                ChatClient.ChatClientRequestSpec spec = client.prompt().system(system).user(userInput);
                spec = spec.tools(toolRegistry.getToolCallbacks());
                spec = applyAgentOptions(spec, s.agent());
                String out = spec.call().content();
                if (out != null && !out.isBlank()) {
                    previous = out;
                    last = out;
                    log.info("顺序执行：步骤 {}/{}（{}）完成，输出长度={}", i + 1, steps.size(),
                            s.agent().getAgentCode(), out.length());
                    progress.accept("✔ " + stepTag + " 完成（产出 " + out.length() + " 字）");
                } else {
                    log.warn("顺序执行：步骤 {}/{}（{}）返回空，沿用上一步结果", i + 1, steps.size(), s.agent().getAgentCode());
                    progress.accept("⚠️ " + stepTag + " 未产出内容，沿用上一步结果");
                }
            } catch (Exception e) {
                log.error("顺序执行：步骤 {}/{}（{}）执行失败：{}", i + 1, steps.size(),
                        s.agent().getAgentCode(), e.getMessage(), e);
                progress.accept("✖ " + stepTag + " 执行失败：" + e.getMessage());
            }
        }
        return last;
    }

    /**
     * 读取本会话的对话历史并格式化为文本块（"用户：xxx / 助手：yyy" 行式）。
     * 两个用途共用一套格式化逻辑，仅「截取条数」与「前置说明」不同：
     * <ul>
     *   <li>{@code maxMessages <= 0} 取全部历史（token 预算内），用于规划模式最后一步注入系统提示词、
     *       替代记忆 Advisor 的上下文读取（执行期间不写记忆，见 {@link #executeSteps}）；</li>
     *   <li>{@code maxMessages > 0} 只取最近 N 条，用于智能路由判断「承接上一轮的短追问」时作为上下文
     *       （如上一轮在查天气、用户只说「北京呢？」），控制路由 LLM 的 token 成本。</li>
     * </ul>
     * 读取失败返回空串，不影响主流程。
     *
     * @param conversationId 会话 ID
     * @param maxMessages    最多取多少条；小于等于 0 表示全部
     * @param header         文本块前置说明（可空，路由上下文场景传 null）
     */
    private String buildHistoryContextText(String conversationId, int maxMessages, String header) {
        try {
            List<Message> hist = chatMemory.get(conversationId);
            if (hist.isEmpty()) return "";
            int start = (maxMessages > 0) ? Math.max(0, hist.size() - maxMessages) : 0;
            StringBuilder sb = new StringBuilder();
            if (header != null && !header.isBlank()) sb.append(header);
            for (Message m : hist.subList(start, hist.size())) {
                String role = m instanceof UserMessage ? "用户" : (m instanceof AssistantMessage ? "助手" : null);
                if (role == null) continue;
                String text = m.getText();
                if (text == null || text.isBlank()) continue;
                sb.append(role).append("：").append(text).append("\n");
            }
            return sb.toString().strip();
        } catch (Exception e) {
            log.warn("读取历史上下文失败：会话={}", conversationId, e);
            return "";
        }
    }

    /** 顺序执行的一个步骤：执行哪个智能体 + 给它的补充指令（被 executeSteps 消费）。 */
    private record StepSpec(Agent agent, String instruction) {}

    /** 透传给 PromptService：根据智能体名称/描述生成系统提示词。 */
    public String generateAgentPrompt(String name, String description) {
        return promptService.generateAgentPrompt(name, description);
    }

    /**
     * 组装一次请求：人设 System Prompt + 长期记忆（核心事实/滚动摘要） + 已确认参数 + 当前输入，
     * 并给会话记忆 Advisor 传入 conversationId（用于读取与写回历史）。
     * <p>
     * 最终 prompt 顺序为：「人设 + 长期记忆 → 窗口原文（由 advisor 注入） → 当前输入」。
     * conv 由调用方一次查出后传入，本方法不再查库。
     */
    private ChatClient.ChatClientRequestSpec buildRequest(String conversationId, String message, Conversation conv,
                                                          Agent agent, String paramBlock) {
        String systemPrompt = promptService.resolveSystemPrompt(agent)
                + buildLongTermMemoryText(conv)
                + (paramBlock == null ? "" : paramBlock);
        // 数据实时性硬约束：声明了「数据实时性」原则的智能体（如教育数据分析），每轮强制重申重新查库，
        // 防止模型直接引用对话历史中的旧数据作答或出图。
        if (agent != null && systemPrompt.contains("数据实时性")) {
            systemPrompt += REALTIME_DATA_RULE;
        }
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId));
        // 全局能力池（启动时预解析为 ToolCallback，不重复反射），但**只在本次请求已路由/绑定到
        // 具体智能体时才挂载**：普通对话（agent 为空，如"你好"）不挂载任何工具，避免把工具定义
        // 无谓地塞进每一次对话的上下文。是否调用、调哪个工具，仍由 AI 在 agent 范围内自主决定，
        // 不按 Agent 手动写死工具列表（全局 ToolRegistry 自动收集）。
        if (agent != null) {
            spec = spec.tools(toolRegistry.getToolCallbacks());
        }
        return applyAgentOptions(spec, agent);
    }

    /**
     * 判断本次请求应绑定的智能体：显式绑定优先；未绑定的普通会话走智能路由；二者皆无则返回 null（普通对话）。
     */
    private Agent determineAgent(Conversation conv, String message) {
        if (conv != null && conv.getAgentId() != null) {
            return agentService.getAgent(conv.getAgentId());
        }
        if (conv != null) {
            // 传入最近若干轮对话上下文：让路由识别「承接上一轮的短追问」（如上一轮查天气、用户只说「北京呢？」）。
            // 否则失去上下文会被误判为普通对话，导致带工具的智能体无法被路由、进而「无法回答」。
            return agentRouter.route(message, null, buildHistoryContextText(conv.getId(), RECENT_TURNS, null)).agent();
        }
        return null;
    }

    /**
     * 读取会话的长期记忆（用户核心信息 + 滚动摘要）文本。这些内容持久化在 conversation 表。
     */
    private String buildLongTermMemoryText(Conversation conv) {
        if (conv == null) return "";
        StringBuilder sb = new StringBuilder();
        if (conv.getCoreFacts() != null && !conv.getCoreFacts().isBlank()) {
            sb.append("\n\n[长期核心信息] 以下内容来自长期记忆（姓名/身份/偏好/待办等），回答时应优先考虑并遵守：\n")
                    .append(conv.getCoreFacts());
        }
        if (conv.getSummary() != null && !conv.getSummary().isBlank()) {
            sb.append("\n\n[历史摘要] 本次对话更早阶段的摘要（长期记忆，仅供上下文参考，不要复述）：\n")
                    .append(conv.getSummary());
        }
        return sb.toString();
    }

    /** 若会话绑定的智能体带 model/temperature，则把对应参数应用到本次请求。 */
    private ChatClient.ChatClientRequestSpec applyAgentOptions(ChatClient.ChatClientRequestSpec spec, Agent agent) {
        if (agent == null) return spec;
        boolean hasModel = agent.getModel() != null && !agent.getModel().isBlank();
        boolean hasTemp = agent.getTemperature() != null;
        if (!hasModel && !hasTemp) return spec;
        OpenAiChatOptions.Builder ob = OpenAiChatOptions.builder();
        if (hasModel) ob.model(agent.getModel());
        if (hasTemp) ob.temperature(agent.getTemperature());
        return spec.options(ob);
    }
}
