package org.luo.ai.agent.handler;

import org.luo.ai.dto.KbCitation;

import java.util.List;

/**
 * 一轮对话的统一产出契约（各会话形态共用）：<b>结论</b>（reply / clarified）、<b>记忆契约</b>（needSaveExchange）、
 * <b>可选通道</b>（citations + 四个 JSON 字段，各自对应一类 SSE 事件，由 ChatService 按需取用）。
 *
 * @param reply            本轮回复文本（clarified=true 时为追问文本）
 * @param clarified        true=本轮是追问、未给正式回答
 * @param needSaveExchange 是否需显式把「用户原话 → 最终回复」写入记忆：规划多步执行为 true；普通对话由记忆 Advisor 自动落库，恒为 false
 * @param citations        本轮引用的 RAG 来源（对应正文 [n] 角标）；未走 RAG / 无命中为空表
 * @param planJson         待确认计划（JSON 对象：任务 id + 步骤清单）；非规划暂停为 null。仅流式推成 {@code plan} 事件
 * @param approvalJson     待批步骤信息（JSON 对象：任务 id + 步骤下标 + 智能体 + 指令）；未暂停为 null。仅流式推成 {@code approval} 事件
 * @param reviewJson       并行评审候选（JSON 对象 {@code {"candidates":[…]}}）；非评审为 null。<b>必须先于正文推送</b>
 * @param recallJson       跨会话召回片段（JSON 数组）；未开启/无命中为 null。是附加素材而非编排事件，可与其余通道并存
 */
public record RoundResult(String reply, boolean clarified, boolean needSaveExchange, List<KbCitation> citations,
                          String planJson, String approvalJson, String reviewJson, String recallJson) {

    /** 正式回答：无需显式补写记忆（普通路径由 Advisor 自动落库）。 */
    public static RoundResult answer(String reply) {
        return answer(reply, List.of());
    }

    /** 正式回答 + RAG 引用来源（供调用方落库 / 前端渲染角标）。 */
    public static RoundResult answer(String reply, List<KbCitation> citations) {
        return new RoundResult(reply, false, false, citations == null ? List.of() : citations,
                null, null, null, null);
    }

    /**
     * 规划暂停：本轮只产出「待确认计划」，不执行任何步骤。
     * <p>
     * {@code needSaveExchange=true} —— 计划已经作为真实交互发生（用户给了目标、助手回了计划），
     * 且执行阶段还会再补写一次「目标 → 最终结果」，两轮各自成对，语义完整。
     */
    public static RoundResult planned(String reply, String planJson) {
        return new RoundResult(reply, false, true, List.of(), planJson, null, null, null);
    }

    /**
     * 执行到审批关卡而暂停：本轮已跑完暂停点之前的步骤，剩余步骤保持 PENDING 等待批准。
     * <p>
     * {@code needSaveExchange=true} —— 与「先看计划」同一口径：暂停提示是用户真实收到的一条助手回复，
     * 应当落进会话记忆（否则刷新后它凭空消失）；批准后继续执行产出的最终结果再补写一对，两轮各自成对。
     */
    public static RoundResult approval(String reply, String approvalJson) {
        return new RoundResult(reply, false, true, List.of(), null, approvalJson, null, null);
    }

    /**
     * 并行评审：本轮由多个候选智能体作答，{@code reply} 是裁决者综合后的最终答案。
     * <p>
     * {@code needSaveExchange=true} —— 评审整体绕开记忆 Advisor（候选答案不该进历史，否则下一轮会把
     * 三份草稿当成「助手说过的话」），由实现方显式补写「用户原话 → 综合答案」这一对。
     */
    public static RoundResult reviewed(String reply, List<KbCitation> citations, String reviewJson) {
        return new RoundResult(reply, false, true, citations == null ? List.of() : citations,
                null, null, reviewJson, null);
    }

    /** 追问：本轮未给出正式回答，返回澄清问题（不涉及 RAG，引用恒为空）。 */
    public static RoundResult clarify(String question) {
        return new RoundResult(question, true, false, List.of(), null, null, null, null);
    }

    /**
     * 本策略无法处理（回退信号）：调用方应改用普通对话策略处理本轮（见 {@code RoundHandler} 契约）。
     * 语义上等价于旧的「返回 null」，但显式命名让回退意图一目了然，避免后续新 handler 误用 null。
     */
    public static RoundResult fallback() {
        return new RoundResult(null, false, false, List.of(), null, null, null, null);
    }

    /** 是否为回退信号（reply 为 null 即无法处理，调用方需回退普通对话策略）。 */
    public boolean isFallback() {
        return reply() == null;
    }

    /** 是否携带待确认计划（规划暂停）。 */
    public boolean hasPlan() {
        return planJson != null && !planJson.isBlank();
    }

    /** 是否因「执行到需审批步骤」而暂停（前端据此渲染审批卡片与「批准并继续」入口）。 */
    public boolean hasApproval() {
        return approvalJson != null && !approvalJson.isBlank();
    }

    /** 是否携带并行评审候选（前端据此渲染候选卡片；必须在正文之前推送）。 */
    public boolean hasReview() {
        return reviewJson != null && !reviewJson.isBlank();
    }

    /** 是否携带跨会话召回片段（前端据此渲染「回忆到的历史」列表）。 */
    public boolean hasRecall() {
        return recallJson != null && !recallJson.isBlank();
    }

    /**
     * 挂上跨会话召回结果。召回是<b>附加素材</b>（与 plan/approval/review 那三个「编排事件」性质不同），
     * 因此用这个后置方法组合，而不是给每个工厂再加一个参数——避免构造器参数爆炸。
     */
    public RoundResult withRecall(String recallJson) {
        if (recallJson == null || recallJson.isBlank()) return this;
        return new RoundResult(reply, clarified, needSaveExchange, citations,
                planJson, approvalJson, reviewJson, recallJson);
    }
}
