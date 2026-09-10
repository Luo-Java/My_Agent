package org.luo.agent;

import lombok.extern.slf4j.Slf4j;
import org.luo.config.PromptProperties;
import org.luo.entity.Agent;
import org.luo.entity.ChatMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.luo.agent.handler.AgentRoundHandler;
import org.luo.entity.Conversation;
import org.luo.memory.DbChatMemory;
import org.luo.service.ChatService;
import org.luo.service.ConversationService;

/**
 * 参数补全与追问服务。
 * <p>
 * 对声明了 {@code paramSchema}（参数清单 JSON）的智能体，每轮先用 LLM 从「当前追问任务范围内的历史
 * （原始请求 + 至多 {@code MAX_CLARIFY} 轮问答，见 {@link #clarifyScopedHistory}）」抽取已收集参数，
 * 校验必填项；缺失则生成一条追问
 * （带 {@link #CLARIFY_PREFIX} 前缀用于计数）直接返回，不调用主模型；
 * 齐全后把已确认参数交给编排层注入主模型 prompt。追问次数受 {@link #MAX_CLARIFY} 上限约束，避免无限追问。
 * <p>
 * LLM 返回的参数抽取结果按「key: 取值」逐行输出后正则解析（不用 JSON 库）；
 * 而 agent.paramSchema 配置用 Hutool 的 {@code JSONUtil} 解析（规避 ObjectMapper，与智能路由解析风格一致）。
 * <p>
 * <b>跨 turn 一致性契约</b>：本服务<b>无显式状态</b>（不新增 Conversation 字段），每轮从
 * DB 全量历史重放推导——追问计数（{@link #countClarifyStreak}）与参数抽取
 * （{@link #clarifyScopedHistory} 最近 12 条）都以历史消息为准，天然跨请求、跨重启、多实例一致。
 * 该推导依赖「历史消息全量保留」：{@link MemoryMergeService} 的滚动摘要只把窗口外消息排除出
 * 主模型上下文、<b>不物理删除 chat_message 行</b>（见其类注释）。若未来改为物理归档旧消息，
 * 必须先同步本服务的重放逻辑（否则追问计数与已确认参数会静默丢失）。
 */
@Slf4j
@Service
public class ParamFillingService {

    /** 单个智能体单轮对话最多追问次数（达到后转交主模型尽力执行，不再追问）。 */
    private static final int MAX_CLARIFY = 3;

    /** 追问消息的固定前缀：既是友好提示，也用于从历史中识别并统计连续追问段。 */
    private static final String CLARIFY_PREFIX = "🔎 还需补充信息";

    private final ChatModel chatModel;
    private final ConversationService conversationService;
    private final PromptProperties promptProperties;

    public ParamFillingService(ChatModel chatModel, ConversationService conversationService,
                               PromptProperties promptProperties) {
        this.chatModel = chatModel;
        this.conversationService = conversationService;
        this.promptProperties = promptProperties;
    }

    /**
     * 参数补全决策（追问核心）：
     * <ul>
     *   <li>智能体未声明 paramSchema → 直接转交主模型（decision.question=null）；</li>
     *   <li>从全量历史抽取参数，必填齐全 → 转交主模型并携带已确认参数；</li>
     *   <li>必填缺失且未达追问上限 → 生成追问、落库 user+assistant，返回 decision.question；</li>
     *   <li>必填缺失且已达追问上限 → 转交主模型，并附「必要参数缺失」提示（不再追问）。</li>
     * </ul>
     * 参数累积完全依赖 AI 自带记忆（历史由 DbChatMemory 持久化），不在 Conversation 上新增任何字段。
     *
     * @return 若 question 非 null 表示需要追问（已落库），编排层直接返回该文本；否则走主流程。
     */
    public ClarifyDecision decideClarify(String conversationId, String message, Agent agent) {
        if (agent == null) {
            return new ClarifyDecision(null, Map.of(), Map.of(), false, List.of());
        }
        return decideClarify(conversationId, message, agent.getParamSchema());
    }

    /**
     * 按参数清单（paramSchema JSON 字符串）做参数补全决策；paramSchema 为空时不追问。
     * 供 {@link Agent} 复用（智能体声明 paramSchema 时启用追问/参数补全）。
     */
    public ClarifyDecision decideClarify(String conversationId, String message, String paramSchema) {
        if (paramSchema == null || paramSchema.isBlank()) {
            return new ClarifyDecision(null, Map.of(), Map.of(), false, List.of());
        }
        List<ParamDef> schema = parseSchema(paramSchema);
        if (schema.isEmpty()) {
            return new ClarifyDecision(null, Map.of(), Map.of(), false, List.of());
        }
        // 历史（不含本轮用户输入，由 advisor 在调用主模型时落库；追问分支由本方法手动落库）
        List<ChatMessage> history = conversationService.getHistory(conversationId);
        int asked = countClarifyStreak(history);                       // 已连续追问次数（只看 assistant 消息，开销可忽略，用全量）
        // 参数抽取只取「当前这一次追问任务」的范围：从最后一次正式回答（非追问）之后的用户请求开始，
        // 到历史末尾。即「原始请求 + 至多 MAX_CLARIFY 轮问答」，天然有界，且跨任务自动隔离
        // （同一会话里开新请求 = 新的参数作用域，不被更早的无关对话污染）。详见 clarifyScopedHistory。
        List<ChatMessage> scoped = clarifyScopedHistory(history);
        Map<String, String> params = extractParams(message, scoped, schema);
        List<ParamDef> missing = missingRequired(schema, params);
        Map<String, String> paramLabels = schema.stream()
                .collect(Collectors.toMap(ParamDef::key, ParamDef::label, (a, b) -> a));
        if (missing.isEmpty()) {
            return new ClarifyDecision(null, params, paramLabels, false, List.of());
        }
        if (asked >= MAX_CLARIFY) {
            // 已达追问上限：转交主模型，列出仍缺失的必要参数，令其尽力执行、不再追问
            List<String> missLabels = missing.stream().map(ParamDef::label).collect(Collectors.toList());
            log.info("追问达上限（{}）：转交主模型，缺失参数={}", MAX_CLARIFY, missLabels);
            return new ClarifyDecision(null, params, paramLabels, true, missLabels);
        }
        // 生成追问文本（本轮不再调用主模型）。落库改由编排层 ChatService 在确认进入追问、且非话题切换时统一完成，
        // 避免「用户中途切换话题」时被误落库一条已失效的追问记录。
        String question = buildQuestion(missing, asked + 1);
        log.info("参数补全追问（第 {} 次）：会话={}，缺失={}", asked + 1, conversationId,
                missing.stream().map(ParamDef::key).collect(Collectors.joining(",")));
        return new ClarifyDecision(question, params, paramLabels, false, List.of());
    }

    /**
     * 解析 agent.paramSchema 为参数定义列表。使用 Hutool 的 {@code JSONUtil} 解析（规避 Jackson ObjectMapper），
     * 与智能路由的 JSON 解析风格保持一致。解析失败返回空列表（不阻断对话）。结构示例：
     * [{"key":"targetLang","label":"目标语言","required":true,"hint":"如：英语","options":["英语","日语"]}]
     */
    private List<ParamDef> parseSchema(String json) {
        try {
            JSONArray arr = JSONUtil.parseArray(json);
            if (arr.isEmpty()) return List.of();
            List<ParamDef> out = new ArrayList<>();
            for (Object o : arr) {
                if (!(o instanceof JSONObject obj)) continue;
                String key = obj.getStr("key");
                if (key == null || key.isBlank()) continue;
                String label = obj.getStr("label");
                if (label == null || label.isBlank()) label = key;
                boolean required = obj.getBool("required", false);
                String hint = obj.getStr("hint");
                JSONArray opts = obj.getJSONArray("options");
                List<String> options = (opts == null) ? null : opts.toList(String.class);
                out.add(new ParamDef(key, label, required, hint, options));
            }
            return out;
        } catch (Exception e) {
            log.warn("参数Schema解析失败：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 返回会话中最近一条追问（{@link #CLARIFY_PREFIX} 前缀的 assistant 消息）的完整文本；
     * 没有追问记录返回 null。供编排层在做「话题切换预检」时作为上下文传给路由，
     * 让路由 LLM 区分「在回答追问」与「开启了新话题」。
     */
    public String lastClarifyQuestion(String conversationId) {
        List<ChatMessage> history = conversationService.getHistory(conversationId);
        if (history == null || history.isEmpty()) return null;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if ("assistant".equals(m.getRole())
                    && m.getContent() != null && m.getContent().startsWith(CLARIFY_PREFIX)) {
                return m.getContent();
            }
        }
        return null;
    }

    /**
     * 用裸 ChatModel 从「当前追问任务范围内的历史 + 本轮输入」抽取已明确给出的参数值
     * （纯文本行式，每行 "key: 取值"）。历史只取当前任务切片（见 {@link #clarifyScopedHistory}），
     * 即原始请求 + 至多 MAX_CLARIFY 轮问答，避免被无关历史污染、也避免成本无界增长。
     * 只在用户明确表达时填值，禁止臆测；未提及的参数不输出任何行。抽取失败回退空 Map。
     */
    private Map<String, String> extractParams(String message, List<ChatMessage> history, List<ParamDef> schema) {
        String schemaText = schema.stream().map(p ->
                        "- " + p.key + "（" + (p.required ? "必填" : "可选") + "）：" + p.label
                                + (p.hint != null && !p.hint.isBlank() ? "；提示：" + p.hint : "")
                                + (p.options != null && !p.options.isEmpty() ? "；可选值：" + String.join("/", p.options) : ""))
                .collect(Collectors.joining("\n"));
        String histText = history.stream()
                .map(m -> (m.getRole() != null ? m.getRole() : "user") + "：" + (m.getContent() == null ? "" : m.getContent()))
                .collect(Collectors.joining("\n"));
        try {
            ChatResponse r = chatModel.call(new Prompt(List.of(
                    new SystemMessage(PromptProperties.render(promptProperties.paramExtractorSystem(),
                            Map.of("schemaText", schemaText))),
                    new UserMessage("对话历史：\n" + (histText.isBlank() ? "（无）" : histText)
                            + "\n\n本次用户输入：\n" + message))));
            var generation = r.getResult();
            var assistantMessage = generation != null ? generation.getOutput() : null;
            String reply = assistantMessage != null ? assistantMessage.getText() : null;
            if (reply == null || reply.isBlank()) return Map.of();
            return parseParamLines(reply, schema);
        } catch (Exception e) {
            log.warn("参数抽取失败，回退空：{}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 把 LLM 返回的参数行（"key: value"）解析为 map。只接受属于 schema 的 key，忽略噪声行。
     */
    /**
     * 取最近若干轮历史（上限 12 条）用于参数抽取。覆盖最近一个完整任务（原始请求 + 至多 {@code MAX_CLARIFY} 轮追问 + 回答），
     * 让跟进任务（例如「北京呢？」跟在查过深圳天气之后）能继承上一任务里用户已明确给出的隐式上下文
     * （最典型是「今天」→ 日期=今天），避免本已齐的参数被错判为缺失、再次追问（例如又问「日期？」）。
     * <p>
     * 早先版本以「最后一次正式回答」为界硬切，会把上一任务的原始请求一起切掉，导致跟进任务拿不到「今天」等关键上下文。
     * 现在改为条数截断：「禁止臆测」规则 + {@code paramSchema} 约束保证不会把别的智能体/无关任务的参数串进当前抽取
     * （不同智能体的 schema 不同，同智能体也只有用户明确表达过的取值才会被采集）。
     */
    private List<ChatMessage> clarifyScopedHistory(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) return history;
        int cap = 12;
        int start = Math.max(0, history.size() - cap);
        return history.subList(start, history.size());
    }

    private Map<String, String> parseParamLines(String reply, List<ParamDef> schema) {
        Set<String> keys = schema.stream().map(ParamDef::key).collect(Collectors.toSet());
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : reply.split("\\R")) {
            String line = raw.trim();
            int c = line.indexOf(':');
            if (c < 0) continue;
            String k = line.substring(0, c).trim();
            String v = line.substring(c + 1).trim();
            if (v.isEmpty() || !keys.contains(k)) continue;
            out.put(k, v);
        }
        return out;
    }

    /** 返回必填但未收集到的参数定义列表。 */
    private List<ParamDef> missingRequired(List<ParamDef> schema, Map<String, String> params) {
        List<ParamDef> miss = new ArrayList<>();
        for (ParamDef p : schema) {
            if (p.required && !params.containsKey(p.key)) miss.add(p);
        }
        return miss;
    }

    /**
     * 统计「连续追问段」已发生的次数：从历史末尾向前回溯，
     * 遇到带 {@link #CLARIFY_PREFIX} 的 assistant 消息计数 +1，遇到不带前缀的 assistant（正式回答）即停止，
     * 遇到 user 消息跳过（用户的补充回答不结束追问段）。用于限制无限追问。
     */
    private int countClarifyStreak(List<ChatMessage> history) {
        int count = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if (!"assistant".equals(m.getRole())) continue;
            if (m.getContent() != null && m.getContent().startsWith(CLARIFY_PREFIX)) {
                count++;
            } else {
                break; // 正式回答出现，追问段结束
            }
        }
        return count;
    }

    /** 生成追问文本（带计数与 CLARIFY_PREFIX 前缀）。 */
    private String buildQuestion(List<ParamDef> missing, int n) {
        StringBuilder sb = new StringBuilder(CLARIFY_PREFIX);
        if (missing.size() > 1) sb.append("（").append(n).append("/").append(MAX_CLARIFY).append("）");
        sb.append("：\n");
        for (ParamDef p : missing) {
            sb.append("- ").append(p.label);
            if (p.hint != null && !p.hint.isBlank()) sb.append("（").append(p.hint).append("）");
            if (p.options != null && !p.options.isEmpty()) sb.append("，可选：").append(String.join(" / ", p.options));
            sb.append("\n");
        }
        sb.append("请补充以上信息，我就马上为你处理～");
        return sb.toString();
    }

    /** 把已确认参数 / 超限提示拼成追加到 system prompt 的文本。 */
    public String buildParamBlock(ClarifyDecision d) {
        StringBuilder sb = new StringBuilder();
        if (d.params != null && !d.params.isEmpty()) {
            sb.append("\n\n[已确认参数] 用户已明确提供以下参数，执行任务时必须直接使用，不要再次询问：\n");
            for (Map.Entry<String, String> e : d.params.entrySet()) {
                String label = d.paramLabels.getOrDefault(e.getKey(), e.getKey());
                sb.append("- ").append(label).append("：").append(e.getValue()).append("\n");
            }
        }
        if (d.limited && d.missingLabels != null && !d.missingLabels.isEmpty()) {
            sb.append("\n[参数提示] 以下必要参数用户多次未提供：").append(String.join("、", d.missingLabels))
                    .append("。请基于已有信息尽力完成任务，不要再反复追问。\n");
        }
        return sb.toString();
    }

    /** 参数定义（来自 agent.paramSchema 的单条）。 */
    // [{"key":"language","label":"语言","required":true,"hint":"请说明要翻译成的语言","options":["英语/日语/韩语"]}]
    private static final class ParamDef {
        final String key;       //参数表示
        final String label;       //参数中文标签
        final boolean required;   //是否必填
        final String hint;       //提示信息
        final List<String> options;   //可选值  示例

        ParamDef(String key, String label, boolean required, String hint, List<String> options) {
            this.key = key;
            this.label = label;
            this.required = required;
            this.hint = hint;
            this.options = options;
        }

        String key() { return key; }
        String label() { return label; }
    }

    /** 参数补全决策结果。question 非 null 表示需要追问；否则转交主模型（params 为已确认参数）。 */
    public static final class ClarifyDecision {
        final String question;        //问题
        final Map<String, String> params;    //已有参数
        final Map<String, String> paramLabels;    //参数标签
        final boolean limited;        //是否到达追问次数上限
        final List<String> missingLabels;   //缺失参数

        /** 追问文本；非 null 表示需要追问（供编排层 AgentRoundHandler 跨包访问）。 */
        public String getQuestion() {
            return question;
        }

        ClarifyDecision(String question, Map<String, String> params, Map<String, String> paramLabels,
                        boolean limited, List<String> missingLabels) {
            this.question = question;
            this.params = params;
            this.paramLabels = paramLabels;
            this.limited = limited;
            this.missingLabels = missingLabels;
        }
    }
}
