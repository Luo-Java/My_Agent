package org.luo.ai.chat;

import lombok.extern.slf4j.Slf4j;
import org.luo.ai.advisor.RoundTraceAdvisor;
import org.luo.ai.advisor.ToolUsageLoggingAdvisor;
import org.luo.ai.properties.PromptProperties;
import org.luo.ai.dto.KbCitation;
import org.luo.ai.entity.Agent;
import org.luo.ai.entity.Conversation;
import org.luo.ai.service.ConversationFactService;
import org.luo.ai.service.CrossSessionSearchService;
import org.luo.ai.service.KbSearchService;
import org.luo.ai.tool.HandoffTool;
import org.luo.ai.tool.SubAgentTool;
import org.luo.ai.tool.ToolApprovalGate;
import org.luo.ai.tool.ToolRegistry;
import org.luo.ai.trace.RoundTrace;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.luo.ai.agent.PromptService;
import org.luo.ai.agent.QueryRewriteService;

/**
 * LLM 请求组装器：把智能体 / 记忆 / 工具组装成一次 ChatClient 请求，供普通对话与规划执行两条路径共用。
 * <p>
 * 持有两个 ChatClient：{@code chatClient} 带会话记忆（MessageChatMemoryAdvisor 自动读写历史）；
 * {@code internalChatClient} 无记忆，规划中间步骤专用（避免「指令 + 上一步输出」的合成串写进历史）。
 * <p>
 * 工具挂载由 {@link #decorateRequest} 门控：普通对话不挂；智能体对话按 {@code tools_json} 装配声明
 * （未配置=全量、{@code []}=不挂、白名单=按名取子集，见 {@link ToolRegistry#resolve(String)}），
 * 避免闲聊/翻译类智能体看到 SQL、天气等无关工具。动态工具（{@code call_agent}）另走
 * {@link ToolRegistry#dynamicToolRequested} 判定，需白名单显式声明，实例每轮由 {@link SubAgentTool} 现构。
 * <p>
 * <b>两个 Advisor 分工</b>：{@link ToolUsageLoggingAdvisor} 只打日志；{@link RoundTraceAdvisor} 把工具调用与
 * token 写进当轮 {@link RoundTrace}，后者需要 trace，故由 {@link #decorateRequest} 塞进 advisor 上下文
 * （键 {@link RoundTrace#CONTEXT_KEY}）——规划模式每一步因此也能各自被追踪，无需线程局部变量。
 */
@Slf4j
@Service
public class ChatComposer {

    /** 预取改写的等待上限（秒）：超时即放弃、改用用户原话检索。远小于模型全局超时（60s），正常路径不产生实际等待。 */
    private static final long REWRITE_JOIN_TIMEOUT_SECONDS = 10;

    /** 空进度回调：无 trace（同步接口 / 无事件通道）时使用。 */
    private static final Consumer<String> NO_PROGRESS = text -> {
    };

    /** 数据实时性强化规则：仅对声明了该原则的智能体追加（软约束之外再以即时指令重申「先查库」），文本外置于 agent.prompt.realtime-rule。 */
    private final String realtimeDataRule;

    /** 带会话记忆的 ChatClient（通过 MessageChatMemoryAdvisor 自动读写历史）。 */
    private final ChatClient chatClient;
    /** 不带会话记忆的 ChatClient：动态规划中间步骤专用，避免中间产物被写进会话历史。 */
    private final ChatClient internalChatClient;
    private final PromptService promptService;
    private final ToolRegistry toolRegistry;
    /** 转交工具（{@code call_agent}）：实例依赖调用方智能体，故由本类每轮现场构造（见 {@link SubAgentTool}）。 */
    private final SubAgentTool subAgentTool;
    /** 会话转交工具（{@code handoff_agent}）：与上者的区别见 {@link HandoffTool}（调一次 vs 交出去）。 */
    private final HandoffTool handoffTool;
    /** 工具审批闸门：把声明要审批的工具包一层（配置关闭时零开销，见 {@link ToolApprovalGate}）。 */
    private final ToolApprovalGate approvalGate;
    private final ChatMemory chatMemory;
    private final KbSearchService kbSearchService;
    private final QueryRewriteService queryRewriteService;
    /** 跨会话召回：在本人其他会话的历史消息里做关键词匹配（与知识库检索是两条独立链路，见其类注释）。 */
    private final CrossSessionSearchService crossSessionSearchService;
    /** 长期事实条目：注入侧的事实来源（见 {@link #buildLongTermMemoryText}）。 */
    private final ConversationFactService factService;
    /** 检索问题改写的预取线程池（见 {@link #prefetchRetrievalQuery}）。 */
    private final Executor prefetchExecutor;

    public ChatComposer(ChatClient.Builder chatClientBuilder,
                        MessageChatMemoryAdvisor memoryAdvisor,
                        ToolUsageLoggingAdvisor toolUsageLoggingAdvisor,
                        RoundTraceAdvisor roundTraceAdvisor,
                        PromptService promptService,
                        ToolRegistry toolRegistry,
                        SubAgentTool subAgentTool,
                        HandoffTool handoffTool,
                        ToolApprovalGate approvalGate,
                        ChatMemory chatMemory,
                        PromptProperties promptProperties,
                        KbSearchService kbSearchService,
                        QueryRewriteService queryRewriteService,
                        CrossSessionSearchService crossSessionSearchService,
                        ConversationFactService factService,
                        @Qualifier("roundPrefetchExecutor") Executor prefetchExecutor) {
        // 中间步骤客户端：先 clone（须在 defaultAdvisors 之前，避免继承记忆 Advisor）
        this.internalChatClient = chatClientBuilder.clone()
                .defaultAdvisors(toolUsageLoggingAdvisor, roundTraceAdvisor).build();
        this.chatClient = chatClientBuilder
                .defaultAdvisors(memoryAdvisor, toolUsageLoggingAdvisor, roundTraceAdvisor).build();
        this.promptService = promptService;
        this.toolRegistry = toolRegistry;
        this.subAgentTool = subAgentTool;
        this.handoffTool = handoffTool;
        this.approvalGate = approvalGate;
        this.chatMemory = chatMemory;
        this.realtimeDataRule = promptProperties.realtimeRule();
        this.kbSearchService = kbSearchService;
        this.queryRewriteService = queryRewriteService;
        this.crossSessionSearchService = crossSessionSearchService;
        this.factService = factService;
        this.prefetchExecutor = prefetchExecutor;
    }

    /** 带记忆的 ChatClient：普通对话 / 通用助手兜底回答使用。 */
    public ChatClient chatClient() {
        return chatClient;
    }

    /** 无记忆的 ChatClient：动态规划中间步骤专用。 */
    public ChatClient internalChatClient() {
        return internalChatClient;
    }

    /**
     * 造审批闸门上下文（透传 {@link ToolApprovalGate#context}）：规划步骤与评审候选各自组装请求，
     * 需要与普通对话同一把闸门。闸门关闭时返回 {@code null}，调用方零分支。
     *
     * @param message 触发本轮的<b>用户原话</b>（规划逐步模式传原始目标；批准后前端据此重跑）
     */
    public ToolApprovalGate.GateContext approvalContext(String conversationId, Agent agent, String message) {
        return approvalGate.context(conversationId, agent, message);
    }

    /**
     * 组装一次普通对话请求：人设 + 长期记忆 + 知识库资料 + 已确认参数 + 当前输入，并把 conversationId
     * 传给记忆 Advisor。prompt 顺序：人设/长期记忆 → 知识库资料 → 窗口原文（advisor 注入） → 当前输入。
     * RAG 是否检索由会话级开关 {@code conv.ragEnabled} 决定（见 {@link #retrieve}）。
     *
     * @param trace 本轮追踪上下文（可 null）
     */
    public ComposedRequest buildRequest(String conversationId, String message, Conversation conv,
                                       Agent agent, String paramBlock, String material, RoundTrace trace) {
        return buildRequest(conversationId, message, conv, agent, paramBlock, material, trace, null);
    }

    /**
     * 同上，带「检索问题」预取结果：由调用方在本轮最前发起（见 {@link #prefetchRetrievalQuery}），
     * 走到这里通常早已完成，join 几乎不产生等待；传 {@code null} 时退回同步改写。
     */
    public ComposedRequest buildRequest(String conversationId, String message, Conversation conv,
                                       Agent agent, String paramBlock, String material, RoundTrace trace,
                                       CompletableFuture<String> prefetchedQuery) {
        return buildRequest(conversationId, message, conv, agent, paramBlock, material, trace, prefetchedQuery, null);
    }

    /**
     * 同上，另带「会话转交」持有者：非 null 时按白名单决定是否挂 {@code handoff_agent} 工具，
     * 工具命中会写入 holder，由调用方（{@code AgentRoundHandler}）执行接力。
     */
    public ComposedRequest buildRequest(String conversationId, String message, Conversation conv,
                                       Agent agent, String paramBlock, String material, RoundTrace trace,
                                       CompletableFuture<String> prefetchedQuery,
                                       HandoffTool.HandoffHolder handoffHolder) {
        KbSearchService.KbContext kb = retrieve(conversationId, message, conv, agent, trace, prefetchedQuery);
        // 跨会话召回：只作用于普通对话（规划步骤与规划回退不走这里，见 CrossSessionSearchService 类注释）
        CrossSessionSearchService.Recall recall = recall(conversationId, message, conv, trace);
        String systemPrompt = buildRoundSystemPrompt(agent, conv, kb.text(), recall.text(), material, paramBlock);
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId));
        // 审批闸门上下文：带上「本轮用户原话」，批准后前端据此重跑同一轮（见 ToolApprovalGate.GateContext）
        ToolApprovalGate.GateContext gate = approvalGate.context(conversationId, agent, message);
        return new ComposedRequest(decorateRequest(spec, agent, trace, conversationId, handoffHolder, gate),
                kb.citations(), recall);
    }

    /**
     * 转交接力回答：由<b>目标智能体</b>用完整人设重跑本轮。
     * <p>
     * <b>为什么走无记忆客户端 + 显式注入历史</b>：本轮的用户消息已经被记忆 Advisor 落过一次库，
     * 这里若再用带记忆的客户端，同一条用户消息会被再写一遍，历史里就出现两条一模一样的提问
     * （而模型会把它们当成用户真的问了两遍）。无记忆客户端只在提示词里显式拼一段历史，
     * 既让接手方看得到来龙去脉，又不产生任何多余的落库。
     * <p>
     * 题外：本方法<b>不做</b>落库，接力回答由调用方覆盖写回那条「转交说明」助手消息
     * （{@code ConversationService#overwriteLatestAssistantMessage}），保证库里与界面是同一个答案。
     */
    public HandoffOutcome handoffAnswer(String conversationId, String message, Conversation conv, Agent target,
                                        String reason, String material, RoundTrace trace) {
        KbSearchService.KbContext kb = retrieve(conversationId, message, conv, target, trace, null);
        String history = buildHistoryContextText(conversationId, 0,
                "[此前的对话历史（你刚接手这个会话，请据此接续）]\n");
        String systemPrompt = buildRoundSystemPrompt(target, conv, kb.text(), null, material, null);
        if (!history.isBlank()) {
            systemPrompt = systemPrompt + "\n\n" + history;
        }
        systemPrompt = systemPrompt + "\n\n[接手说明] 你刚刚从另一个智能体那里接过这段对话"
                + (reason == null || reason.isBlank() ? "" : "（转交原因：" + reason + "）")
                + "。请直接回答用户最近这条消息，不要评论转交这件事本身。";
        ChatClient.ChatClientRequestSpec spec = internalChatClient.prompt()
                .system(systemPrompt)
                .user(message);
        // 接力这一轮同样过闸门：它照样是「拿用户的提问去驱动工具」，放过它就等于闸门只挡了前半程
        ToolApprovalGate.GateContext gate = approvalGate.context(conversationId, target, message);
        String reply = decorateRequest(spec, target, trace, conversationId, null, gate).call().content();
        return new HandoffOutcome(reply == null ? "" : reply, kb.citations());
    }

    /** 转交接力的产出：接手方的回答 + 它自己这一轮的检索引用（引用由它这轮检索产生，不能沿用交棒方的）。 */
    public record HandoffOutcome(String reply, List<KbCitation> citations) {
    }

    /**
     * 拼装一轮对话的 system prompt（<b>唯一的拼装口径</b>）：人设 → 长期记忆 → 知识库资料 → 跨会话回忆
     * → 已确认参数 → 当轮附件材料。
     * <p>
     * 抽成公共方法的理由：并行评审要为每个候选各拼一份 system（同样的前缀，只有人设不同），若那份自己
     * 拼一遍，两边一旦漂移就会出现「普通对话带了长期记忆、评审候选没带」这类只有对比才发现的问题。
     * 顺序在本方法内固定，新增素材（如跨会话回忆）只需改这一处。
     *
     * @param kbText     知识库资料文本（可为 null/空）
     * @param recallText 跨会话回忆文本（可为 null/空）
     * @param paramBlock 已确认参数块（可为 null/空）
     * @param material   当轮附件材料（可为 null/空）
     */
    public String buildRoundSystemPrompt(Agent agent, Conversation conv, String kbText, String recallText,
                                         String material, String paramBlock) {
        String systemPrompt = applyRealtimeRule(promptService.resolveSystemPrompt(agent)
                + buildLongTermMemoryText(conv)
                + blankToEmpty(kbText)
                + blankToEmpty(recallText)
                + blankToEmpty(paramBlock));
        // 附件材料只进当轮 system（记忆 Advisor 仅持久化 .user() 纯提问）
        return withMaterial(systemPrompt, material);
    }

    /** null/空白归一为空串（拼接友好）。 */
    private static String blankToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 通用助手兜底请求（无智能体、不挂工具）：规划回退或目标与任何智能体无关时使用；同样跟随会话 RAG 开关（只检索全局库）。 */
    public ComposedRequest buildDefaultRequest(Conversation conv, String conversationId, String message,
                                              String material, RoundTrace trace) {
        KbSearchService.KbContext kb = retrieve(conversationId, message, conv, null, trace, null);
        // 走同一个拼装口径（人设=通用助手、无参数块、无跨会话回忆）：规划回退路径不召回历史会话
        String systemPrompt = buildRoundSystemPrompt(null, conv, kb.text(), null, material, null);
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId));
        return new ComposedRequest(decorateRequest(spec, null, trace, conversationId), kb.citations());
    }

    /** 附件材料追加到 system 末尾（仅当轮可见：system 每轮重建、不落库）。 */
    private static String withMaterial(String systemPrompt, String material) {
        if (material == null || material.isBlank()) return systemPrompt;
        return systemPrompt + "\n\n[本轮附件材料] 以下为用户本轮上传的内容（图片已识别、文档已解析为文本），"
                + "仅作为本次回答参考，不写入长期记忆：\n" + material;
    }

    /** 解析智能体系统提示词（透传 PromptService；agent 为 null 时返回通用助手提示词）。 */
    public String buildSystemPrompt(Agent agent) {
        return promptService.resolveSystemPrompt(agent);
    }

    /** 组装本轮知识库资料（透传 KbSearchService）：返回「资料文本 + 引用来源」，所有路径复用。 */
    public KbSearchService.KbContext buildKbContext(Boolean ragEnabled, Agent agent, String query) {
        return kbSearchService.buildKbContext(ragEnabled, agent, query);
    }

    /**
     * 本轮知识库检索统一入口：会话开关 → 多轮查询改写 → 检索。改写结果与用户原话不同时才写入 trace
     * （{@code retrieval_query}，null=未改写）。规划步骤不走此方法（其 query 是合成串，用历史消解只会引入噪声）。
     */
    private KbSearchService.KbContext retrieve(String conversationId, String message, Conversation conv,
                                               Agent agent, RoundTrace trace,
                                               CompletableFuture<String> prefetchedQuery) {
        if (!ragOn(conv)) return KbSearchService.KbContext.EMPTY;
        String query = resolveQuery(conversationId, message, prefetchedQuery);
        if (trace != null && !query.equals(message)) {
            trace.retrievalQuery(query);
        }
        KbSearchService.KbContext kb = kbSearchService.buildKbContext(true, agent, query);
        // 检索命中播报：命中数对用户可见（0 命中与无 RAG 的「完全静默」要能区分开，便于判断是否该换个问法）
        if (trace != null) {
            int hit = kb.citations().size();
            trace.reportProgress(hit > 0 ? ("📚 知识库检索命中 " + hit + " 条") : "📚 知识库检索未命中");
        }
        return kb;
    }

    /**
     * 预取「检索问题」：RAG 开启时把改写提交到专用线程池，与路由/参数抽取并行。RAG 未开返回 {@code null}；
     * 线程池拒绝时返回 null 并回退同步改写。预取只加速、不承担正确性。走了追问分支时会作废（刻意取舍）。
     */
    public CompletableFuture<String> prefetchRetrievalQuery(String conversationId, String message,
                                                            Conversation conv) {
        if (!ragOn(conv) || message == null || message.isBlank()) return null;
        try {
            return CompletableFuture.supplyAsync(
                    () -> queryRewriteService.rewrite(conversationId, message), prefetchExecutor);
        } catch (RuntimeException e) {
            log.warn("检索问题预取提交失败，本轮回退同步改写：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 取本轮实际检索问题：优先消费预取，未预取 → 同步改写、超时/中断/异常 → 用户原话。
     * 任何分支都返回可直接检索的字符串，<b>绝不阻断对话</b>。
     */
    private String resolveQuery(String conversationId, String message, CompletableFuture<String> prefetchedQuery) {
        if (prefetchedQuery == null) {
            return queryRewriteService.rewrite(conversationId, message);
        }
        try {
            return prefetchedQuery.get(REWRITE_JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("检索问题预取超时（{}s），本轮改用用户原话检索", REWRITE_JOIN_TIMEOUT_SECONDS);
            prefetchedQuery.cancel(true);
            return message;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("检索问题预取等待被中断，本轮改用用户原话检索");
            return message;
        } catch (Exception e) {
            log.warn("检索问题预取失败，本轮改用用户原话检索：{}", e.getMessage());
            return message;
        }
    }

    /**
     * 跨会话召回统一入口：会话开关 → 提词 → 关键词检索。与 {@link #retrieve} 完全对称（含进度播报口径），
     * 区别只在「检索对象」：这里是用户自己的历史会话，不是知识库。
     * <p>
     * 用户身份取自 {@code conv.userId}（该实体已由 {@code ConversationService} 做过归属校验），
     * 因此不需要再往这一层传 userId —— 与「身份一律从 HTTP 线程取出」的原则不冲突：
     * 这里用的不是 ThreadLocal，而是已校验实体的字段。
     */
    private CrossSessionSearchService.Recall recall(String conversationId, String message,
                                                    Conversation conv, RoundTrace trace) {
        if (conv == null || !Boolean.TRUE.equals(conv.getCrossSession())) {
            return CrossSessionSearchService.Recall.EMPTY;
        }
        // 进度走 trace 通道（与 RAG 命中播报同一机制）：同步接口无 trace 时静默，不影响正确性
        Consumer<String> progress = trace == null ? NO_PROGRESS : trace::reportProgress;
        return crossSessionSearchService.recall(conversationId, message, conv.getUserId(), progress);
    }

    /** 会话级 RAG 开关取值（null 视为关闭）。 */
    public boolean ragOn(Conversation conv) {
        return conv != null && Boolean.TRUE.equals(conv.getRagEnabled());
    }

    /**
     * 按智能体装配工具、应用其模型参数，并把当轮 trace 挂进 advisor 上下文。仅路由/绑定到智能体时才挂工具
     * （未配置=全量、{@code []}=不挂、白名单=按名取子集）；显式声明不用工具时跳过 {@code .tools()}。
     * <p>
     * <b>动态工具另算一路</b>：{@code call_agent} 的实例依赖「调用方是谁」（候选清单要排除自己），
     * 且候选随库变化，所以不能进 {@link ToolRegistry} 的静态池——此处按白名单显式声明现场构造并与静态工具<b>合并</b>。
     * 合并后统一 {@code .tools()} 挂载：分两次调用后者会覆盖前者。
     *
     * @param conversationId 当前会话 id（动态工具用于成本流水归属；静态工具路径不使用）
     */
    public ChatClient.ChatClientRequestSpec decorateRequest(ChatClient.ChatClientRequestSpec spec, Agent agent,
                                                            RoundTrace trace, String conversationId) {
        return decorateRequest(spec, agent, trace, conversationId, null, null);
    }

    /**
     * 同上，另带「会话转交」持有者：非 null 且白名单声明了 {@code handoff_agent} 时一并挂载该工具。
     */
    public ChatClient.ChatClientRequestSpec decorateRequest(ChatClient.ChatClientRequestSpec spec, Agent agent,
                                                            RoundTrace trace, String conversationId,
                                                            HandoffTool.HandoffHolder handoffHolder) {
        return decorateRequest(spec, agent, trace, conversationId, handoffHolder, null);
    }

    /**
     * 同上，另带「审批闸门」上下文：非 null 时，落在 {@code agent.tool-approval.tools} 清单里的工具会被
     * 包一层 —— 执行前先问过用户（见 {@link ToolApprovalGate}）。传 {@code null} 即不设闸门。
     * <p>
     * 闸门放在这里而不是各工具内部：这是全项目<b>唯一</b>的挂工具点（注解式、动态式、将来新增的都从这儿过），
     * 只有一处就不会漏 —— 漏一处的闸门等于没有闸门。
     */
    public ChatClient.ChatClientRequestSpec decorateRequest(ChatClient.ChatClientRequestSpec spec, Agent agent,
                                                            RoundTrace trace, String conversationId,
                                                            HandoffTool.HandoffHolder handoffHolder,
                                                            ToolApprovalGate.GateContext gate) {
        if (trace != null) {
            spec = spec.advisors(a -> a.param(RoundTrace.CONTEXT_KEY, trace));
        }
        if (agent == null) return spec;
        ToolCallback[] staticTools = toolRegistry.resolve(agent.getToolsJson());
        // 动态工具：实例依赖调用方（候选清单要排除自己），故不能进静态池，只能按白名单现场构造
        List<ToolCallback> dynamicTools = new ArrayList<>(2);
        if (toolRegistry.dynamicToolRequested(agent.getToolsJson(), SubAgentTool.TOOL_NAME)) {
            dynamicTools.add(subAgentTool.build(agent, conversationId));
        }
        if (handoffHolder != null
                && toolRegistry.dynamicToolRequested(agent.getToolsJson(), HandoffTool.TOOL_NAME)) {
            dynamicTools.add(handoffTool.build(agent, handoffHolder));
        }
        int total = staticTools.length + dynamicTools.size();
        if (total > 0) {
            // 静态与动态合并后一次性挂载：分两次调用 .tools() 后者会覆盖前者
            ToolCallback[] tools = new ToolCallback[total];
            System.arraycopy(staticTools, 0, tools, 0, staticTools.length);
            for (int i = 0; i < dynamicTools.size(); i++) {
                tools[staticTools.length + i] = dynamicTools.get(i);
            }
            // 合并之后过闸门：动态工具（call_agent / handoff_agent）也在这个数组里，一并受管
            tools = approvalGate.wrap(tools, gate);
            // 显式 (Object[]) 传参：消除 tools(ToolCallback...) 的 varargs 提示性告警（@SuppressWarnings 实测无效）
            spec = spec.tools((Object[]) tools);
        }
        return applyAgentOptions(spec, agent);
    }

    /**
     * 数据实时性标记短语：系统提示词含此短语即视为声明了该原则（见 {@link #applyRealtimeRule}）。
     * 与 prompts.yaml / DB 中的提示词<b>强耦合</b>，改提示词需同步改这里。
     */
    static final String REALTIME_MARKER = "数据实时性";

    /** 对声明了数据实时性的智能体，每轮追加硬约束（防止直接引用历史旧数据作答）。 */
    public String applyRealtimeRule(String systemPrompt) {
        return systemPrompt.contains(REALTIME_MARKER) ? systemPrompt + "\n\n" + realtimeDataRule : systemPrompt;
    }

    /**
     * 会话历史格式化为文本块（"用户：x / 助手：y"）。{@code maxMessages <= 0} 取全部（规划最后一步注入用）；
     * &gt; 0 取最近 N 条（路由判断短追问用，控 token）。失败返回空串。
     */
    public String buildHistoryContextText(String conversationId, int maxMessages, String header) {
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

    /**
     * 长期记忆文本：<b>事实段</b>（逐条条目优先、旧版归档兜底）+ 滚动摘要。
     * <p>
     * <b>为什么事实在这里现查，而不是由调用方传进来</b>：本方法被四条路径共用 —— 普通对话、规划每一步、
     * 评审每个候选。改成传参就得在四个调用点各查一次并记得传；漏传的地方不会报错，只会静默少一段记忆
     * （正是本项目最忌讳的那类 bug）。现查的代价是每轮多一次按会话索引的等值查询（行数在几十以内）。
     * <p>
     * 「条目优先、归档兜底」的判据与两种块头都在 {@link ConversationFactService#injectableFactsText}，
     * 与追踪侧报的字符数是<b>同一段文本</b>。
     */
    public String buildLongTermMemoryText(Conversation conv) {
        if (conv == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(factService.injectableFactsText(conv.getId(), conv.getCoreFacts()));
        if (conv.getSummary() != null && !conv.getSummary().isBlank()) {
            sb.append("\n\n[历史摘要] 本次对话更早阶段的摘要（长期记忆，仅供上下文参考，不要复述）：\n")
                    .append(conv.getSummary());
        }
        return sb.toString();
    }

    /** 若绑定的智能体带 model/temperature，则把对应参数应用到本次请求。 */
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

    /**
     * 一次组装的产物：请求规格 + 本轮引用来源 + 跨会话召回。citations 必须与请求一起返回——编号在拼 system
     * 时生成，拆两次算会导致序号与来源漂移；{@code recall} 同理（它既注入 system、又要推 SSE 事件给前端展示），
     * 故一并回传而不是调用方自己再跑一遍。
     */
    public record ComposedRequest(ChatClient.ChatClientRequestSpec spec, List<KbCitation> citations,
                                  CrossSessionSearchService.Recall recall) {

        /** 无跨会话召回的构造（规划回退等路径使用）。 */
        public ComposedRequest(ChatClient.ChatClientRequestSpec spec, List<KbCitation> citations) {
            this(spec, citations, CrossSessionSearchService.Recall.EMPTY);
        }

        /** 是否发生了跨会话召回（调用方据此决定要不要推 {@code recall} 事件）。 */
        public boolean hasRecall() {
            return recall != null && !recall.isEmpty();
        }

        /** 召回事件载荷；无召回返回 null（{@code RoundResult.withRecall} 对空串同样忽略）。 */
        public String recallJson() {
            return hasRecall() ? recall.json() : null;
        }
    }
}
