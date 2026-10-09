package org.luo.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.dto.RecallHit;
import org.luo.ai.entity.ChatMessage;
import org.luo.ai.entity.Conversation;
import org.luo.ai.mapper.ChatMessageMapper;
import org.luo.ai.mapper.ConversationMapper;
import org.luo.ai.infrastructure.rerank.RerankService;
import org.luo.ai.properties.CrossSessionProperties;
import org.luo.ai.properties.PromptProperties;
import org.luo.ai.properties.RagProperties;
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
 * 跨会话搜索：在<b>本人其他会话</b>的历史消息里做召回，产出「注入 system 的文本 + 展示用命中列表」。
 * <p>
 * <b>两段式检索</b>：① <b>关键词召回</b>（提词 → 有界 {@code LIKE}，按命中词项数降序）→
 * ② <b>语义重排</b>（{@link RerankService} 交叉编码按 query↔片段打分，丢弃低分候选）。
 * 召回侧靠「提词扩展」（见 prompts.yaml 的 {@code cross-session-query-system}：要求同时产出原词与
 * 用户当时可能用的同义说法），排序侧靠精排 —— 两头补的就是原来纯字面匹配缺的「语义」。
 * <p>
 * 与 {@link KbSearchService} 的关系：都是「先检索、再注入」，但<b>对象不同</b> —— 前者检索外部资料
 * （向量 + 关键词 + RRF 融合 + 精排），本服务检索用户自己的历史对话（关键词 + 精排重排）。
 * 刻意不合并成一个「统一检索服务」：两者的召回语义、阈值口径、失败降级方式都不一样，硬合并只会让两边都难解释。
 * <p>
 * <b>为何不走向量</b>：会话消息逐轮写入，给每条消息存向量意味着每轮多一次 embedding 调用，且要新增一套
 * 向量副本维护链路（写入、删除、回填、一致性）；而现有向量设施围绕 {@code kb_chunk} 建，混进会话消息会让
 * 「知识库命中」与「历史回忆」互相干扰。语义能力改由「提词扩展 + 精排重排」承担：前者让换种说法也能召回，
 * 后者把碰巧含同一个词但语境无关的片段筛掉。<b>代价</b>：召回仍是字面驱动，用户当时若用了完全不同的措辞、
 * 且该措辞未被提词模型想到，仍会漏 —— 这是明确接受的边界（要突破得先解决上面那条副本链路的成本与一致性）。
 * <p>
 * <b>绝不阻断对话</b>：提词失败、SQL 出错、精排异常一律降级为「本轮不带历史」或「沿用字面排序」并记日志；
 * 但<b>降级不静默</b> —— 命中 0 条会明确播报，且「字面没命中」与「命中但被语义筛掉」播报不同。
 * <b>零成本短路</b>：会话开关未开 / 全局开关关闭 / 本轮消息为空，任一成立即直接返回空，零模型调用、零 SQL。
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

    /**
     * 语义重排的候选条数上限：精排<b>按条计费</b>，不必把 SQL 拉回的候选全送去打分
     * （SQL 已按命中词项数降序，高相关区就在前若干条）。
     */
    private static final int RERANK_CANDIDATES = 10;

    private final ChatMessageMapper chatMessageMapper;
    private final ConversationMapper conversationMapper;
    private final ChatModel chatModel;
    private final PromptProperties promptProperties;
    private final CrossSessionProperties props;
    private final RerankService rerankService;
    /** 只读精排阈值（{@code agent.rag.rerank-min-score}）：与知识库复用同一把尺子（同一个精排模型，同一套 0~1 分）。 */
    private final RagProperties ragProps;
    /** 裸调用成本采集（新增用途 {@code RECALL}，与 REWRITE / ROUTE 等同一口径）。 */
    private final LlmUsageService llmUsageService;

    public CrossSessionSearchService(ChatMessageMapper chatMessageMapper,
                                     ConversationMapper conversationMapper,
                                     ChatModel chatModel,
                                     PromptProperties promptProperties,
                                     CrossSessionProperties props,
                                     RerankService rerankService,
                                     RagProperties ragProps,
                                     LlmUsageService llmUsageService) {
        this.chatMessageMapper = chatMessageMapper;
        this.conversationMapper = conversationMapper;
        this.chatModel = chatModel;
        this.promptProperties = promptProperties;
        this.props = props;
        this.rerankService = rerankService;
        this.ragProps = ragProps;
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
            // 候选条数随「是否语义重排」变：要重排就得多取一些给它筛 —— 否则只是在 topK 条里挑 topK 条，
            // 重排只能过滤、不能换人，等于白做。不重排时维持原口径，行为与改造前逐字一致。
            boolean useRerank = reranking();
            int candidates = useRerank ? Math.min(props.recall(), RERANK_CANDIDATES) : props.k();
            List<RecallHit> hits = toHits(rows, keywords, candidates);
            int before = hits.size();
            hits = semanticRerank(hits, message, useRerank);
            if (hits.isEmpty()) {
                // 「字面命中但语义不相关被筛掉」与「压根没命中」是两件事，播报必须分开
                progress.accept("🔎 历史召回命中 " + before + " 条，语义筛选后均不相关，本轮不带历史");
                return Recall.EMPTY;
            }
            String json = RecallHit.toJson(hits);
            String tail = before != hits.size() ? "（语义筛选自 " + before + " 条）" : "";
            progress.accept("🔎 回忆起 " + hits.size() + " 条历史记录" + tail);
            log.debug("跨会话召回：会话={}，检索词={}，SQL 候选 {} 条 → 注入 {} 条（语义重排={}）",
                    currentConversationId, keywords, rows.size(), hits.size(), useRerank);
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
     * SQL 行 → 展示用命中列表：取前 {@code limit} 条，逐条算命中关键词数并截断片段。
     * <p>
     * 命中数在内存里重算（SQL 只负责排序）：这样 SQL 的 {@code resultType} 可以保持 {@code ChatMessage}
     * 走列名自动映射，不必为多出来的聚合列引入 record 投影（那会踩「列数/列序必须一一对应」的坑）。
     * 排序已在 SQL 完成，这里只做截断，<b>不重排</b>（重排会让 SQL 的命中数排序白做）。
     *
     * @param limit 候选条数上限（是否语义重排决定取值，见 {@link #recall}）
     */
    private List<RecallHit> toHits(List<ChatMessage> rows, List<String> keywords, int limit) {
        int n = Math.min(limit, rows.size());
        Map<String, String> titles = titles(rows.subList(0, n));
        List<RecallHit> hits = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ChatMessage m = rows.get(i);
            String content = m.getContent() == null ? "" : m.getContent().strip();
            hits.add(new RecallHit(
                    i + 1,
                    m.getConversationId(),
                    titles.get(m.getConversationId()),
                    m.getRole(),
                    m.getCreatedAt(),
                    snippet(content),
                    hitCount(content, keywords),
                    0));   // 语义分留待重排阶段回填；此处 0 表示「尚未重排」
        }
        return hits;
    }

    // ==================== ②b 语义重排 ====================

    /** 是否对召回候选做语义重排：开关开 <b>且</b> 精排服务在途可用。 */
    private boolean reranking() {
        return props.semanticRerankOn() && rerankService.available();
    }

    /**
     * 语义重排：把「字面命中」的候选交给交叉编码器按 query↔片段<b>成对</b>打分，重排并丢弃低于
     * {@code agent.rag.rerank-min-score} 的候选。
     * <p>
     * <b>它补的是什么</b>：字面匹配召回的是「包含同一个词」的句子，但同一个词在不同语境下未必相关
     * （问「项目进度」会召回「这个项目我放弃了」）。精排是 query 与候选一起过模型，判的是<b>语义相关</b>，
     * 正是字面一路缺的那半。这也是本条链路不做向量副本的原因：语义能力由「提词扩展（召回侧）」+
     * 「精排重排（排序侧）」两头补，无需为每条消息多存一份向量并维护副本。
     * <p>
     * 失败即降级为「命中词数」排序（返回值与入参同序），绝不阻断对话；返回空表表示候选被全部判为不相关。
     */
    private List<RecallHit> semanticRerank(List<RecallHit> hits, String query, boolean enabled) {
        if (!enabled || hits.isEmpty()) {
            return hits;
        }
        List<String> docs = new ArrayList<>(hits.size());
        for (RecallHit h : hits) {
            docs.add(h.snippet());
        }
        List<RerankService.Ranked> ranked = rerankService.rerank(query, docs, props.k());
        if (ranked == null) {
            log.debug("跨会话语义重排不可用或调用失败，沿用命中词数排序（{} 条）", hits.size());
            return hits;
        }
        List<RecallHit> out = new ArrayList<>(Math.min(props.k(), ranked.size()));
        for (RerankService.Ranked r : ranked) {
            if (r.index() < 0 || r.index() >= hits.size()) {
                continue;   // 响应越界，防御跳过
            }
            if (r.score() < ragProps.rerankMinScore()) {
                continue;   // 语义相关度不足
            }
            RecallHit src = hits.get(r.index());
            // 重编号：序号必须与注入文本的行首编号一致，重排后沿用旧序号会让「编号↔列表」错位
            out.add(new RecallHit(out.size() + 1, src.conversationId(), src.conversationTitle(),
                    src.role(), src.createdAt(), src.snippet(), src.hitCount(), r.score()));
            if (out.size() >= props.k()) {
                break;
            }
        }
        return out;
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
