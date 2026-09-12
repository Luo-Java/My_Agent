package org.luo.agent.handler;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.luo.dto.KbCitation;
import org.luo.entity.Agent;
import org.luo.entity.Conversation;
import org.luo.service.AgentService;
import org.luo.chat.ChatComposer;
import org.luo.agent.PlannerService;
import org.luo.agent.PlannerService.PlanStep;
import org.luo.trace.RoundTrace;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.luo.service.KbSearchService;

/**
 * 规划模式会话策略：由动态规划器（PlannerService）在运行时根据用户目标产出多智能体步骤，
 * 再顺序执行（前一步输出作为后一步输入）。规划器不预配置步骤、不追问参数，对用户最友好。
 * <p>
 * 与普通对话策略（AgentRoundHandler）的关键差异在<b>记忆写入契约</b>：
 * 本策略全程使用无记忆的 {@link ChatComposer#internalChatClient()}（避免「指令+上一步输出」
 * 这类合成串污染会话历史），在回复产出后由 {@link #savePlannerExchange} 显式补写
 * 「用户原话 → 最终回复」整对（{@link RoundResult#needSaveExchange()} 为 true）。
 * 执行过程通过 {@code progress} 回调实时对外播报（规划中 / 计划清单 / 每步开始与完成），
 * 这些文本<b>只用于展示，不写入会话记忆</b>——记忆里只有「用户原话 → 最终回复」。
 * <p>
 * <b>RAG 引用只取最后一步</b>：每一步各自检索、各自从 [1] 开始编号，若跨步合并会出现重复序号、
 * 与最终回答正文的角标对不上。最终回答由最后一步产出，故只回传那份引用（见 {@link StepsOutcome}）。
 */
@Slf4j
@Service
public class PlannerRoundHandler implements RoundHandler {

    /** 追踪用：处理方来源枚举值（与 agent_trace.route_source 列注释一致）。 */
    private static final String SRC_PLAN = "PLAN";
    private static final String SRC_NONE = "NONE";

    private final PlannerService plannerService;
    private final AgentService agentService;
    private final ChatComposer composer;
    private final ChatMemory chatMemory;

    public PlannerRoundHandler(PlannerService plannerService,
                               AgentService agentService,
                               ChatComposer composer,
                               ChatMemory chatMemory) {
        this.plannerService = plannerService;
        this.agentService = agentService;
        this.composer = composer;
        this.chatMemory = chatMemory;
    }

    @Override
    public RoundResult handle(Conversation conv, String conversationId, String message, String material,
                             Consumer<String> progress, RoundTrace trace) {
        // message 为纯提问（不含附件）；附件材料 material 仅当轮注入（首步 / 兜底），不进会话记忆。
        PlannerOutcome po = handlePlannerConversation(conv, conversationId, message, material, progress, trace);
        // 防御：规划未产出任何结果（理论不会发生）→ 显式回退信号，调用方转普通对话策略
        if (po.reply() == null) return RoundResult.fallback();
        // 多步执行期间不写记忆，需在回复产出后显式补写「用户原话 → 最终回复」整对
        if (po.needSaveExchange()) savePlannerExchange(conversationId, message, po.reply());
        return RoundResult.answer(po.reply(), po.citations());
    }

    /**
     * 规划模式会话处理。规划为空（无可用智能体 / 目标与任何智能体无关）→ 回退普通助手直接回答；
     * 计划中的智能体编码不存在 → 跳过该步；全部不存在 → 回退普通回答；执行复用 {@link #executeSteps}，
     * 记忆由 {@link #savePlannerExchange} 统一落库，工具全程挂载。
     * <p>
     * 本方法<b>不写记忆</b>：是否需要显式落库由返回值的 {@code needSaveExchange} 告知调用方
     * （见 {@link PlannerOutcome}）。
     *
     * @param progress 进度回调（流式接口传事件推送，同步接口传空回调）
     * @param trace    本轮追踪上下文（可为 null）：记录计划与最终处理方
     * @return 本轮结果（回复文本 + 是否需显式写记忆 + RAG 引用）
     */
    private PlannerOutcome handlePlannerConversation(Conversation conv, String conversationId, String message,
                                                     String material, Consumer<String> progress, RoundTrace trace) {
        progress.accept("🧭 正在分析目标并规划执行步骤…");
        List<PlanStep> plan = plannerService.plan(message);
        log.info("动态规划：会话={}, 步骤={}", conversationId, plan);
        if (plan == null || plan.isEmpty()) {
            log.info("动态规划：无需编排（无可用智能体或目标无关），普通回答");
            progress.accept("💬 无需多智能体协同，由通用助手直接回答");
            if (trace != null) {
                trace.plan("[]");
                trace.route(SRC_NONE, null);   // 实际由通用助手处理
            }
            // 回退路径走带记忆的 chatClient，记忆由 Advisor 自动落库，无需显式补写
            return answerDefault(conv, conversationId, message, material, trace);
        }
        List<StepSpec> specs = new ArrayList<>();
        for (PlanStep s : plan) {
            Agent a = agentService.getByCode(s.agentCode());
            if (a == null) {
                log.warn("动态规划：智能体编码 {} 不存在，跳过该步骤", s.agentCode());
                progress.accept("⚠️ 跳过：智能体「" + s.agentCode() + "」不存在");
                continue;
            }
            specs.add(new StepSpec(a, s.instruction()));
        }
        if (specs.isEmpty()) {
            log.warn("动态规划：计划中的智能体均不存在，回退普通回答");
            progress.accept("💬 计划中的智能体都不可用，改由通用助手回答");
            if (trace != null) {
                trace.plan(planJson(specs));
                trace.route(SRC_NONE, null);
            }
            return answerDefault(conv, conversationId, message, material, trace);
        }
        log.info("动态规划启动：会话={}，步骤数={}", conversationId, specs.size());
        if (trace != null) {
            trace.plan(planJson(specs));
            trace.route(SRC_PLAN, specs.get(specs.size() - 1).agent().getAgentCode());
        }
        // 播报计划清单：让用户先看到「准备怎么做」，再看到逐步执行情况
        StringBuilder planText = new StringBuilder("📋 规划完成，共 " + specs.size() + " 步：");
        for (int i = 0; i < specs.size(); i++) {
            StepSpec s = specs.get(i);
            planText.append("\n").append(i + 1).append(". ").append(s.agent().getName());
            if (s.instruction() != null && !s.instruction().isBlank()) {
                planText.append(" —— ").append(s.instruction());
            }
        }
        progress.accept(planText.toString());
        StepsOutcome executed = executeSteps(specs, message, conversationId, conv, null, material, progress, trace);
        if (executed.reply() == null || executed.reply().isBlank()) {
            log.warn("动态规划未产出结果，回退普通回答");
            progress.accept("⚠️ 各步骤均未产出结果，改由通用助手回答");
            if (trace != null) trace.route(SRC_NONE, null);
            return answerDefault(conv, conversationId, message, material, trace);
        }
        progress.accept("✅ 全部步骤执行完毕，已生成最终结果");
        // 多步规划：执行期间所有步骤都用 internalChatClient（不写记忆），避免「指令+上一步输出」这类合成串
        // 污染会话历史；需由调用方在回复推送后显式补写「用户原话 → 最终回复」整对（needSaveExchange=true）。
        return new PlannerOutcome(executed.reply(), true, executed.citations());
    }

    /**
     * 顺序执行一组步骤（智能体 + 指令）：前一步输出作为后一步输入。
     * <ul>
     *   <li>第 1 步输入 = firstInput（用户原始目标），并注入长期记忆与已确认参数（paramBlock）；</li>
     *   <li>后续步骤输入 = 本步指令 + 上一步输出；</li>
     *   <li>所有步骤均用无记忆 ChatClient，不写会话历史，避免中间产物/合成串污染；
     *       最后一步额外注入近期窗口历史（historyContext）以替代记忆 Advisor 的上下文读取，保证回答连贯；</li>
     *   <li>执行结束后由 {@link #savePlannerExchange} 把「用户原话 → 最终回复」整对落库；</li>
     *   <li>任一步骤失败/返回空则沿用上一步结果；全部失败返回 null；</li>
     *   <li>每步的开始/完成/失败通过 {@code progress} 回调实时播报，这些文本只用于展示、不进记忆。</li>
     * </ul>
     *
     * @return 最终回复 + 产出该回复那一步的 RAG 引用（无引用为空表）
     */
    private StepsOutcome executeSteps(List<StepSpec> steps, String firstInput, String conversationId,
                                      Conversation conv, String paramBlock, String material,
                                      Consumer<String> progress, RoundTrace trace) {
        if (steps == null || steps.isEmpty()) return new StepsOutcome(null, List.of());
        // 近期窗口历史（替代记忆 Advisor 的读取）：仅注入到最后一步，避免合成输入被误写入记忆。
        String historyContext = composer.buildHistoryContextText(conversationId, 0,
                "\n\n[近期对话] 以下为本轮之前同一会话的近期上下文（仅供参考，请勿复述）：\n");
        String previous = null;   // 上一步的输出（供下一步作为输入）
        String last = null;       // 最后一个成功步骤的输出（即最终结果）
        List<KbCitation> lastCitations = List.of();   // 产出最终结果那一步的 RAG 引用
        for (int i = 0; i < steps.size(); i++) {
            StepSpec s = steps.get(i);
            boolean isLast = (i == steps.size() - 1);
            String stepTag = "步骤 " + (i + 1) + "/" + steps.size() + " · " + s.agent().getName();
            progress.accept("▶ " + stepTag + " 执行中…");
            String userInput = (i == 0) ? firstInput : "上一步的输出：\n" + previous;
            // 附件材料仅注入首步（用户原始目标所在步），后续步以上一步产物为输入，避免重复放大
            if (i == 0 && material != null && !material.isBlank()) {
                userInput = userInput + "\n\n[本轮附件材料] 以下为用户本轮上传的内容（图片已识别、文档已解析为文本），"
                        + "仅作本次参考：\n" + material;
            }
            if (s.instruction() != null && !s.instruction().isBlank()) {
                userInput = s.instruction() + "\n\n" + userInput;
            }
            try {
                // 所有步骤都不挂记忆 Advisor：中间产物绝不写入会话历史
                // 长期记忆（核心信息/历史摘要）对所有步骤注入：用户偏好应贯穿整条流水线；
                // 已确认参数只在第一步注入（后续步骤以上一步产物为输入，无关参数只会造成干扰）；
                // 最后一步追加近期窗口历史，保持与单智能体对话一致的上下文连贯性。
                // 知识库检索跟随会话级 RAG 开关：开启后自动查「全局库 + 本步骤 agent 的专属库」，
                // 未开启则不检索（用户选择的「纯开关 + 自动多库」语义，见 KbSearchService.buildKbContext）
                KbSearchService.KbContext kb = composer.buildKbContext(composer.ragOn(conv), s.agent(), userInput);
                String system = composer.applyRealtimeRule(composer.buildSystemPrompt(s.agent())
                        + kb.text()
                        + composer.buildLongTermMemoryText(conv)
                        + (i == 0 && paramBlock != null ? paramBlock : "")
                        + (isLast && !historyContext.isBlank() ? historyContext : ""));
                ChatClient.ChatClientRequestSpec spec = composer.internalChatClient()
                        .prompt().system(system).user(userInput);
                spec = composer.decorateRequest(spec, s.agent(), trace);
                String out = spec.call().content();
                if (out != null && !out.isBlank()) {
                    previous = out;
                    last = out;
                    lastCitations = kb.citations();   // 只保留产出最终结果那一步的引用（编号与正文角标一致）
                    log.info("顺序执行：步骤 {}/{}（{}）完成，输出长度={}", i + 1, steps.size(),
                            s.agent().getAgentCode(), out.length());
                    progress.accept("✔ " + stepTag + " 完成（产出 " + out.length() + " 字）");
                } else {
                    log.warn("顺序执行：步骤 {}/{}（{}）返回空，沿用上一步结果", i + 1, steps.size(), s.agent().getAgentCode());
                    progress.accept("⚠️ " + stepTag + " 未产出内容，沿用上一步结果");
                }
            } catch (Exception e) {
                log.error("顺序执行：步骤 {}/{}（{}）执行失败：{}", i + 1, steps.size(),
                        s.agent().getAgentCode(), e.getMessage(), e);
                progress.accept("✖ " + stepTag + " 执行失败：" + e.getMessage());
            }
        }
        return new StepsOutcome(last, lastCitations);
    }

    /** 通用助手兜底回答（无智能体绑定、不挂载工具）：用于规划模式回退、或规划目标与任何智能体无关时。
     *  附件材料 material 仅当轮注入 system，不进会话记忆；RAG 引用随结果一并带回。 */
    private PlannerOutcome answerDefault(Conversation conv, String conversationId, String message,
                                         String material, RoundTrace trace) {
        ChatComposer.ComposedRequest composed =
                composer.buildDefaultRequest(conv, conversationId, message, material, trace);
        String reply = composed.spec().call().content();
        return new PlannerOutcome(reply != null ? reply : "", false, composed.citations());
    }

    /** 计划步骤 → JSON（写进 agent_trace.plan_json，供事后回看 LLM 当时的编排决策）。 */
    private static String planJson(List<StepSpec> specs) {
        JSONArray arr = new JSONArray();
        for (StepSpec s : specs) {
            JSONObject o = new JSONObject();
            o.set("agentCode", s.agent().getAgentCode());
            o.set("agentName", s.agent().getName());
            o.set("instruction", s.instruction());
            arr.add(o);
        }
        return arr.toString();
    }

    /**
     * 把规划模式的一轮对话（用户原话 + 最终回复）写入会话记忆。
     * 顺序执行期间各步骤都走无记忆 ChatClient、不落库，故在这里统一补写，
     * 否则 chat_message 里存的是被污染的「指令+上一步输出」，而非用户真正的问题。
     */
    private void savePlannerExchange(String conversationId, String userMessage, String assistantReply) {
        try {
            chatMemory.add(conversationId, List.of(
                    new UserMessage(userMessage),
                    new AssistantMessage(assistantReply)));
            log.debug("规划记忆写入：会话={}", conversationId);
        } catch (Exception e) {
            log.error("规划记忆写入失败：会话={}", conversationId, e);
        }
    }

    /**
     * 规划模式一轮的产出。
     *
     * @param reply            最终回复文本
     * @param needSaveExchange 是否需要显式把「用户原话 → 最终回复」写入记忆：
     *                         多步执行为 {@code true}（执行期全程不写记忆）；
     *                         回退到通用助手为 {@code false}（记忆 Advisor 已自动落库，重复写会出现两遍）
     * @param citations        最终回复引用到的 RAG 来源（取产出该回复那一步的引用；无则为空表）
     */
    private record PlannerOutcome(String reply, boolean needSaveExchange, List<KbCitation> citations) {
    }

    /** 顺序执行结果：最终回复 + 产出该回复那一步的 RAG 引用。 */
    private record StepsOutcome(String reply, List<KbCitation> citations) {
    }

    /** 顺序执行的一个步骤：执行哪个智能体 + 给它的补充指令（被 executeSteps 消费）。 */
    private record StepSpec(Agent agent, String instruction) {
    }
}
