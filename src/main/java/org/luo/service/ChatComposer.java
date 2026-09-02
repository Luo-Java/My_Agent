package org.luo.service;

import lombok.extern.slf4j.Slf4j;
import org.luo.advisor.ToolUsageLoggingAdvisor;
import org.luo.config.PromptProperties;
import org.luo.entity.Agent;
import org.luo.entity.Conversation;
import org.luo.tool.ToolRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * LLM 请求组装器：所有「把智能体 / 记忆 / 工具组装成一次 ChatClient 请求」的公共逻辑集中在这里，
 * 供普通对话（AgentRoundHandler）与规划执行（PlannerRoundHandler）两条路径共用（callAgent 骨架）。
 * <p>
 * 本类持有两个 ChatClient：
 * <ul>
 *   <li>{@code chatClient} —— 带会话记忆（MessageChatMemoryAdvisor 自动读写历史），
 *       普通对话 / 通用助手兜底回答使用，历史注入与消息落库都由 Advisor 完成；</li>
 *   <li>{@code internalChatClient} —— 无记忆，动态规划中间步骤专用，避免「指令 + 上一步输出」
 *       这类合成串被写进会话历史。</li>
 * </ul>
 * 工具挂载不在构建 ChatClient 时做：由 {@link #decorateRequest} 按「本次请求是否路由/绑定到
 * 具体智能体」门控挂载（普通对话不挂任何工具，避免把工具定义塞进每次对话的上下文；Agent 对话
 * 才挂全局能力池）。全局能力池由 ToolRegistry 启动时预解析为 ToolCallback，由 AI 在 Agent 范围内
 * 自主决定调用哪些，不按 Agent 手动写死工具列表、也无需 ToolSearch 渐进式披露。
 */
@Slf4j
@Service
public class ChatComposer {

    /**
     * 数据实时性强化规则：仅对系统提示词声明了「数据实时性」原则的数据分析类智能体（如教育数据智能分析）追加。
     * 纯提示词原则是软约束，模型仍可能直接引用历史数据；此规则以更高优先级的即时指令形式在每轮请求中重申，
     * 与智能体自带提示词叠加，强制「需要数据必须先重新查询数据库」。规则文本从配置外置（agent.prompt.realtime-rule）。
     */
    private final String realtimeDataRule;

    /** 带会话记忆的 ChatClient（通过 MessageChatMemoryAdvisor 自动读写历史）。 */
    private final ChatClient chatClient;
    /** 不带会话记忆的 ChatClient：动态规划中间步骤专用，避免中间产物被写进会话历史。 */
    private final ChatClient internalChatClient;
    private final PromptService promptService;
    private final ToolRegistry toolRegistry;
    private final ChatMemory chatMemory;
    private final KbService kbService;

    public ChatComposer(ChatClient.Builder chatClientBuilder,
                        MessageChatMemoryAdvisor memoryAdvisor,
                        ToolUsageLoggingAdvisor toolUsageLoggingAdvisor,
                        PromptService promptService,
                        ToolRegistry toolRegistry,
                        ChatMemory chatMemory,
                        PromptProperties promptProperties,
                        KbService kbService) {
        // 中间步骤客户端：先 clone 出一份干净的 builder（clone 须在 defaultAdvisors 之前，避免继承到记忆 Advisor）
        this.internalChatClient = chatClientBuilder.clone().defaultAdvisors(toolUsageLoggingAdvisor).build();
        this.chatClient = chatClientBuilder.defaultAdvisors(memoryAdvisor, toolUsageLoggingAdvisor).build();
        this.promptService = promptService;
        this.toolRegistry = toolRegistry;
        this.chatMemory = chatMemory;
        this.realtimeDataRule = promptProperties.realtimeRule();
        this.kbService = kbService;
    }

    /** 带记忆的 ChatClient：普通对话 / 通用助手兜底回答使用。 */
    public ChatClient chatClient() {
        return chatClient;
    }

    /** 无记忆的 ChatClient：动态规划中间步骤专用（见 {@link #executeStep} 调用方）。 */
    public ChatClient internalChatClient() {
        return internalChatClient;
    }

    /**
     * 组装一次普通对话请求：人设 System Prompt + 长期记忆（核心事实/滚动摘要） + 知识库资料（RAG）
     * + 已确认参数 + 当前输入，并给会话记忆 Advisor 传入 conversationId（用于读取与写回历史）。
     * <p>
     * 最终 prompt 顺序为：「人设 + 长期记忆 → 知识库资料（若命中） → 窗口原文（由 advisor 注入） → 当前输入」。
     * conv 由调用方一次查出后传入，本方法不再查库。RAG 检索范围为「本轮实际使用的智能体」的专属库
     * + 全局知识库（见 {@link KbService#buildKbContext}），由 {@code agent} 参数驱动。
     */
    public ChatClient.ChatClientRequestSpec buildRequest(String conversationId, String message, Conversation conv,
                                                         Agent agent, String paramBlock) {
        String systemPrompt = applyRealtimeRule(promptService.resolveSystemPrompt(agent)
                + buildLongTermMemoryText(conv)
                + buildKbContext(agent, message)
                + (paramBlock == null ? "" : paramBlock));
        return decorateRequest(chatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)), agent);
    }

    /**
     * 通用助手兜底请求（无智能体绑定、不挂载工具）：用于规划模式回退、或规划目标与任何智能体无关时。
     * 复用带记忆的 {@code chatClient}，保证用户原始目标被写入会话历史；知识库侧检索全局知识库。
     */
    public ChatClient.ChatClientRequestSpec buildDefaultRequest(Conversation conv, String conversationId, String message) {
        return chatClient.prompt()
                .system(promptService.resolveSystemPrompt(null) + buildLongTermMemoryText(conv)
                        + buildKbContext(null, message))
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId));
    }

    /** 解析智能体系统提示词（透传 PromptService；agent 为 null 时返回通用助手提示词）。 */
    public String buildSystemPrompt(Agent agent) {
        return promptService.resolveSystemPrompt(agent);
    }

    /**
     * 组装本轮请求的知识库资料文本（RAG，透传 KbService）：供 {@link #buildRequest}/{@link #buildDefaultRequest}
     * 拼接，也暴露给规划执行（PlannerRoundHandler 手拼 system 的步骤）复用，保证所有路径的知识库注入
     * 逻辑收敛在 KbService 一处。失败/无命中返回空串，不影响主流程。
     */
    public String buildKbContext(Agent agent, String query) {
        return kbService.buildKbContext(agent, query);
    }

    /**
     * 给已组装的请求挂载全局能力池并应用智能体模型参数（普通/规划两条路径共用的 callAgent 骨架）。
     * 全局能力池（启动时预解析为 ToolCallback，不重复反射），但<b>只在本次请求已路由/绑定到
     * 具体智能体时才挂载</b>：普通对话（agent 为空，如"你好"）不挂载任何工具，避免把工具定义
     * 无谓地塞进每一次对话的上下文。是否调用、调哪个工具，仍由 AI 在 agent 范围内自主决定，
     * 不按 Agent 手动写死工具列表（全局 ToolRegistry 自动收集）。
     */
    public ChatClient.ChatClientRequestSpec decorateRequest(ChatClient.ChatClientRequestSpec spec, Agent agent) {
        if (agent == null) return spec;
        return applyAgentOptions(spec.tools(toolRegistry.getToolCallbacks()), agent);
    }

    /**
     * 数据实时性声明的标记短语：系统提示词中出现该短语即视为该智能体声明了「数据实时性」原则
     * （如教育数据智能分析的提示词）。{@link #applyRealtimeRule} 据此追加硬约束规则。
     * <p>
     * 注意：此常量与 prompts.yaml / 数据库中的智能体提示词关键短语<b>强耦合</b>——
     * 修改提示词时需同步改这里（或提示词改为配置驱动后，升级为 Agent 表 realtime_data 标志位）。
     */
    static final String REALTIME_MARKER = "数据实时性";

    /**
     * 数据实时性硬约束注入：声明了「数据实时性」原则的智能体（如教育数据分析），每轮强制重申重新查库，
     * 防止模型直接引用对话历史中的旧数据作答或出图。纯提示词原则是软约束，此规则以更高优先级的
     * 即时指令形式叠加在系统提示词之后。
     */
    public String applyRealtimeRule(String systemPrompt) {
        return systemPrompt.contains(REALTIME_MARKER) ? systemPrompt + "\n\n" + realtimeDataRule : systemPrompt;
    }

    /**
     * 读取本会话的对话历史并格式化为文本块（"用户：xxx / 助手：yyy" 行式）。
     * 两个用途共用一套格式化逻辑，仅「截取条数」与「前置说明」不同：
     * <ul>
     *   <li>{@code maxMessages <= 0} 取全部历史（token 预算内），用于规划模式最后一步注入系统提示词、
     *       替代记忆 Advisor 的上下文读取（执行期间不写记忆）；</li>
     *   <li>{@code maxMessages > 0} 只取最近 N 条，用于智能路由判断「承接上一轮的短追问」时作为上下文
     *       （如上一轮在查天气、用户只说「北京呢？」），控制路由 LLM 的 token 成本。</li>
     * </ul>
     * 读取失败返回空串，不影响主流程。
     *
     * @param conversationId 会话 ID
     * @param maxMessages    最多取多少条；小于等于 0 表示全部
     * @param header         文本块前置说明（可空，路由上下文场景传 null）
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
     * 读取会话的长期记忆（用户核心信息 + 滚动摘要）文本。这些内容持久化在 conversation 表。
     */
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
}
