package org.luo.agent.handler;

import lombok.extern.slf4j.Slf4j;
import org.luo.constant.AgentBindSource;
import org.luo.entity.Agent;
import org.luo.entity.Conversation;
import org.luo.agent.AgentRouter;
import org.luo.service.AgentService;
import org.luo.chat.ChatComposer;
import org.luo.service.ConversationService;
import org.luo.agent.ParamFillingService;
import org.luo.trace.RoundTrace;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.luo.agent.AgentRouter.RouteDecision;
import org.luo.service.ChatService;

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
        // 绑定来源：EXPLICIT=用户显式选择（保持粘住，不因话题切换解绑）；CLARIFY=追问流程临时绑定。
        boolean explicitBinding = AgentBindSource.EXPLICIT.equals(conv.getAgentBindSource());
        Agent agent = determineAgent(conv, message);

        // 话题切换预检：仅当会话处于「追问绑定(CLARIFY)」时。携带「待回答的追问」重新审视本轮消息的真实意图，
        // 避免「深圳烧鸡味道怎么样」这类含城市词的新话题被误当成天气补全、进而去查天气。必须放在参数补全之前：
        // 原实现依赖「是否凑齐参数」判断，但新话题里若恰好含城市词会被直接凑齐参数，检测彻底进不去。
        // 由路由 LLM 语义判断三态：continueTask=在回答追问 → 继续补全；命中另一 agent → 转向解绑；
        // none（未命中且未在回答追问）= 新话题且无 agent 可接 → 转普通对话并解绑。
        if (!explicitBinding && conv.getAgentId() != null && AgentBindSource.CLARIFY.equals(conv.getAgentBindSource())) {
            String pendingQuestion = paramFillingService.lastClarifyQuestion(conversationId);
            AgentRouter.RouteDecision rd = agentRouter.route(message, pendingQuestion,
                    composer.buildHistoryContextText(conversationId, RECENT_TURNS, null));
            boolean keepBound = rd.continuation()
                    || (rd.agent() != null && rd.agent().getId().equals(agent.getId()));
            if (!keepBound) {
                // 意图已转向：另一个 agent 或普通对话（agent()==null）。解除追问绑定，按新意图处理。
                conversationService.unbindAgent(conversationId);
                agent = rd.agent();
            }
        }

        // 追踪：处理方来源以「本轮实际生效的绑定」为准——显式绑定=BOUND；路由/追问命中=ROUTE；其余=通用助手。
        if (trace != null) {
            trace.route(explicitBinding ? SRC_BOUND : (agent != null ? SRC_ROUTE : SRC_NONE),
                    agent == null ? null : agent.getAgentCode());
        }

        // 播报本轮由谁处理（同样属于「执行过程」，只展示、不进记忆）；普通闲聊无 agent，不打扰。
        if (agent != null) {
            progress.accept("🤖 已交由智能体「" + agent.getName() + "」处理");
        }

        ParamFillingService.ClarifyDecision decision = paramFillingService.decideClarify(conversationId, message, agent);
        if (decision.getQuestion() != null) {
            // 进入追问：把正在补全参数的 agent 临时绑定（CLARIFY），使下一轮回答能复用同一 agent。
            // 落库由 saveClarifyExchange 统一完成（避免话题切换分支误落库失效的追问）。
            if (agent != null && !explicitBinding) {
                conversationService.bindAgent(conversationId, agent.getId());
            }
            conversationService.saveClarifyExchange(conversationId, message, decision.getQuestion());
            return RoundResult.clarify(decision.getQuestion());
        }
        // 常规单智能体回答（动态规划由 planner 会话单独处理，见 PlannerRoundHandler）。
        // message 为纯提问（不含附件），附件材料走 material 注入 system，不进会话记忆。
        ChatComposer.ComposedRequest composed = composer.buildRequest(conversationId, message, conv, agent,
                paramFillingService.buildParamBlock(decision), material, trace, prefetchedQuery);
        ChatClient.ChatClientRequestSpec spec = composed.spec();
        // content() 标注 @Nullable（模型可能只产出工具调用而无正文）：必须在此收口，否则 null 会走到同步接口的
        // Map.of("content", reply) —— Map.of 拒绝 null 值 → NPE → 500。与流式路径 emitChunks 的归一口径一致。
        String reply = spec.call().content();
        if (reply == null) reply = "";
        // 路由命中的 agent 在完成回答后解绑，恢复后续轮的正常智能路由；显式绑定的保持不变。
        if (!explicitBinding) conversationService.unbindAgent(conversationId);
        // 带上本轮 RAG 引用：由 ChatService 落库到本轮 assistant 消息（前端渲染角标用）
        return RoundResult.answer(reply, composed.citations());
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
                    composer.buildHistoryContextText(conv.getId(), RECENT_TURNS, null)).agent();
        }
        return null;
    }
}
