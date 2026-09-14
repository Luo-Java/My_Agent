package org.luo.chat;

import lombok.extern.slf4j.Slf4j;
import org.luo.advisor.RoundTraceAdvisor;
import org.luo.advisor.ToolUsageLoggingAdvisor;
import org.luo.config.PromptProperties;
import org.luo.dto.KbCitation;
import org.luo.entity.Agent;
import org.luo.entity.Conversation;
import org.luo.service.KbSearchService;
import org.luo.tool.ToolRegistry;
import org.luo.trace.RoundTrace;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.luo.agent.PromptService;
import org.luo.agent.QueryRewriteService;
import org.luo.agent.handler.AgentRoundHandler;
import org.luo.agent.handler.PlannerRoundHandler;

/**
 * LLM 请求组装器：把智能体 / 记忆 / 工具组装成一次 ChatClient 请求，供普通对话与规划执行两条路径共用。
 * <p>
 * 持有两个 ChatClient：{@code chatClient} 带会话记忆（MessageChatMemoryAdvisor 自动读写历史）；
 * {@code internalChatClient} 无记忆，规划中间步骤专用（避免「指令 + 上一步输出」的合成串写进历史）。
 * <p>
 * 工具挂载由 {@link #decorateRequest} 门控：普通对话不挂；智能体对话按 {@code tools_json} 装配声明
 * （未配置=全量、{@code []}=不挂、白名单=按名取子集，见 {@link ToolRegistry#resolve(String)}），
 * 避免闲聊/翻译类智能体看到 SQL、天气等无关工具。
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

    /** 数据实时性强化规则：仅对声明了该原则的智能体追加（软约束之外再以即时指令重申「先查库」），文本外置于 agent.prompt.realtime-rule。 */
    private final String realtimeDataRule;

    /** 带会话记忆的 ChatClient（通过 MessageChatMemoryAdvisor 自动读写历史）。 */
    private final ChatClient chatClient;
    /** 不带会话记忆的 ChatClient：动态规划中间步骤专用，避免中间产物被写进会话历史。 */
    private final ChatClient internalChatClient;
    private final PromptService promptService;
    private final ToolRegistry toolRegistry;
    private final ChatMemory chatMemory;
    private final KbSearchService kbSearchService;
    private final QueryRewriteService queryRewriteService;
    /** 检索问题改写的预取线程池（见 {@link #prefetchRetrievalQuery}）。 */
    private final Executor prefetchExecutor;

    public ChatComposer(ChatClient.Builder chatClientBuilder,
                        MessageChatMemoryAdvisor memoryAdvisor,
                        ToolUsageLoggingAdvisor toolUsageLoggingAdvisor,
                        RoundTraceAdvisor roundTraceAdvisor,
                        PromptService promptService,
                        ToolRegistry toolRegistry,
                        ChatMemory chatMemory,
                        PromptProperties promptProperties,
                        KbSearchService kbSearchService,
                        QueryRewriteService queryRewriteService,
                        @Qualifier("roundPrefetchExecutor") Executor prefetchExecutor) {
        // 中间步骤客户端：先 clone（须在 defaultAdvisors 之前，避免继承记忆 Advisor）
        this.internalChatClient = chatClientBuilder.clone()
                .defaultAdvisors(toolUsageLoggingAdvisor, roundTraceAdvisor).build();
        this.chatClient = chatClientBuilder
                .defaultAdvisors(memoryAdvisor, toolUsageLoggingAdvisor, roundTraceAdvisor).build();
        this.promptService = promptService;
        this.toolRegistry = toolRegistry;
        this.chatMemory = chatMemory;
        this.realtimeDataRule = promptProperties.realtimeRule();
        this.kbSearchService = kbSearchService;
        this.queryRewriteService = queryRewriteService;
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
        KbSearchService.KbContext kb = retrieve(conversationId, message, conv, agent, trace, prefetchedQuery);
        String systemPrompt = applyRealtimeRule(promptService.resolveSystemPrompt(agent)
                + buildLongTermMemoryText(conv)
                + kb.text()
                + (paramBlock == null ? "" : paramBlock));
        // 附件材料只进当轮 system（记忆 Advisor 仅持久化 .user() 纯提问）
        systemPrompt = withMaterial(systemPrompt, material);
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId));
        return new ComposedRequest(decorateRequest(spec, agent, trace), kb.citations());
    }

    /** 通用助手兜底请求（无智能体、不挂工具）：规划回退或目标与任何智能体无关时使用；同样跟随会话 RAG 开关（只检索全局库）。 */
    public ComposedRequest buildDefaultRequest(Conversation conv, String conversationId, String message,
                                              String material, RoundTrace trace) {
        KbSearchService.KbContext kb = retrieve(conversationId, message, conv, null, trace, null);
        String systemPrompt = promptService.resolveSystemPrompt(null) + buildLongTermMemoryText(conv) + kb.text();
        systemPrompt = withMaterial(systemPrompt, material);
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId));
        return new ComposedRequest(decorateRequest(spec, null, trace), kb.citations());
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
        return kbSearchService.buildKbContext(true, agent, query);
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

    /** 会话级 RAG 开关取值（null 视为关闭）。 */
    public boolean ragOn(Conversation conv) {
        return conv != null && Boolean.TRUE.equals(conv.getRagEnabled());
    }

    /**
     * 按智能体装配工具、应用其模型参数，并把当轮 trace 挂进 advisor 上下文。仅路由/绑定到智能体时才挂工具
     * （未配置=全量、{@code []}=不挂、白名单=按名取子集）；显式声明不用工具时跳过 {@code .tools()}。
     */
    public ChatClient.ChatClientRequestSpec decorateRequest(ChatClient.ChatClientRequestSpec spec, Agent agent,
                                                            RoundTrace trace) {
        if (trace != null) {
            spec = spec.advisors(a -> a.param(RoundTrace.CONTEXT_KEY, trace));
        }
        if (agent == null) return spec;
        ToolCallback[] tools = toolRegistry.resolve(agent.getToolsJson());
        // 显式 (Object[]) 传参：消除 tools(ToolCallback...) 的 varargs 提示性告警（@SuppressWarnings 实测无效）
        if (tools.length > 0) spec = spec.tools((Object[]) tools);
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

    /** 长期记忆文本（core_facts + summary，持久化于 conversation 表）。 */
    public String buildLongTermMemoryText(Conversation conv) {
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
     * 一次组装的产物：请求规格 + 本轮引用来源。citations 必须与请求一起返回——编号在拼 system 时生成，
     * 拆两次算会导致序号与来源漂移。
     */
    public record ComposedRequest(ChatClient.ChatClientRequestSpec spec, List<KbCitation> citations) {
    }
}
