package org.luo.ai.agent;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.properties.PromptProperties;
import org.luo.ai.entity.Agent;
import org.luo.ai.entity.ChatMessage;
import org.luo.ai.trace.LlmUsageService;
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
import org.luo.ai.dto.ClarifyState;
import org.luo.ai.service.ConversationService;

/**
 * 参数补全与追问服务。
 * <p>
 * 对声明了 {@code paramSchema} 的智能体，每轮先用 LLM 从「当前追问任务范围内的历史」
 * （见 {@link #clarifyScopedHistory}）抽取已收集参数：缺失必填项则生成追问（带 {@link #CLARIFY_PREFIX}
 * 前缀）直接返回、不调主模型，上限 {@link ClarifyState#MAX_ASKED} 次；齐全则把参数交给编排层注入 prompt。
 * <p>
 * <b>状态显式落库</b>（{@code conversation.clarify_state}，见 {@link ClarifyState}）：追问次数、原始请求锚点、
 * 已确认参数都从状态读，不再每轮扫历史推导。理由是重放会算歪 —— 历史超窗被摘要压缩后原始请求滑出窗口、
 * 消息被标「不参与记忆」后重放侧少算一次、长会话里连续追问段的起点落在扫描范围之外。
 * <p>
 * <b>历史重放作为兜底保留</b>：状态为空（本功能之前就已进入追问的老会话、或状态损坏）时退回原逻辑，
 * 行为与改造前一致，不因升级而回退。<b>仍然依赖「历史消息全量保留」</b>——兜底路径与参数抽取都用历史；
 * 若改为物理归档旧消息，必须同步本服务。
 */
@Slf4j
@Service
public class ParamFillingService {

    /** 追问消息的固定前缀：既是友好提示，也用于从历史中识别并统计连续追问段。 */
    private static final String CLARIFY_PREFIX = "🔎 还需补充信息";

    /** 历史读取上限：追问计数（≤6 条）与参数抽取（12 条）都只看尾部，50 条足够覆盖；避免长会话每轮无界拉取。 */
    private static final int HISTORY_SCAN_LIMIT = 50;

    private final ChatModel chatModel;
    private final ConversationService conversationService;
    private final PromptProperties promptProperties;
    /** 裸调用成本采集（全量成本口径，旁路异步，失败不影响参数抽取）。 */
    private final LlmUsageService llmUsageService;

    public ParamFillingService(ChatModel chatModel, ConversationService conversationService,
                               PromptProperties promptProperties, LlmUsageService llmUsageService) {
        this.chatModel = chatModel;
        this.conversationService = conversationService;
        this.promptProperties = promptProperties;
        this.llmUsageService = llmUsageService;
    }

    /**
     * 参数补全决策：无 paramSchema → 直接转交主模型；必填齐全 → 转交并携带已确认参数；
     * 缺失未达上限 → 生成追问（落库由编排层完成）；已达上限 → 转交并附缺失提示，不再追问。
     *
     * @return question 非 null 表示需要追问，编排层直接返回该文本
     */
    public ClarifyDecision decideClarify(String conversationId, String message, Agent agent) {
        if (agent == null) {
            return new ClarifyDecision(null, Map.of(), Map.of(), false, List.of(), null);
        }
        return decideClarify(conversationId, message, agent.getId(), agent.getParamSchema());
    }

    /** 按 paramSchema（JSON 字符串）做决策；空则不追问。{@code agentId} 用于判断已落库状态是否属于本智能体。 */
    public ClarifyDecision decideClarify(String conversationId, String message, Long agentId, String paramSchema) {
        if (paramSchema == null || paramSchema.isBlank()) {
            return new ClarifyDecision(null, Map.of(), Map.of(), false, List.of(), null);
        }
        List<ParamDef> schema = parseSchema(paramSchema);
        if (schema.isEmpty()) {
            return new ClarifyDecision(null, Map.of(), Map.of(), false, List.of(), null);
        }
        // 历史不含本轮用户输入（advisor 在调主模型时落库；追问分支由编排层手动落库）
        List<ChatMessage> history = conversationService.getRecentHistory(conversationId, HISTORY_SCAN_LIMIT);

        // 澄清状态优先：读显式落库的那一份；为空（本功能之前的老会话 / 状态损坏）才退回历史重放，
        // 行为与改造前一致。换智能体（agentId 不匹配）视同无状态：路由转向别的 agent 时状态自然作废。
        ClarifyState stored = conversationService.getClarifyState(conversationId);
        ClarifyState prev = (stored != null && stored.belongsTo(agentId)) ? stored : null;
        int asked;
        String anchor;
        Map<String, String> carried;
        if (prev != null) {
            asked = prev.asked();
            anchor = prev.request();
            carried = prev.params();
        } else {
            asked = countClarifyStreak(history);   // 兜底：从历史里数已连续追问几次
            anchor = null;
            carried = Map.of();
        }

        // 抽取范围 = 最近若干条 + 原始请求锚点（锚点即使已被摘要压缩出窗口也会补回来）
        List<ChatMessage> scoped = clarifyScopedHistory(history);
        Map<String, String> params = mergeParams(carried,
                extractParams(conversationId, message, scoped, schema, anchor));
        List<ParamDef> missing = missingRequired(schema, params);
        Map<String, String> paramLabels = schema.stream()
                .collect(Collectors.toMap(ParamDef::key, ParamDef::label, (a, b) -> a));
        if (missing.isEmpty()) {
            // 参数已齐：状态随之作废（nextState=null ⇒ 编排层清列），下一段追问从零开始
            return new ClarifyDecision(null, params, paramLabels, false, List.of(), null);
        }
        if (asked >= ClarifyState.MAX_ASKED) {
            // 已达上限：转交主模型并列出缺失参数，令其尽力执行、不再追问
            List<String> missLabels = missing.stream().map(ParamDef::label).collect(Collectors.toList());
            log.info("追问达上限（{}）：转交主模型，缺失参数={}", ClarifyState.MAX_ASKED, missLabels);
            return new ClarifyDecision(null, params, paramLabels, true, missLabels, null);
        }
        // 追问文本 + 本轮结束后的状态。落库由编排层在「确认进入追问且非话题切换」时与追问消息一并完成
        // （只落消息不落状态，下一轮就读不到原始请求锚点，本功能等于没做）。
        String question = buildQuestion(missing, asked + 1);
        String originalRequest = (anchor != null && !anchor.isBlank()) ? anchor : message;
        ClarifyState next = new ClarifyState(agentId, asked + 1, originalRequest, question, params);
        log.info("参数补全追问（第 {} 次）：会话={}，缺失={}", asked + 1, conversationId,
                missing.stream().map(ParamDef::key).collect(Collectors.joining(",")));
        return new ClarifyDecision(question, params, paramLabels, false, List.of(), next);
    }

    /** 合并参数：以已落库的快照为底、本轮新抽取的覆盖之（用户中途改口时新值胜出）。 */
    private static Map<String, String> mergeParams(Map<String, String> carried, Map<String, String> extracted) {
        if (carried == null || carried.isEmpty()) return extracted;
        Map<String, String> out = new LinkedHashMap<>(carried);
        out.putAll(extracted);
        return out;
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
        // 显式状态优先：它记的就是最近一次追问原文，且不受历史被压缩 / 被标「不参与记忆」影响
        ClarifyState st = conversationService.getClarifyState(conversationId);
        if (st != null && st.question() != null && !st.question().isBlank()) return st.question();
        // 兜底：本功能之前就已进入追问的老会话（状态列为空）退回历史扫描 —— 最近一条追问一定在尾部
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
     * 用裸 ChatModel 从「当前任务切片 + 原始请求锚点 + 本轮输入」抽取参数（纯文本行式 "key: 取值"）。
     * 只在用户明确表达时填值、禁止臆测，失败回退空 Map。
     *
     * @param anchorRequest 触发本次追问的原始请求；可能已被摘要压缩出窗口，故单独补进上下文。
     *                      若它本来就还在切片里（内容逐字相同）则不重复附加，避免白占 token
     */
    private Map<String, String> extractParams(String conversationId, String message, List<ChatMessage> history,
                                              List<ParamDef> schema, String anchorRequest) {
        String schemaText = schema.stream().map(p ->
                        "- " + p.key + "（" + (p.required ? "必填" : "可选") + "）：" + p.label
                                + (p.hint != null && !p.hint.isBlank() ? "；提示：" + p.hint : "")
                                + (p.options != null && !p.options.isEmpty() ? "；可选值：" + String.join("/", p.options) : ""))
                .collect(Collectors.joining("\n"));
        StringBuilder hist = new StringBuilder();
        boolean anchorInScope = history != null && history.stream()
                .anyMatch(m -> anchorRequest != null && anchorRequest.equals(m.getContent()));
        if (anchorRequest != null && !anchorRequest.isBlank() && !anchorInScope) {
            hist.append("user（本次任务的原始请求）：").append(anchorRequest).append('\n');
        }
        if (history != null) {
            hist.append(history.stream()
                    .map(m -> (m.getRole() != null ? m.getRole() : "user") + "：" + (m.getContent() == null ? "" : m.getContent()))
                    .collect(Collectors.joining("\n")));
        }
        String histText = hist.toString();
        try {
            ChatResponse r = chatModel.call(new Prompt(List.of(
                    new SystemMessage(PromptProperties.render(promptProperties.paramExtractorSystem(),
                            Map.of("schemaText", schemaText))),
                    new UserMessage("对话历史：\n" + (histText.isBlank() ? "（无）" : histText)
                            + "\n\n本次用户输入：\n" + message))));
            llmUsageService.recordAsync("CLARIFY", conversationId, null, r);
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
        if (missing.size() > 1) sb.append("（").append(n).append("/").append(ClarifyState.MAX_ASKED).append("）");
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
        /**
         * 追问文本；非 null 表示需要追问（供编排层 AgentRoundHandler 跨包访问）。
         */
        @Getter
        final String question;
        final Map<String, String> params;
        final Map<String, String> paramLabels;
        final boolean limited;
        final List<String> missingLabels;
        /**
         * 本轮追问落库后的澄清状态；<b>非 null 时编排层必须与追问消息一并写入</b>。
         * 为 null 表示本轮没有产生新追问（参数已齐 / 已达上限）—— 那意味着状态应当作废。
         */
        @Getter
        final ClarifyState nextState;

        ClarifyDecision(String question, Map<String, String> params, Map<String, String> paramLabels,
                        boolean limited, List<String> missingLabels, ClarifyState nextState) {
            this.question = question;
            this.params = params;
            this.paramLabels = paramLabels;
            this.limited = limited;
            this.missingLabels = missingLabels;
            this.nextState = nextState;
        }
    }
}
