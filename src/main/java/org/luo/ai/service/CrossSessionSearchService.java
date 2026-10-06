package org.luo.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.dto.RecallHit;
import org.luo.ai.entity.ChatMessage;
import org.luo.ai.entity.Conversation;
import org.luo.ai.mapper.ChatMessageMapper;
import org.luo.ai.mapper.ConversationMapper;
import org.luo.ai.properties.CrossSessionProperties;
import org.luo.ai.properties.PromptProperties;
import org.luo.ai.trace.LlmUsageService;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 跨会话搜索：在<b>本人其他会话</b>的历史消息里做关键词召回，产出「注入 system 的文本 + 展示用命中列表」。
 * <p>
 * 与知识库检索（{@link KbSearchService}）的关系：两者都是「先检索、再注入」，但<b>检索对象与机制完全不同</b> ——
 * 知识库检索外部资料（向量 + 精排），本服务检索用户自己的历史对话（关键词字面匹配）。刻意把两者做成两条独立
 * 链路而不是复用一个「统一检索服务」：向量与关键词的召回语义、阈值口径、失败降级方式都不一样，硬合并只会
 * 让两边都变得难解释。
 * <p>
 * <b>为什么用关键词而不是向量</b>：会话消息是逐轮写入的，走向量检索意味着每条消息落库时都要多一次 embedding
 * 调用（成本翻倍）并新增一套向量副本维护链路；而现有的向量设施是围绕知识库块建的，把会话消息塞进同一空间会
 * 让「知识库命中」与「历史回忆」互相干扰。代价是<b>召回质量受限于提词质量与字面匹配</b>：换个说法就召回不到，
 * 这是明确接受的边界，不是缺陷。
 * <p>
 * <b>绝不阻断对话</b>：与 RAG 同一原则 —— 提词失败、SQL 出错、模型异常一律降级为「本轮不带历史」，
 * 只记日志。但<b>降级不静默</b>：命中 0 条会在执行过程里明确播报，让用户能区分「真没有」与「功能没生效」。
 * <p>
 * <b>零成本短路</b>：会话开关未开 / 全局开关关闭 / 本轮消息为空 —— 三者任一成立都直接返回空，
 * 一次模型调用与一次 SQL 都不发生。
 */
@Slf4j
@Service
public class CrossSessionSearchService {

    /** 召回片段的时间展示格式（与 RecallHit 保持一致）。 */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 关键词长度上限：过长的「关键词」几乎不可能命中，还白白拉长 SQL。 */
    private static final int MAX_KEYWORD_CHARS = 20;

    /** LIKE 通配符与转义符：关键词来自 LLM 输出，必须清洗后再进 SQL 参数（否则 `%` 会变成全表匹配）。 */
    private static final char[] LIKE_SPECIALS = {'%', '_', '\\'};

    private final ChatMessageMapper chatMessageMapper;
    private final ConversationMapper conversationMapper;
    private final ChatModel chatModel;
    private final PromptProperties promptProperties;
    private final CrossSessionProperties props;
    /** 裸调用成本采集（新增用途 {@code RECALL}，与 REWRITE / ROUTE 等同一口径）。 */
    private final LlmUsageService llmUsageService;

    public CrossSessionSearchService(ChatMessageMapper chatMessageMapper,
                                     ConversationMapper conversationMapper,
                                     ChatModel chatModel,
                                     PromptProperties promptProperties,
                                     CrossSessionProperties props,
                                     LlmUsageService llmUsageService) {
        this.chatMessageMapper = chatMessageMapper;
        this.conversationMapper = conversationMapper;
        this.chatModel = chatModel;
        this.promptProperties = promptProperties;
        this.props = props;
        this.llmUsageService = llmUsageService;
    }

    /**
     * 执行一次跨会话召回。
     *
     * @param currentConversationId 当前会话（<b>会被排除</b>：它的内容已经在记忆窗口里）
     * @param message               本轮用户原话
     * @param userId                当前登录用户（归属过滤，SQL 层强制）
     * @param progress              执行过程播报（可传空回调）；命中数对用户可见
     * @return 注入文本 + 展示列表；未开启 / 无关键词 / 无命中时返回 {@link Recall#EMPTY}
     */
    public Recall recall(String currentConversationId, String message, Long userId, Consumer<String> progress) {
        if (!props.enabledOn() || userId == null || message == null || message.isBlank()) {
            return Recall.EMPTY;
        }
        try {
            List<String> keywords = extractKeywords(currentConversationId, message);
            if (keywords.isEmpty()) {
                // 明确播报：让「提不出关键词」与「提出来了但没命中」在用户看来是两件事
                progress.accept("🔎 未从本轮问题中提取到可用于检索的关键词");
                return Recall.EMPTY;
            }
            List<ChatMessage> rows = chatMessageMapper.searchOwned(
                    userId, currentConversationId, keywords, props.recall());
            if (rows == null || rows.isEmpty()) {
                progress.accept("🔎 未在历史会话中回忆到相关内容");
                return Recall.EMPTY;
            }
            List<RecallHit> hits = toHits(rows, keywords);
            String json = RecallHit.toJson(hits);
            progress.accept("🔎 回忆起 " + hits.size() + " 条历史记录");
            log.debug("跨会话召回：会话={}，关键词={}，SQL 候选 {} 条 → 注入 {} 条",
                    currentConversationId, keywords, rows.size(), hits.size());
            return new Recall(render(hits), json, hits.size());
        } catch (Exception e) {
            // 检索是增强不是依赖：任何异常都只降级为「本轮不带历史」，但绝不静默到不播报
            log.warn("跨会话召回失败，本轮不带历史（不影响对话）：{}", e.getMessage());
            progress.accept("🔎 历史召回失败，本轮不带历史记忆");
            return Recall.EMPTY;
        }
    }

    // ==================== ① 关键词提取 ====================

    /** 一次裸模型调用抽出检索关键词（无 advisor、无工具、不写记忆）；失败返回空列表。 */
    private List<String> extractKeywords(String conversationId, String message) {
        String template = promptProperties.crossSessionQuerySystem();
        if (template == null || template.isBlank()) {
            // 模板缺失属于配置问题，必须留痕（否则表现为「功能开着但永远不召回」）
            log.warn("跨会话召回的提词模板为空（请检查 prompts.yaml 的 agent.prompt.cross-session-query-system）");
            return List.of();
        }
        try {
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(template),
                    new UserMessage(message))));
            llmUsageService.recordAsync("RECALL", conversationId, null, response);
            var generation = response.getResult();
            var out = generation != null ? generation.getOutput() : null;
            String text = out != null ? out.getText() : null;
            return parseKeywords(text);
        } catch (Exception e) {
            log.warn("跨会话召回的关键词提取失败，本轮不检索：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析「每行一个关键词」的输出并清洗：剥前缀编号与引号 → 去 LIKE 通配符 → 去重 → 截断长度 → 限量。
     * 任何一步都不抛：关键词质量差只会让召回变差，不该让整轮对话失败。
     */
    private List<String> parseKeywords(String text) {
        if (text == null || text.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : text.split("\\R")) {
            String k = cleanKeyword(raw);
            if (k.isEmpty()) continue;
            out.add(k);
            if (out.size() >= props.keywords()) break;
        }
        return new ArrayList<>(out);
    }

    /** 清洗单个关键词：去行首编号/项目符号/引号 → 去 LIKE 通配符与反斜杠 → 限长（仍为空则丢弃）。 */
    private static String cleanKeyword(String raw) {
        if (raw == null) return "";
        String k = raw.strip()
                .replaceAll("^[-*•\\d.、)（(\\s]+", "")     // 行首编号、项目符号
                .replaceAll("^[\"'“”‘’「」《》]+|[\"'“”‘’「」《》]+$", "")   // 成对引号
                .strip();
        StringBuilder sb = new StringBuilder(k.length());
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            boolean special = false;
            for (char s : LIKE_SPECIALS) {
                if (c == s) {
                    special = true;
                    break;
                }
            }
            if (!special) sb.append(c);
        }
        String cleaned = sb.toString().strip();
        if (cleaned.length() > MAX_KEYWORD_CHARS) cleaned = cleaned.substring(0, MAX_KEYWORD_CHARS);
        return cleaned;
    }

    // ==================== ② 命中整形 ====================

    /**
     * SQL 行 → 展示用命中列表：取前 {@code top-k} 条，逐条算命中关键词数并截断片段。
     * <p>
     * 命中数在内存里重算（SQL 只负责排序）：这样 SQL 的 {@code resultType} 可以保持 {@code ChatMessage}
     * 走列名自动映射，不必为多出来的聚合列引入 record 投影（那会踩「列数/列序必须一一对应」的坑）。
     * 排序已在 SQL 完成，这里只做截断，<b>不重排</b>（重排会让 SQL 的命中数排序白做）。
     */
    private List<RecallHit> toHits(List<ChatMessage> rows, List<String> keywords) {
        int limit = Math.min(props.k(), rows.size());
        Map<String, String> titles = titles(rows.subList(0, limit));
        List<RecallHit> hits = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            ChatMessage m = rows.get(i);
            String content = m.getContent() == null ? "" : m.getContent().strip();
            hits.add(new RecallHit(
                    i + 1,
                    m.getConversationId(),
                    titles.get(m.getConversationId()),
                    m.getRole(),
                    m.getCreatedAt(),
                    snippet(content),
                    hitCount(content, keywords)));
        }
        return hits;
    }

    /** 批量取会话标题（命中的会话可能分布在多个会话里）：一次 IN 查询，避免逐条回查。 */
    private Map<String, String> titles(List<ChatMessage> rows) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ChatMessage m : rows) {
            if (m.getConversationId() != null) ids.add(m.getConversationId());
        }
        Map<String, String> map = new LinkedHashMap<>();
        if (ids.isEmpty()) return map;
        List<Conversation> convs = conversationMapper.selectList(new QueryWrapper<Conversation>()
                .select("id", "title")
                .in("id", ids));
        for (Conversation c : convs) {
            map.put(c.getId(), c.getTitle());
        }
        return map;
    }

    /** 命中关键词个数（大小写不敏感：中文无影响，英文关键词容错）。 */
    private static int hitCount(String content, List<String> keywords) {
        String lower = content.toLowerCase();
        int n = 0;
        for (String k : keywords) {
            if (lower.contains(k.toLowerCase())) n++;
        }
        return n;
    }

    /** 片段截断：超长时保留前段 + 省略号（截断是有意的，避免单条历史吃光注入预算）。 */
    private String snippet(String content) {
        int max = props.snippet();
        if (content.length() <= max) return content;
        return content.substring(0, max) + "…";
    }

    // ==================== ③ 注入文本 ====================

    /** 套 {@code cross-session-context} 模板渲染注入块（编号即展示列表序号）；模板缺失返回空串。 */
    private String render(List<RecallHit> hits) {
        StringBuilder items = new StringBuilder(512);
        for (RecallHit h : hits) {
            items.append("[").append(h.index()).append("] [")
                    .append(h.conversationTitle() == null ? "未命名会话" : h.conversationTitle());
            if (h.createdAt() != null) {
                items.append(" · ").append(h.createdAt().format(TIME_FMT));
            }
            items.append(" · ").append("user".equals(h.role()) ? "用户" : "助手");
            items.append("] ").append(h.snippet()).append("\n");
        }
        String block = PromptProperties.render(promptProperties.crossSessionContext(),
                Map.of("items", items.toString().strip()));
        if (block == null || block.isBlank()) {
            log.warn("跨会话历史块模板为空（请检查 prompts.yaml 的 agent.prompt.cross-session-context），本轮不带历史");
            return "";
        }
        return "\n\n" + block.strip();
    }

    /**
     * 召回结果：{@code text}=注入 system 的文本（空串表示无召回），{@code json}=SSE 事件载荷，
     * {@code hits}=命中条数（日志与测试用）。三者<b>同趟产出</b> —— 编号与展示列表必须一一对应，
     * 拆两次算会让序号与内容漂移（与 KbCitation 同一约定）。
     */
    public record Recall(String text, String json, int hits) {

        /** 无召回（未开启 / 无关键词 / 无命中 / 失败）。 */
        public static final Recall EMPTY = new Recall("", "", 0);

        /** 是否无召回内容。 */
        public boolean isEmpty() {
            return text == null || text.isBlank();
        }
    }
}
