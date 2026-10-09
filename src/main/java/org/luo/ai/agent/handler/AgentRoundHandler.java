package org.luo.ai.agent.handler;

import lombok.extern.slf4j.Slf4j;
import org.luo.ai.constant.AgentBindSource;
import org.luo.ai.entity.Agent;
import org.luo.ai.entity.Conversation;
import org.luo.ai.agent.AgentRouter;
import org.luo.ai.service.AgentService;
import org.luo.ai.chat.ChatComposer;
import org.luo.ai.service.ConversationService;
import org.luo.ai.agent.ParamFillingService;
import org.luo.ai.tool.HandoffTool;
import org.luo.ai.trace.RoundTrace;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.luo.ai.agent.AgentRouter.RouteDecision;
import org.luo.ai.service.ChatService;

/**
 * 普通对话策略（非规划模式）：智能路由 → 话题切换预检 → 参数补全/追问 → 正式回答。
 * 由 {@code ChatService.runRound} 按会话形态选择调用，差异只在进度回调（同步传空回调，流式推 progress 事件）。
 * <p>
 * 需要落库的交互在本类内完成：追问时临时绑定 agent（CLARIFY）并落库追问记录；正式回答后解绑
 * （仅 CLARIFY 绑定、显式绑定保持）。落库/解绑均为毫秒级 DB 操作，发生在 LLM 调用之后，不影响首字感知。
 * <p>
 * 记忆写入契约：信任记忆 Advisor 自动落库，不需要显式补写（{@link RoundResult#needSaveExchange()} 恒为 false）。
 */
@Slf4j
@Service
public class AgentRoundHandler implements RoundHandler {

    /** 智能路由上下文最多取的最近消息条数（路由 LLM 的 token 成本控制）。 */
    private static final int RECENT_TURNS = 6;

    /** 追踪用：处理方来源枚举值（与 agent_trace.route_source 列注释一致）。 */
    private static final String SRC_BOUND = "BOUND";
    private static final String SRC_ROUTE = "ROUTE";
    private static final String SRC_NONE = "NONE";
    /** 智能体主动转交（handoff）：本轮由「被交接的」智能体作答。 */
    private static final String SRC_HANDOFF = "HANDOFF";

    private final ConversationService conversationService;
    private final AgentService agentService;
    private final ParamFillingService paramFillingService;
    private final AgentRouter agentRouter;
    private final ChatComposer composer;

    public AgentRoundHandler(ConversationService conversationService,
                             AgentService agentService,
                             ParamFillingService paramFillingService,
                             AgentRouter agentRouter,
                             ChatComposer composer) {
        this.conversationService = conversationService;
        this.agentService = agentService;
        this.paramFillingService = paramFillingService;
        this.agentRouter = agentRouter;
        this.composer = composer;
    }

    @Override
    public RoundResult handle(Conversation conv, String conversationId, String message, String material,
                             Consumer<String> progress, RoundTrace trace) {
        // 检索问题预取：RAG 开启时立即异步启动「多轮查询改写」，与下面的智能路由 / 参数抽取并行。
        // 前置链原本是「路由 → 参数抽取 → 改写」三段串行模型往返，而改写只看用户原话与会话历史、
        // 与另两段互不依赖，提前并发能压掉整整一个往返的延迟（见 ChatComposer#prefetchRetrievalQuery）。
        // 未开 RAG 返回 null、零额外调用；本轮若走追问分支则结果作废（刻意接受的轻微浪费）。
        CompletableFuture<String> prefetchedQuery = composer.prefetchRetrievalQuery(conversationId, message, conv);
        if (prefetchedQuery != null) {
            // 改写是异步预取，这里只播「已启动」；真正落地要等 resolveQuery join（在 buildRequest 内），
            // 不在此回显结果——改写产物是「检索问题」，用户能感知的是「要不要检索 / 命中什么」，不是中间问句。
            progress.accept("🔎 正在改写检索问句…");
        }
        // 绑定来源：EXPLICIT=用户显式选择（保持粘住，不因话题切换解绑）；CLARIFY=追问流程临时绑定；
        // HANDOFF=智能体主动转交（与显式绑定一样粘住 —— 用户没推翻这次交接之前，它就该继续负责）。
        String bindSource = conv.getAgentBindSource();
        boolean explicitBinding = AgentBindSource.EXPLICIT.equals(bindSource);
        boolean stickyBinding = explicitBinding || AgentBindSource.HANDOFF.equals(bindSource);
        // 路由判定是前置链里最重的一段 LLM 往返（普通会话未绑定时每次都要跑），先给反馈，避免「发送后 2~3 秒空白」。
        if (conv != null && conv.getAgentId() == null) {
            progress.accept("🧭 正在判断走哪个智能体…");
        }
        Agent agent = determineAgent(conv, message);

        // 话题切换预检：仅当会话处于「追问绑定(CLARIFY)」时。携带「待回答的追问」重新审视本轮消息的真实意图，
        // 避免「深圳烧鸡味道怎么样」这类含城市词的新话题被误当成天气补全、进而去查天气。必须放在参数补全之前：
        // 原实现依赖「是否凑齐参数」判断，但新话题里若恰好含城市词会被直接凑齐参数，检测彻底进不去。
        // 由路由 LLM 语义判断三态：continueTask=在回答追问 → 继续补全；命中另一 agent → 转向解绑；
        // none（未命中且未在回答追问）= 新话题且无 agent 可接 → 转普通对话并解绑。
        if (!stickyBinding && conv.getAgentId() != null && AgentBindSource.CLARIFY.equals(conv.getAgentBindSource())) {
            String pendingQuestion = paramFillingService.lastClarifyQuestion(conversationId);
            AgentRouter.RouteDecision rd = agentRouter.route(message, pendingQuestion,
                    composer.buildHistoryContextText(conversationId, RECENT_TURNS, null), conversationId);
            boolean keepBound = rd.continuation()
                    || (rd.agent() != null && rd.agent().getId().equals(agent.getId()));
            if (!keepBound) {
                // 意图已转向：另一个 agent 或普通对话（agent()==null）。解除追问绑定，按新意图处理。
                conversationService.unbindAgent(conversationId);
                agent = rd.agent();
            }
        }

        // 追踪：处理方来源以「本轮实际生效的绑定」为准——显式绑定=BOUND；转交绑定=HANDOFF；
        // 路由/追问命中=ROUTE；其余=通用助手。
        if (trace != null) {
            trace.route(explicitBinding ? SRC_BOUND : (AgentBindSource.HANDOFF.equals(bindSource) ? SRC_HANDOFF
                    : (agent != null ? SRC_ROUTE : SRC_NONE)),
                    agent == null ? null : agent.getAgentCode());
        }

        // 播报本轮由谁处理（同样属于「执行过程」，只展示、不进记忆）；普通闲聊无 agent，不打扰。
        if (agent != null) {
            progress.accept("🤖 已交由智能体「" + agent.getName() + "」处理");
        }

        // 参数抽取/追问判断也是 LLM 往返（带 paramSchema 的智能体才会跑），先给反馈。只有确实进入该环节才播，
        // 避免普通智能体/闲聊也弹一条「正在抽取参数」误导用户。
        if (agent != null && agent.getParamSchema() != null && !agent.getParamSchema().isBlank()) {
            progress.accept("📝 正在识别所需参数…");
        }
        ParamFillingService.ClarifyDecision decision = paramFillingService.decideClarify(conversationId, message, agent);
        if (decision.getQuestion() != null) {
            // 进入追问：把正在补全参数的 agent 临时绑定（CLARIFY），使下一轮回答能复用同一 agent。
            // 状态与消息同一次落库（避免话题切换分支误落库失效的追问）：只落消息不落状态，下一轮就读不到
            // 「问到第几次 / 原始请求是什么 / 已确认哪些参数」——历史被摘要压缩或标记不参与记忆后必然算歪。
            if (agent != null && !stickyBinding) {
                conversationService.bindAgent(conversationId, agent.getId());
            }
            conversationService.saveClarifyState(conversationId, decision.getNextState());
            conversationService.saveClarifyExchange(conversationId, message, decision.getQuestion());
            return RoundResult.clarify(decision.getQuestion());
        }
        // 非追问分支：把可能残留的追问状态显式作废（此时 decision.nextState 恒为 null）。
        // 不能只依赖下面的 unbindAgent —— 它的第二个条件是「非显式绑定」，而显式绑定的智能体根本不走解绑
        // （点智能体卡片开会话就是显式绑定，正是带 paramSchema 的智能体的主路径）⇒ 状态与输入区提示会永久残留，
        // 且下一轮会把「上一轮的原始请求锚点 + 已确认参数」当成新请求的上下文，污染参数抽取。
        // 只在「该智能体带 paramSchema」时才写，避免给无参数智能体 / 普通闲聊每轮白跑一次 UPDATE。
        if (agent != null && agent.getParamSchema() != null && !agent.getParamSchema().isBlank()) {
            conversationService.saveClarifyState(conversationId, decision.getNextState());
        }
        // 常规单智能体回答（动态规划由 planner 会话单独处理，见 PlannerRoundHandler）。
        // message 为纯提问（不含附件），附件材料走 material 注入 system，不进会话记忆。
        // 转交持有者：白名单声明 handoff_agent 时挂载该工具，命中即由目标智能体接力本轮。
        HandoffTool.HandoffHolder handoff = new HandoffTool.HandoffHolder();
        ChatComposer.ComposedRequest composed = composer.buildRequest(conversationId, message, conv, agent,
                paramFillingService.buildParamBlock(decision), material, trace, prefetchedQuery, handoff);
        ChatClient.ChatClientRequestSpec spec = composed.spec();
        // content() 标注 @Nullable（模型可能只产出工具调用而无正文）：必须在此收口，否则 null 会走到同步接口的
        // Map.of("content", reply) —— Map.of 拒绝 null 值 → NPE → 500。与流式路径 emitChunks 的归一口径一致。
        String reply = spec.call().content();
        if (reply == null) reply = "";

        // 转交接力：原智能体把会话交了出去 —— 落 HANDOFF 绑定（粘住），并用目标智能体的完整人设重跑本轮。
        if (handoff.handedOff()) {
            Agent target = handoff.target();
            progress.accept("🔁 已把会话转交给智能体「" + target.getName() + "」，由它接续回答");
            conversationService.bindAgentHandoff(conversationId, target.getId());
            if (trace != null) {
                trace.route(SRC_HANDOFF, target.getAgentCode());
            }
            ChatComposer.HandoffOutcome relayed = composer.handoffAnswer(
                    conversationId, message, conv, target, handoff.reason(), material, trace);
            if (relayed.reply().isBlank()) {
                // 接手方没产出：保留原智能体的转交说明（至少让用户知道发生了什么），不谎报成功
                log.warn("转交接力：目标智能体「{}」未产出内容，保留转交说明", target.getAgentCode());
                return RoundResult.answer(reply, composed.citations()).withRecall(composed.recallJson());
            }
            // 覆盖刚刚被记忆 Advisor 写下的「转交说明」：库里与界面必须是同一个答案（否则下一个 turn 的
            // 模型会读到一段用户根本没见过的内容 —— 典型的事实漂移）
            conversationService.overwriteLatestAssistantMessage(conversationId, relayed.reply());
            return RoundResult.answer(relayed.reply(), relayed.citations()).withRecall(composed.recallJson());
        }

        // 路由命中的 agent 在完成回答后解绑，恢复后续轮的正常智能路由；显式绑定 / 转交绑定的保持不变。
        if (!stickyBinding) conversationService.unbindAgent(conversationId);
        // 带上本轮 RAG 引用（由 ChatService 落库到本轮 assistant 消息、前端渲染角标）与跨会话召回
        // （仅推 SSE recall 事件供展示，不落库）。召回为空时 withRecall 原样返回，不影响其余字段。
        return RoundResult.answer(reply, composed.citations()).withRecall(composed.recallJson());
    }

    /** 判断本次请求应绑定的智能体：显式绑定优先；未绑定的普通会话走智能路由；二者皆无则返回 null（普通对话）。 */
    private Agent determineAgent(Conversation conv, String message) {
        if (conv != null && conv.getAgentId() != null) {
            return agentService.getAgent(conv.getAgentId());
        }
        if (conv != null) {
            // 传入最近若干轮上下文：让路由识别「承接上一轮的短追问」（如上一轮查天气、用户只说「北京呢？」）。
            // 否则失去上下文会被误判为普通对话，导致带工具的智能体无法被路由、进而「无法回答」。
            return agentRouter.route(message, null,
                    composer.buildHistoryContextText(conv.getId(), RECENT_TURNS, null), conv.getId()).agent();
        }
        return null;
    }
}
