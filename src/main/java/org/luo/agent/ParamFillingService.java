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
 * 对声明了 {@code paramSchema} 的智能体，每轮先用 LLM 从「当前追问任务范围内的历史」
 * （见 {@link #clarifyScopedHistory}）抽取已收集参数：缺失必填项则生成追问（带 {@link #CLARIFY_PREFIX}
 * 前缀）直接返回、不调主模型，上限 {@link #MAX_CLARIFY} 次；齐全则把参数交给编排层注入 prompt。
 * <p>
 * <b>无显式状态</b>：追问计数与参数抽取都以 DB 历史为准（只读最近 {@value #HISTORY_SCAN_LIMIT} 条），
 * 每轮重放推导，天然跨请求/跨重启/多实例一致。<b>依赖「历史消息全量保留」</b>——若改为物理归档旧消息，
 * 必须同步本服务的重放逻辑，否则追问计数与已确认参数会静默丢失。
 */
@Slf4j
@Service
public class ParamFillingService {

    /** 单个智能体单轮对话最多追问次数（达到后转交主模型尽力执行，不再追问）。 */
    private static final int MAX_CLARIFY = 3;

    /** 追问消息的固定前缀：既是友好提示，也用于从历史中识别并统计连续追问段。 */
    private static final String CLARIFY_PREFIX = "🔎 还需补充信息";

    /** 历史读取上限：追问计数（≤6 条）与参数抽取（12 条）都只看尾部，50 条足够覆盖；避免长会话每轮无界拉取。 */
    private static final int HISTORY_SCAN_LIMIT = 50;

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
     * 参数补全决策：无 paramSchema → 直接转交主模型；必填齐全 → 转交并携带已确认参数；
     * 缺失未达上限 → 生成追问（落库由编排层完成）；已达上限 → 转交并附缺失提示，不再追问。
     *
     * @return question 非 null 表示需要追问，编排层直接返回该文本
     */
    public ClarifyDecision decideClarify(String conversationId, String message, Agent agent) {
        if (agent == null) {
            return new ClarifyDecision(null, Map.of(), Map.of(), false, List.of());
        }
        return decideClarify(conversationId, message, agent.getParamSchema());
    }

    /** 按 paramSchema（JSON 字符串）做决策；空则不追问。 */
    public ClarifyDecision decideClarify(String conversationId, String message, String paramSchema) {
        if (paramSchema == null || paramSchema.isBlank()) {
            return new ClarifyDecision(null, Map.of(), Map.of(), false, List.of());
        }
        List<ParamDef> schema = parseSchema(paramSchema);
        if (schema.isEmpty()) {
            return new ClarifyDecision(null, Map.of(), Map.of(), false, List.of());
        }
        // 历史不含本轮用户输入（advisor 在调主模型时落库；追问分支由编排层手动落库）
        List<ChatMessage> history = conversationService.getRecentHistory(conversationId, HISTORY_SCAN_LIMIT);
        int asked = countClarifyStreak(history);          // 已连续追问次数
        // 参数抽取范围 = 最后一次正式回答之后的用户请求到末尾（原始请求 + ≤MAX_CLARIFY 轮问答），天然有界
        List<ChatMessage> scoped = clarifyScopedHistory(history);
        Map<String, String> params = extractParams(message, scoped, schema);
        List<ParamDef> missing = missingRequired(schema, params);
        Map<String, String> paramLabels = schema.stream()
                .collect(Collectors.toMap(ParamDef::key, ParamDef::label, (a, b) -> a));
        if (missing.isEmpty()) {
            return new ClarifyDecision(null, params, paramLabels, false, List.of());
        }
        if (asked >= MAX_CLARIFY) {
            // 已达上限：转交主模型并列出缺失参数，令其尽力执行、不再追问
            List<String> missLabels = missing.stream().map(ParamDef::label).collect(Collectors.toList());
            log.info("追问达上限（{}）：转交主模型，缺失参数={}", MAX_CLARIFY, missLabels);
            return new ClarifyDecision(null, params, paramLabels, true, missLabels);
        }
        // 追问文本；落库由编排层在「确认进入追问且非话题切换」时统一完成，避免误落失效追问
        String question = buildQuestion(missing, asked + 1);
        log.info("参数补全追问（第 {} 次）：会话={}，缺失={}", asked + 1, conversationId,
                missing.stream().map(ParamDef::key).collect(Collectors.joining(",")));
        return new ClarifyDecision(question, params, paramLabels, false, List.of());
    }

    /**
     * 解析 agent.paramSchema（Hutool JSONUtil，规避 Jackson），失败返回空列表（不阻断对话）。
     * 结构：[{"key":"targetLang","label":"目标语言","required":true,"hint":"如：英语","options":["英语"]}]
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

    /** 最近一条追问文本（无则 null）：供编排层做话题切换预检，让路由区分「回答追问」与「开新话题」。 */
    public String lastClarifyQuestion(String conversationId) {
        // 最近一条追问一定落在尾部，有界读取即可（见 HISTORY_SCAN_LIMIT）
        List<ChatMessage> history = conversationService.getRecentHistory(conversationId, HISTORY_SCAN_LIMIT);
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
     * 用裸 ChatModel 从「当前任务切片 + 本轮输入」抽取参数（纯文本行式 "key: 取值"）。
     * 只在用户明确表达时填值、禁止臆测，失败回退空 Map。
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
     * 取最近 12 条历史用于参数抽取：让跟进任务（如「北京呢？」）继承上一任务的上下文（如「今天」→ 日期），
     * 避免参数被错判为缺失而重复追问。「禁止臆测」+ paramSchema 约束保证不会串入无关任务的参数。
     */
    private List<ChatMessage> clarifyScopedHistory(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) return history;
        int cap = 12;
        int start = Math.max(0, history.size() - cap);
        return history.subList(start, history.size());
    }

    /** 把 LLM 返回的 "key: value" 行解析为 map，只接受属于 schema 的 key、忽略噪声行。 */
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

    /** 统计「连续追问段」次数：从末尾回溯，带 {@link #CLARIFY_PREFIX} 的 assistant 计数，遇正式回答即停。 */
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
    private static final class ParamDef {
        final String key;
        final String label;
        final boolean required;
        final String hint;
        final List<String> options;

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
        final String question;
        final Map<String, String> params;
        final Map<String, String> paramLabels;
        final boolean limited;
        final List<String> missingLabels;

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
