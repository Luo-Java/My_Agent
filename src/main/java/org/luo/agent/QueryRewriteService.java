package org.luo.agent;

import lombok.extern.slf4j.Slf4j;
import org.luo.config.PromptProperties;
import org.luo.config.RagProperties;
import org.luo.entity.ChatMessage;
import org.luo.service.ConversationService;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索查询改写（RAG 多轮指代消解）。
 * <p>
 * <b>为什么需要它</b>：知识库检索此前<b>直接把用户原话当检索问题</b>。但多轮对话里用户大量使用指代与省略
 * （「那它呢」「这个怎么算」「换成三年级呢」），这类短句单独拿去向量化，语义是残缺的——大概率召回一堆
 * 无关块。而<b>精排只能重排「已经召回来的候选」</b>：如果正确答案在召回阶段就没进来，精排再准也无从补救。
 * 所以改写必须发生在检索<b>之前</b>，它是精排的前置条件，不是替代品。
 * <p>
 * <b>做法</b>：取该会话最近 {@code agent.rag.query-rewrite-history-size} 条历史，连同本轮原话交给一次 LLM 调用，
 * 让它只做「补全指代与省略的主语/宾语」，产出一句语义完整、可独立检索的问题。提示词见
 * {@code prompts.yaml} 的 {@code agent.prompt.query-rewrite-system}（外置，改配置无需编译）。
 * <p>
 * <b>零成本短路</b>（三层，避免为不需要改写的场景白花一次模型调用）：
 * <ol>
 *   <li>配置关闭（{@code agent.rag.query-rewrite-enabled=false}）→ 原话返回；</li>
 *   <li>会话未开 RAG → 由调用方（{@link org.luo.chat.ChatComposer}）直接跳过，根本不进本服务；</li>
 *   <li><b>首轮无历史</b> → 没有任何可消解的上下文，直接原话返回（最常见的省钱点）。</li>
 * </ol>
 * <p>
 * <b>绝不阻断对话</b>：模型异常 / 返回空 / 结果明显跑偏，一律回退用户原话——改写是增强，不是依赖。
 * 与路由判定、参数抽取、规划、记忆合并同款：走<b>裸 {@code ChatModel}</b>（不经 Advisor、不挂工具、不写记忆），
 * 故不计入 {@code agent_trace} 的 token 口径（那口径只统计「回答成本」）。
 */
@Slf4j
@Service
public class QueryRewriteService {

    /** 模型偶尔会带上这些前缀，逐条剥掉（命中即止）。 */
    private static final String[] PREFIXES = {
            "改写后的问题：", "改写后的问题:", "改写为：", "改写为:", "改写：", "改写:", "检索问题：", "问题："
    };

    /** 成对包裹符号（中英文引号/书名号），成对出现时剥掉。 */
    private static final String[][] QUOTE_PAIRS = {
            {"\"", "\""}, {"'", "'"}, {"“", "”"}, {"‘", "’"}, {"「", "」"}, {"《", "》"}
    };

    /** 改写结果的长度护栏：超过「原话 3 倍」且超过该下限即视为模型跑偏（在回答问题/展开解释），回退原话。 */
    private static final int MIN_LENGTH_CEILING = 120;

    private final ChatModel chatModel;
    private final ConversationService conversationService;
    private final PromptProperties promptProperties;
    private final RagProperties ragProperties;

    public QueryRewriteService(ChatModel chatModel,
                              ConversationService conversationService,
                              PromptProperties promptProperties,
                              RagProperties ragProperties) {
        this.chatModel = chatModel;
        this.conversationService = conversationService;
        this.promptProperties = promptProperties;
        this.ragProperties = ragProperties;
        log.info("QueryRewriteService 初始化：查询改写开关={}，参与改写的历史条数={}",
                ragProperties.queryRewriteOn(), ragProperties.queryRewriteHistorySize());
    }

    /**
     * 把用户本轮原话改写为可独立检索的问题。
     * <p>
     * 任何一步不如意（开关关闭 / 无历史 / 调用异常 / 结果为空或跑偏）都返回<b>用户原话</b>，
     * 保证调用方拿到的永远是「一个可以拿去检索的字符串」，无需判空。
     *
     * @param conversationId 会话 ID（用于读取最近历史）
     * @param message        用户本轮原话
     * @return 改写后的问题；未改写时即原话
     */
    public String rewrite(String conversationId, String message) {
        if (message == null || message.isBlank()) return message;
        if (!ragProperties.queryRewriteOn()) return message;
        try {
            String historyText = recentHistoryText(conversationId);
            if (historyText.isBlank()) {
                // 首轮（或历史里没有任何可读文本）：没有指代可消解，直接原话，省一次模型调用
                return message;
            }
            String rephrased = call(historyText, message);
            String cleaned = sanitize(rephrased, message);
            if (!cleaned.equals(message)) {
                log.debug("查询改写：会话={}，「{}」→「{}」", conversationId, message, cleaned);
            }
            return cleaned;
        } catch (Exception e) {
            log.warn("查询改写失败，回退用户原话（不影响对话）：{}", e.getMessage());
            return message;
        }
    }

    /**
     * 取最近若干条历史拼成「用户：xxx / 助手：yyy」文本；不含本轮（本轮此刻尚未落库）。
     * 读取失败或内容为空返回空串（调用方据此跳过改写）。
     */
    private String recentHistoryText(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return "";
        // getRecentHistory 走 (conversation_id, created_at) 索引 + LIMIT，长会话也不会全量拉取
        List<ChatMessage> history = conversationService.getRecentHistory(
                conversationId, ragProperties.queryRewriteHistorySize());
        if (history == null || history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(256);
        for (ChatMessage m : history) {
            if (m == null) continue;
            String text = m.getContent();
            if (text == null || text.isBlank()) continue;   // 纯附件轮次可能 content 为空
            String role = "assistant".equals(m.getRole()) ? "助手" : "用户";
            sb.append(role).append("：").append(text).append("\n");
        }
        return sb.toString().strip();
    }

    /** 一次改写调用（裸 ChatModel：无 advisor、无工具、不写记忆）。 */
    private String call(String historyText, String message) {
        ChatResponse response = chatModel.call(new Prompt(List.of(
                new SystemMessage(promptProperties.queryRewriteSystem()),
                new UserMessage("对话历史：\n" + historyText
                        + "\n\n用户最新一条消息：\n" + message
                        + "\n\n请只输出改写后的问题："))));
        var generation = response.getResult();
        var assistantMessage = generation != null ? generation.getOutput() : null;
        return assistantMessage != null ? assistantMessage.getText() : null;
    }

    /**
     * 清洗模型输出并做跑偏护栏，任何一步不达标即回退原话：
     * <ul>
     *   <li>空/空白 → 原话；</li>
     *   <li>只取第一段非空行（模型偶尔会多写一行解释）；</li>
     *   <li>剥掉「改写为：」这类前缀与成对包裹的引号；</li>
     *   <li>长度超过「原话 3 倍」且超过 {@value #MIN_LENGTH_CEILING} 字 → 判定为在回答问题而非改写，回退原话。</li>
     * </ul>
     * 最后一条护栏很关键：改写器一旦「开始回答」，注入的知识库资料就会跟着跑偏，
     * 而这类失败是静默的——宁可退回用户原话，也不要把一段解释当检索问题用。
     */
    private static String sanitize(String rephrased, String original) {
        if (rephrased == null || rephrased.isBlank()) return original;
        String s = rephrased.strip();
        for (String line : s.split("\\R")) {
            if (!line.isBlank()) {
                s = line.strip();
                break;
            }
        }
        for (String prefix : PREFIXES) {
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length()).strip();
                break;
            }
        }
        s = stripQuotes(s);
        if (s.isBlank()) return original;
        int ceiling = Math.max(MIN_LENGTH_CEILING, original.length() * 3);
        if (s.length() > ceiling) {
            log.warn("查询改写结果过长（{} 字 > 上限 {} 字），疑似在回答问题，回退原话", s.length(), ceiling);
            return original;
        }
        return s;
    }

    /** 剥掉成对包裹的引号/书名号（首尾为同一对时才剥，避免误伤正文里的引号）。 */
    private static String stripQuotes(String s) {
        for (String[] pair : QUOTE_PAIRS) {
            if (s.length() > pair[0].length() + pair[1].length()
                    && s.startsWith(pair[0]) && s.endsWith(pair[1])) {
                return s.substring(pair[0].length(), s.length() - pair[1].length()).strip();
            }
        }
        return s;
    }
}
