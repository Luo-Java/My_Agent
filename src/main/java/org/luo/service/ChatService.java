package org.luo.service;

import lombok.extern.slf4j.Slf4j;
import org.luo.advisor.ToolUsageLoggingAdvisor;
import org.luo.entity.Agent;
import org.luo.entity.Conversation;
import org.luo.tool.ToolRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
    private final ConversationService conversationService;
    private final AgentService agentService;
    private final ParamFillingService paramFillingService;
    private final AgentRouter agentRouter;
    private final MemoryMergeService memoryMergeService;
    private final PromptService promptService;
    private final ToolRegistry toolRegistry;

    public ChatService(ChatClient.Builder chatClientBuilder,
                       ConversationService conversationService,
                       AgentService agentService,
                       MessageChatMemoryAdvisor memoryAdvisor,
                       ToolSearchToolCallingAdvisor toolSearchAdvisor,
                       ToolUsageLoggingAdvisor toolUsageLoggingAdvisor,
                       ParamFillingService paramFillingService,
                       AgentRouter agentRouter,
                       MemoryMergeService memoryMergeService,
                       PromptService promptService,
                       ToolRegistry toolRegistry) {
        // 动态工具发现 Advisor：替换默认 ToolCallingAdvisor（DefaultChatClient 检测到已有 ToolAdvisor
        // 会自动跳过默认注册），由 AI 在对话中自主决定调用哪些工具，无需按 Agent 手动配置。
        // 工具使用监控 Advisor 放在链末尾，记录每次对话实际挂载了哪些工具。
        this.chatClient = chatClientBuilder.defaultAdvisors(memoryAdvisor, toolSearchAdvisor, toolUsageLoggingAdvisor).build();
        this.conversationService = conversationService;
        this.agentService = agentService;
        this.paramFillingService = paramFillingService;
        this.agentRouter = agentRouter;
        this.memoryMergeService = memoryMergeService;
        this.promptService = promptService;
        this.toolRegistry = toolRegistry;
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
        Agent agent = determineAgent(conv, message);
        ParamFillingService.ClarifyDecision decision = paramFillingService.decideClarify(conversationId, message, agent);
        if (decision.question != null) {
            // 进入追问分支：decideClarify 已落库 user+assistant，这里只做收尾
            conversationService.touchConversation(conversationId, message);
            memoryMergeService.maybeMergeMemory(conversationId);
            return decision.question;
        }
        ChatClient.ChatClientRequestSpec spec = buildRequest(conversationId, message, conv, agent, paramFillingService.buildParamBlock(decision));
        String reply = spec.call().content();

        log.info("同步对话完成：回复长度={}", reply != null ? reply.length() : 0);
        conversationService.touchConversation(conversationId, message);
        memoryMergeService.maybeMergeMemory(conversationId);
        return reply;
    }

    /**
     * 流式对话：逐 token 返回 AI 回复。
     * 消息落库由 advisor 在流完成时完成，随后执行滚动摘要合并检查。
     *
     * @param conversationId 会话 ID
     * @param message        当前用户输入
     * @return 逐 token 返回的 Flux 流
     */
    public Flux<String> stream(String conversationId, String message) {
        log.info("流式对话：会话={}", conversationId);
        Conversation conv = conversationService.ensureConversation(conversationId);
        Agent agent = determineAgent(conv, message);
        ParamFillingService.ClarifyDecision decision = paramFillingService.decideClarify(conversationId, message, agent);
        if (decision.question != null) {
            conversationService.touchConversation(conversationId, message);
            memoryMergeService.maybeMergeMemory(conversationId);
            return Flux.just(decision.question);
        }
        ChatClient.ChatClientRequestSpec spec = buildRequest(conversationId, message, conv, agent, paramFillingService.buildParamBlock(decision));

        // 动态工具发现已全局挂载：任何对话都可能触发工具调用。Spring AI 2.0.0 的 stream() 在
        // 合并工具调用分片时会抛 NoSuchElementException（OpenAiChatModel.ChunkMerger 已知缺陷）。
        // 统一改为 call() 走完「模型→工具→再回答」循环拿到完整答案，再分块模拟流式输出，兼顾正确性与打字效果。
        return streamToolAnswer(spec, conversationId, message);
    }

    /**
     * 带工具对话的「伪流式」输出：先以 call() 走完工具调用循环得到完整答案，
     * 再按 4 字一块切片并加微小间隔，让前端依旧呈现逐字打字效果。
     * 工具调用本身无法流式（2.0.0 流式合并有缺陷），故工具循环用非流式完成。
     */
    private Flux<String> streamToolAnswer(ChatClient.ChatClientRequestSpec spec, String conversationId, String message) {
        try {
            String full = spec.call().content();
            if (full == null) full = "";
            log.info("工具对话完成：回复长度={}", full.length());
            conversationService.touchConversation(conversationId, message);
            memoryMergeService.maybeMergeMemory(conversationId);
            List<String> chunks = new ArrayList<>();
            for (int i = 0; i < full.length(); i += 4) {
                chunks.add(full.substring(i, Math.min(i + 4, full.length())));
            }
            return Flux.fromIterable(chunks).delayElements(Duration.ofMillis(15));
        } catch (Exception e) {
            log.error("工具对话失败：会话={}，错误={}", conversationId, e.getMessage(), e);
            return Flux.just("工具调用出错：" + e.getMessage());
        }
    }

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
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId));
        // 全局能力池：所有对话都挂载全部工具（启动时预解析为 ToolCallback，不重复反射）。
        // 由 ToolSearchToolCallingAdvisor 做渐进式工具暴露，模型先搜索再决定调用哪些，
        // 而不是按 Agent 手动配置。是否调用、调哪个，全由 AI 决定。
        spec = spec.tools(toolRegistry.getToolCallbacks());
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
            return agentRouter.route(message);
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
