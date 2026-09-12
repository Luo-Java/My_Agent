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
 * 工具挂载不在构建 ChatClient 时做：由 {@link #decorateRequest} 门控——普通对话（未路由到智能体）
 * 不挂任何工具；智能体对话按该智能体的 {@code tools_json} 装配声明，从全局工具池取子集挂载
 * （未配置=全量、{@code []}=不挂、白名单=按名取子集，见 {@link ToolRegistry#resolve(String)}）。
 * 这样闲聊/翻译类智能体不会看到 SQL、天气等无关工具——既省 token，也避免人设被无关工具干扰；
 * 是否调用、调哪个工具仍由 AI 在装配范围内自主决定。
 * <p>
 * <b>两个 Advisor 分工</b>：{@link ToolUsageLoggingAdvisor} 只打日志；
 * {@link RoundTraceAdvisor} 把工具调用与 token 用量写进当轮 {@link RoundTrace}（可观测）。
 * 后者需要拿到当轮 trace，故 {@link #decorateRequest} 会把它塞进 advisor 上下文
 * （键 {@link RoundTrace#CONTEXT_KEY}，与 CONVERSATION_ID 同款机制）——这样规划模式的每一步
 * 也能各自被追踪，而不必依赖线程局部变量。
 */
@Slf4j
@Service
public class ChatComposer {

    /**
     * 预取改写的等待上限（秒）：正式回答前 join 预取结果，超过该时长即放弃、改用用户原话检索。
     * <p>
     * 取 10s 是「远大于正常改写耗时（亚秒级）、又远小于模型全局超时（60s）」的折中：它只是防止
     * 改写侧异常拖慢主链路的兜底。正常情况下 join 时预取早已完成（改写与路由/参数抽取并行跑），
     * 不产生任何实际等待。
     */
    private static final long REWRITE_JOIN_TIMEOUT_SECONDS = 10;

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
        // 中间步骤客户端：先 clone 出一份干净的 builder（clone 须在 defaultAdvisors 之前，避免继承到记忆 Advisor）
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
     * 组装一次普通对话请求：人设 System Prompt + 长期记忆（核心事实/滚动摘要） + 知识库资料（RAG）
     * + 已确认参数 + 当前输入，并给会话记忆 Advisor 传入 conversationId（用于读取与写回历史）。
     * <p>
     * 最终 prompt 顺序为：「人设 + 长期记忆 → 知识库资料（若命中） → 窗口原文（由 advisor 注入） → 当前输入」。
     * conv 由调用方一次查出后传入，本方法不再查库。RAG 是否检索由<b>会话级开关</b>
     * {@code conv.ragEnabled} 决定（false=不检索；true=先把多轮追问改写为自包含问题，再自动检索全局库 +
     * 本轮 agent 专属库，见 {@link #retrieve}）。
     *
     * @param trace 本轮追踪上下文（可为 null）：仅用于把工具调用 / token 采集进可观测记录
     */
    public ComposedRequest buildRequest(String conversationId, String message, Conversation conv,
                                       Agent agent, String paramBlock, String material, RoundTrace trace) {
        return buildRequest(conversationId, message, conv, agent, paramBlock, material, trace, null);
    }

    /**
     * 组装一次普通对话请求（带「检索问题」预取结果）。
     * <p>
     * {@code prefetchedQuery} 由调用方在本轮<b>最前</b>发起（见 {@link #prefetchRetrievalQuery}），
     * 因此走到这里时通常早已完成——join 它几乎不产生等待，却把改写的模型往返整个藏在了
     * 智能路由 / 参数抽取背后。传 {@code null}（未预取或未开 RAG）时退回同步改写，行为与改造前一致。
     */
    public ComposedRequest buildRequest(String conversationId, String message, Conversation conv,
                                       Agent agent, String paramBlock, String material, RoundTrace trace,
                                       CompletableFuture<String> prefetchedQuery) {
        KbSearchService.KbContext kb = retrieve(conversationId, message, conv, agent, trace, prefetchedQuery);
        String systemPrompt = applyRealtimeRule(promptService.resolveSystemPrompt(agent)
                + buildLongTermMemoryText(conv)
                + kb.text()
                + (paramBlock == null ? "" : paramBlock));
        // 附件材料只进当轮 system（不进会话记忆）：记忆 Advisor 仅持久化 .user() 纯提问文本
        systemPrompt = withMaterial(systemPrompt, material);
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId));
        return new ComposedRequest(decorateRequest(spec, agent, trace), kb.citations());
    }

    /**
     * 通用助手兜底请求（无智能体绑定、不挂载工具）：用于规划模式回退、或规划目标与任何智能体无关时。
     * 复用带记忆的 {@code chatClient}，保证用户原始目标被写入会话历史；知识库侧同样跟随会话开关
     * （conv.ragEnabled，此时无路由智能体 → 只检索全局库），并同样先做多轮查询改写。
     * 附件材料 {@code material} 仅当轮注入 system，不进记忆。
     */
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

    /**
     * 把本轮附件材料追加到 system prompt 末尾（仅在非空时）。附件仅当轮可见、不进会话记忆：
     * 记忆 Advisor 持久化的是 {@code .user()} 纯提问，且 system 段每轮由本方法重建、不落库。
     */
    private static String withMaterial(String systemPrompt, String material) {
        if (material == null || material.isBlank()) return systemPrompt;
        return systemPrompt + "\n\n[本轮附件材料] 以下为用户本轮上传的内容（图片已识别、文档已解析为文本），"
                + "仅作为本次回答参考，不写入长期记忆：\n" + material;
    }

    /** 解析智能体系统提示词（透传 PromptService；agent 为 null 时返回通用助手提示词）。 */
    public String buildSystemPrompt(Agent agent) {
        return promptService.resolveSystemPrompt(agent);
    }

    /**
     * 组装本轮请求的知识库资料（RAG，透传 KbSearchService）：返回「资料文本 + 引用来源」。
     * 供 {@link #buildRequest}/{@link #buildDefaultRequest} 拼接，也暴露给规划执行
     * （PlannerRoundHandler 手拼 system 的步骤）复用，保证所有路径的知识库注入逻辑收敛在 KbSearchService 一处。
     * 开关关闭（ragEnabled=false）返回 {@code KbContext.EMPTY}；开启后按路由到的 agent 自动多库检索
     * （全局库 + 该 agent 专属库）；失败/无命中同样返回 EMPTY，不影响主流程。
     */
    public KbSearchService.KbContext buildKbContext(Boolean ragEnabled, Agent agent, String query) {
        return kbSearchService.buildKbContext(ragEnabled, agent, query);
    }

    /**
     * 本轮知识库检索的统一入口（普通对话 / 通用助手兜底共用）：<b>会话开关判定 → 多轮查询改写 → 检索</b>。
     * <p>
     * 相比直接调 {@link #buildKbContext}，多了一步「检索问题改写」：多轮对话里用户大量使用指代与省略
     * （「那它呢」「换成三年级呢」），原话单独向量化会因语义残缺而召回错误内容；而精排只能重排
     * <b>已召回</b>的候选，救不回压根没进来的正确答案。故在检索前先用最近历史把追问补全为自包含问题
     * （见 {@link QueryRewriteService}，首轮无历史时自动跳过、零额外调用）。
     * <p>
     * 改写结果只有与用户原话<b>确实不同</b>时才写进 trace（{@code retrieval_query} 列），
     * 便于事后核验「这轮到底拿什么去检索的」——null 表示未改写（未开 RAG / 首轮 / 关闭改写 / 模型认为无需改写）。
     * <p>
     * <b>规划模式的步骤不经过本方法</b>：那里的 query 是「本步指令 + 上一步输出」的合成串，不是用户原话，
     * 拿会话历史去消解它的指代只会引入噪声，故 {@code PlannerRoundHandler} 仍直接调
     * {@link #buildKbContext} 并传原始 userInput。
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
     * 预取「检索问题」：RAG 开启时把多轮查询改写提交到专用线程池异步执行，与调用方的前置链
     * （智能路由 / 参数抽取）并行，把这次模型往返藏到它们背后。
     * <p>
     * RAG 未开启时返回 {@code null}（调用方不会产生任何额外调用）；线程池拒绝时记日志并返回
     * {@code null}，调用方回退同步改写。预取只是加速、<b>不承担正确性</b>——无论预取成败，
     * 最终都会得到一个可直接检索的问题字符串。
     * <p>
     * <b>代价说明</b>：若本轮最终走了「参数追问」分支，预取结果会被弃用（浪费一次很轻的改写调用）。
     * 这是刻意接受的取舍：追问只发生在声明了 paramSchema 的智能体缺参时，而收益是每次正式回答
     * 都省掉一个完整的模型往返。
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
     * 取本轮检索实际使用的问题：优先消费预取结果，未预取 / 超时 / 异常时回退。
     * <p>
     * 回退顺序：未预取（{@code null}）→ 同步改写（改造前行为）；预取超时
     * （{@value #REWRITE_JOIN_TIMEOUT_SECONDS}s）→ 用户原话；等待异常 → 用户原话。
     * 改写调用本身的异常由 {@link QueryRewriteService} 内部兜底为原话，故这里只处理「等待它」的问题。
     * <p>
     * <b>绝不阻断对话</b>：任何分支都返回一个可直接检索的字符串，不向上抛异常。
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
     * 给已组装的请求按智能体装配工具、应用其模型参数，并把当轮 trace 挂进 advisor 上下文。
     * <b>只在本次请求已路由/绑定到具体智能体时才挂载工具</b>：普通对话（agent 为空，如"你好"）
     * 不挂任何工具，避免把工具定义无谓地塞进每一次对话的上下文；智能体对话按 {@code agent.toolsJson}
     * 从全局池中取子集（未配置=全量、{@code []}=不挂、白名单=按名取子集）。
     * 显式声明「不使用工具」时直接跳过 {@code .tools()}，不挂空数组。
     *
     * @param trace 本轮追踪上下文（可为 null）：塞进 advisor 上下文供 RoundTraceAdvisor 采集
     */
    public ChatClient.ChatClientRequestSpec decorateRequest(ChatClient.ChatClientRequestSpec spec, Agent agent,
                                                            RoundTrace trace) {
        if (trace != null) {
            spec = spec.advisors(a -> a.param(RoundTrace.CONTEXT_KEY, trace));
        }
        if (agent == null) return spec;
        ToolCallback[] tools = toolRegistry.resolve(agent.getToolsJson());
        // 显式转 Object[]：tools(Object...) 接收数组时按 varargs 展开（挂载全部工具），
        // 转换同时消除 javac 的「非 varargs 调用」提示性告警
        if (tools.length > 0) spec = spec.tools((Object[]) tools);
        return applyAgentOptions(spec, agent);
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

    /**
     * 一次组装请求的产物：待执行的请求规格 + 本轮注入的知识库引用来源。
     * <p>
     * 引用必须与请求一起返回：编号是在拼 system 资料块时生成的，若拆成两次计算（先拼文本、再查一次引用），
     * 序号与来源就会漂移。调用方把 citations 一路带到 {@code RoundResult}，最终落库 / 推给前端。
     *
     * @param spec      已装好人设 / 记忆 / 工具 / 模型参数的请求规格
     * @param citations 本轮系统提示词引用的知识块（无 RAG 或未命中时为空表）
     */
    public record ComposedRequest(ChatClient.ChatClientRequestSpec spec, List<KbCitation> citations) {
    }
}
