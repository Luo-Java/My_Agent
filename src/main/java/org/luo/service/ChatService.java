package org.luo.service;

import lombok.extern.slf4j.Slf4j;
import org.luo.entity.Agent;
import org.luo.entity.ChatMessage;
import org.luo.entity.Conversation;
import org.luo.exception.AiBusinessException;
import org.luo.exception.AiErrorCode;
import org.luo.memory.DbChatMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 对话编排服务（纯 AI 调用层）。
 * <p>
 * 会话记忆交由 Spring AI 标准的 {@link ChatMemory} 处理：
 * <ul>
 *   <li>「最近窗口原文」的读取与新消息落库，由 {@link MessageChatMemoryAdvisor} + {@link DbChatMemory} 自动完成；</li>
 *   <li>「长期记忆」（滚动摘要 + 用户核心信息）仍持久化在 conversation 表，每次请求前注入、对话结束后触发批量合并；</li>
 * </ul>
 * 本类只负责：人设/长期记忆/当前输入拼装、调用大模型、返回流式或同步结果。
 */
@Slf4j
@Service
public class ChatService {


    /** 默认 System Prompt：未绑定智能体时使用的通用助手指令（约束 AI 输出为规范 Markdown）。 */
    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是一个专业、友好的 AI 助手。请严格遵守以下输出格式要求：

            1. 使用 Markdown 格式组织回复内容：
               - 用 ## 或 ### 分段标题（不要用 # 一级标题）
               - 代码片段用 ```语言 包裹代码块，行内变量/函数用 `反引号`
               - 列表用 - 或数字编号
               - 表格用标准 Markdown 表格语法

            2. 内容结构要求：
               - 先给出结论或直接回答，再展开解释
               - 分点说明时每条不超过两行
               - 代码示例必须附带简短注释说明关键步骤
               - 长回复请用分段和空行保持可读性

            3. 禁止事项：
               - 不要输出原始 Markdown 源码符号作为装饰（如 ### 冒泡排序 **算法**）
               - 不要在正文里混入 HTML 标签
               - 不要使用过多的 emoji 或特殊符号
               - 回复开头不要加「你好」「您好」等寒暄，直接回答问题
            """;

    /** 带会话记忆的 ChatClient（通过 MessageChatMemoryAdvisor 自动读写历史）。 */
    private final ChatClient chatClient;
    private final ConversationService conversationService;
    private final AgentService agentService;
    /** 用于内部记忆合并的裸 ChatModel：不走 advisor，避免把摘要指令写入对话记忆。 */
    private final ChatModel chatModel;
    /** 解析 LLM 返回的 JSON（路由判断等内部调用用）。 */
    private final ObjectMapper objectMapper;

    public ChatService(ChatClient.Builder chatClientBuilder,
                       ConversationService conversationService,
                       AgentService agentService,
                       ChatModel chatModel,
                       MessageChatMemoryAdvisor memoryAdvisor,
                       ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.defaultAdvisors(memoryAdvisor).build();
        this.conversationService = conversationService;
        this.agentService = agentService;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
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
        Conversation conv = conversationService.ensureConversation(conversationId);   // 一次查询，后续复用
        ChatClient.ChatClientRequestSpec spec = buildRequest(conversationId, message, conv);
        String reply = spec.call().content();

        log.info("同步对话完成：回复长度={}", reply != null ? reply.length() : 0);
        conversationService.touchConversation(conversationId, message);
        maybeMergeMemory(conversationId);
        return reply;
    }

    /**
     * 流式对话：逐 token 返回 AI 回复。
     * 使用 boundedElastic 线程池执行数据库落库，避免阻塞 Reactor 事件循环。
     * 消息落库由 advisor 在流完成时完成，随后执行滚动摘要合并检查。
     *
     * @param conversationId 会话 ID
     * @param message        当前用户输入
     * @return 逐 token 返回的 Flux 流
     */
    public Flux<String> stream(String conversationId, String message) {
        log.info("流式对话：会话={}", conversationId);
        Conversation conv = conversationService.ensureConversation(conversationId);   // 一次查询，后续复用
        ChatClient.ChatClientRequestSpec spec = buildRequest(conversationId, message, conv);
        StringBuilder fullReply = new StringBuilder();
        return spec.stream().content()
                .doOnNext(fullReply::append)
                .publishOn(Schedulers.boundedElastic())
                .doOnComplete(() -> {
                    log.info("流式对话完成：回复长度={}", fullReply.length());
                    conversationService.touchConversation(conversationId, message);
                    maybeMergeMemory(conversationId);
                });
    }

    /**
     * 批量记忆合并阈值：累计溢出这么多条消息才触发一次 LLM 记忆合并（摘要 + 关键事实）。
     * 值越大调用越少（默认 6 条 ≈ 每 3 轮一次），但批次之间溢出的消息会暂时缺席上下文；
     * 值越小记忆越细但调用越频繁（设为 1 即回到每轮合并）。可调。
     */
    private static final int SUMMARY_BATCH_SIZE = 6;

    /**
     * 组装一次请求：人设 System Prompt + 长期记忆（核心事实/滚动摘要） + 当前输入，
     * 并给会话记忆 Advisor 传入 conversationId（用于读取与写回历史）。
     * <p>
     * 长期记忆直接拼进 system prompt（而非独立 SystemMessage），保证最终 prompt 顺序为
     * 「人设 + 长期记忆 → 窗口原文（由 advisor 注入） → 当前输入」。
     * <p>
     * conv 由调用方（chat/stream）一次查出后传入，本方法不再查库；
     * 绑定了智能体时也只查一次 agent，供人设与模型参数两处共用。
     *
     * @param conversationId 会话 ID
     * @param message        当前用户输入
     * @param conv           会话对象（由 ensureConversation 返回，可复用）
     * @return 组装好的请求规格
     */
    private ChatClient.ChatClientRequestSpec buildRequest(String conversationId, String message, Conversation conv) {
        Agent agent;
        if (conv != null && conv.getAgentId() != null) {
            agent = agentService.getAgent(conv.getAgentId()); // 显式绑定智能体：直接用
        } else if (conv != null) {
            // 未绑定智能体的普通会话：先根据消息内容智能路由到某个智能体（不写回会话 agentId），未命中则普通对话
            agent = routeAgent(message);
        } else {
            agent = null;
        }
        String systemPrompt = resolveSystemPrompt(agent) + buildLongTermMemoryText(conv);
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId));
        return applyAgentOptions(spec, agent);
    }

    /**
     * 智能路由：对「未绑定智能体的普通会话」，让 LLM 根据消息内容判断是否应交给某个专属智能体处理。
     * <p>
     * 使用裸 {@link ChatModel} 调用（不走 advisor、不写任何会话记忆）；判断失败或未命中一律回退普通对话，
     * 绝不阻断主流程。路由只影响本次回复的人设/模型参数，不会修改会话的 agentId。
     *
     * @param message 当前用户输入
     * @return 命中的智能体，未命中或判断失败返回 null（普通对话）
     */
    private Agent routeAgent(String message) {
        List<Agent> agents = agentService.listAgents();
        if (agents == null || agents.isEmpty()) {
            log.debug("智能路由：暂无智能体，跳过路由");
            return null;
        }
        if (message == null || message.isBlank()) return null;
        try {
            StringBuilder list = new StringBuilder();
            for (Agent a : agents) {
                list.append("- ").append(a.getName())
                        .append(" (").append(a.getAgentCode() == null ? "无编码" : a.getAgentCode()).append(")")
                        .append("：").append(a.getDescription() == null || a.getDescription().isBlank() ? "（无描述）" : a.getDescription())
                        .append("\n");
            }
            log.debug("智能路由：调用 LLM 判断路由，可用智能体={}", agents.size());
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage("你是多智能体路由决策器。根据用户消息的内容判断：是否应该交由某个专属智能体（agent）来处理本次请求。\n\n"
                            + "判断规则：\n"
                            + "1. 只有当用户请求的意图与某个智能体的职责高度匹配时才路由（例如用户明确要求翻译 → 交给翻译类智能体；要求写代码 → 交给编程类智能体）。\n"
                            + "2. 一般闲聊、寒暄，或用户请求没有对应智能体能胜任时，不路由，进行普通对话。\n"
                            + "3. 最多路由到一个智能体；犹豫时选择职责最匹配的，仍不匹配就不路由。\n\n"
                            + "可用智能体清单：\n" + list
                            + "\n严格只输出 JSON（不要输出任何其他内容、不要用代码块包裹）：\n"
                            + "路由时：{\"route\": true, \"agentCode\": \"<编码>\"}\n"
                            + "不路由时：{\"route\": false}"),
                    new UserMessage("用户消息：\n" + message))));
            String reply = (response.getResult() != null && response.getResult().getOutput() != null)
                    ? response.getResult().getOutput().getText() : null;
            if (reply == null || reply.isBlank()) return null;
            JsonNode node = objectMapper.readTree(extractJson(reply));
            if (!node.path("route").asBoolean(false)) {
                log.debug("智能路由：未命中，普通对话");
                return null;
            }
            String code = node.path("agentCode").asText(null);
            if (code == null || code.isBlank()) return null;
            Agent target = agentService.getByCode(code);
            if (target == null) {
                log.warn("智能路由：路由编码 {} 不存在，回退普通对话", code);
                return null;
            }
            log.info("智能路由命中：会话按智能体「{}」（{}）处理", target.getName(), target.getAgentCode());
            return target;
        } catch (Exception e) {
            log.warn("智能路由判断失败，回退普通对话", e);
            return null;
        }
    }

    /** 从 LLM 回复中提取 JSON 片段：去除 ```json 代码块包裹，只保留第一个 { 到最后一个 }。 */
    private String extractJson(String reply) {
        String json = reply.trim();
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "").trim();
        }
        int s = json.indexOf('{');
        int e = json.lastIndexOf('}');
        return (s >= 0 && e > s) ? json.substring(s, e + 1) : json;
    }

    /**
     * 读取会话的长期记忆（用户核心信息 + 滚动摘要）文本。
     * 这些内容持久化在 conversation 表，由 maybeMergeMemory 在对话结束后增量更新。
     *
     * @param conv 会话对象（调用方已查出，直接复用，不查库）
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

    /**
     * 对话结束后检查：历史是否溢出窗口达到合并阈值，若是则触发一次 LLM 记忆合并。
     * 本轮消息已由 advisor 通过 ChatMemory 落库，这里基于全量历史计算窗口起点，
     * 窗口之外的旧消息由「滚动摘要 + 用户核心信息」接管，之后不再进入上下文。
     */
    private void maybeMergeMemory(String conversationId) {
        try {
            List<ChatMessage> history = conversationService.getHistory(conversationId);
            int windowStart = DbChatMemory.computeWindowStart(history);
            if (windowStart <= 0) {
                return; // 全部历史都在 token 预算内，无需合并
            }
            Conversation conv = conversationService.getConversation(conversationId);
            if (conv == null) return;
            int alreadySummarized = conv.getSummarizedCount() == null ? 0 : conv.getSummarizedCount();
            int newOverflow = windowStart - alreadySummarized;
            if (newOverflow < SUMMARY_BATCH_SIZE) {
                if (newOverflow > 0) {
                    log.debug("记忆待合并：{} 条溢出消息未合并（阈值={}）", newOverflow, SUMMARY_BATCH_SIZE);
                }
                return;
            }
            if (alreadySummarized >= windowStart) {
                log.warn("记忆状态不一致：已覆盖条数={} >= 窗口起点={}，跳过合并", alreadySummarized, windowStart);
                return;
            }
            List<ChatMessage> delta = history.subList(alreadySummarized, windowStart);
            SummaryResult sr = summarize(conv.getSummary(), conv.getCoreFacts(), delta);
            conversationService.updateMemory(conversationId, sr.summary, sr.coreFacts, windowStart);
            log.info("记忆合并完成：合并 {} 条，已覆盖条数={}", delta.size(), windowStart);
        } catch (Exception e) {
            log.error("记忆合并失败：会话={}", conversationId, e);
        }
    }

    /** LLM 一次记忆合并的产出：更新后的滚动摘要 + 用户核心信息。 */
    private static final class SummaryResult {
        String summary;
        String coreFacts;

        SummaryResult(String summary, String coreFacts) {
            this.summary = summary;
            this.coreFacts = coreFacts;
        }
    }

    /**
     * 将"新增溢出的历史"与"已有摘要/关键事实"合并，一次 LLM 调用同时产出：
     * 更新后的滚动摘要 + 更新后的用户核心信息（关键事实清单）。
     * <p>
     * 注意：这里使用裸 {@link ChatModel} 直接调用，而不是带 advisor 的 chatClient，
     * 否则 advisor 会把本次摘要指令当作对话消息写入记忆，造成污染。
     * 调用失败时回退到已有记忆，保证主流程不被打断、长期记忆不丢。
     *
     * @param existingSummary   之前的滚动摘要（首次为 {@code null}）
     * @param existingCoreFacts 已提取的用户核心信息（首次为 {@code null}）
     * @param newMessages       新溢出到记忆池的消息列表（按时间正序）
     * @return 合并结果（摘要 + 核心信息）；LLM 调用失败时两者均回退原值
     */
    private SummaryResult summarize(String existingSummary, String existingCoreFacts, List<ChatMessage> newMessages) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("【已有摘要】\n").append(existingSummary == null || existingSummary.isBlank() ? "（无）" : existingSummary).append("\n\n");
            sb.append("【已有关键事实】\n").append(existingCoreFacts == null || existingCoreFacts.isBlank() ? "（无）" : existingCoreFacts).append("\n\n");
            sb.append("【新增对话内容】\n");
            for (ChatMessage m : newMessages) {
                sb.append(m.getRole()).append("：").append(m.getContent()).append("\n");
            }
            log.debug("生成摘要：调用 LLM 合并 {} 条新消息", newMessages.size());
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage("你负责维护一份对话记忆。每次收到【新增对话内容】时，把它与【已有摘要】和【已有关键事实】合并，"
                            + "输出两部分，严格使用下面的格式（不要输出其他内容）：\n"
                            + "## 摘要\n更新后的简洁中文摘要，保留关键事实、用户偏好、待办与结论；不要逐字复述。\n"
                            + "## 关键事实\n从全部内容中提取的用户长期关键信息，每条以“- ”开头（如姓名、身份、偏好、待办、重要承诺）；没有则只输出“无”。"),
                    new UserMessage(sb.toString()))));
            String reply = (response.getResult() != null && response.getResult().getOutput() != null)
                    ? response.getResult().getOutput().getText()
                    : null;
            if (reply == null || reply.isBlank()) {
                log.warn("生成摘要：LLM 返回为空，回退到已有记忆");
                return new SummaryResult(existingSummary, existingCoreFacts);
            }
            SummaryResult sr = parseSummaryResult(reply, existingSummary, existingCoreFacts);
            log.info("生成摘要完成：摘要长度={}，关键事实长度={}",
                    sr.summary != null ? sr.summary.length() : 0,
                    sr.coreFacts != null ? sr.coreFacts.length() : 0);
            return sr;
        } catch (Exception e) {
            log.error("生成摘要失败：LLM 调用异常", e);
            return new SummaryResult(existingSummary, existingCoreFacts);   // 失败：保留旧记忆，不阻断对话
        }
    }

    /**
     * 解析 LLM 返回的「摘要 + 关键事实」两段式文本，容错处理格式偏差：
     * 找不到分隔符时整段视为摘要、关键事实保留旧值；关键事实为"无"时置为 null。
     */
    private SummaryResult parseSummaryResult(String reply, String existingSummary, String existingCoreFacts) {
        String text = reply.trim();
        int idx = text.indexOf("## 关键事实");
        if (idx < 0) idx = text.indexOf("关键事实");
        if (idx < 0) {
            return new SummaryResult(text, existingCoreFacts);
        }
        String summaryPart = text.substring(0, idx).replaceAll("^##?\\s*摘要\\s*", "").trim();
        String factsPart = text.substring(idx).replaceFirst("^##?\\s*关键事实\\s*", "").trim();
        String summary = summaryPart.isBlank() ? existingSummary : summaryPart;
        String coreFacts;
        if (factsPart.isBlank() || "无".equals(factsPart)) {
            coreFacts = null;
        } else {
            coreFacts = factsPart;
        }
        return new SummaryResult(summary, coreFacts);
    }

    /**
     * 解析本次请求应使用的系统提示词：存在可用智能体（显式绑定或路由命中）且带 systemPrompt 时用之，否则用默认。
     * agent 由调用方（buildRequest）查出后传入，不查库。
     */
    private String resolveSystemPrompt(Agent agent) {
        if (agent != null) {
            String p = agent.getSystemPrompt();
            if (p != null && !p.isBlank()) {
                return p;
            }
        }
        return DEFAULT_SYSTEM_PROMPT;
    }

    /**
     * 根据智能体名称与描述，用 LLM 生成一段系统提示词（人设）。
     * 使用裸 {@link ChatModel} 调用（不走 advisor，不写入任何会话记忆、不影响对话历史）。
     *
     * @param name        智能体名称（必填）
     * @param description 智能体描述（可选）
     * @return 生成的提示词纯文本
     * @throws AiBusinessException 名称为空或 LLM 调用失败时抛出
     */
    public String generateAgentPrompt(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "智能体名称不能为空");
        }
        log.info("生成智能体提示词：名称={}", name);
        try {
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage("""
                            你是一位专业的提示词（Prompt）工程师。根据用户提供的智能体名称和描述，创作一段高质量的中文系统提示词（人设设定）。
                            要求：
                            1. 直接输出提示词正文本身，不要输出任何解释、前言、后语，不要用代码块包裹。
                            2. 用第二人称「你」开头，明确角色定位、职责范围与目标用户。
                            3. 包含对回答风格与输出格式的具体要求（涉及代码时用 Markdown 代码块等）。
                            4. 列出 2~4 条具体行为准则，例如回复结构、禁止事项、处理边界。
                            5. 全文 150~400 字，语气专业、指令明确。
                            """),
                    new UserMessage("智能体名称：" + name
                            + "\n描述：" + (description == null || description.isBlank() ? "（无）" : description)))));
            String reply = (response.getResult() != null && response.getResult().getOutput() != null)
                    ? response.getResult().getOutput().getText()
                    : null;
            if (reply == null || reply.isBlank()) {
                throw new AiBusinessException(AiErrorCode.INTERNAL_ERROR, "AI 未能生成提示词，请稍后重试");
            }
            String trimmed = reply.trim();
            log.info("智能体提示词生成完成：长度={}", trimmed.length());
            return trimmed;
        } catch (AiBusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("智能体提示词生成失败：LLM 调用异常", e);
            throw new AiBusinessException(AiErrorCode.INTERNAL_ERROR, "提示词生成失败，请检查 AI 服务配置后重试");
        }
    }

    /** 若会话绑定的智能体带 model/temperature，则把对应参数应用到本次请求。agent 由调用方查出后传入，不查库。 */
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
