package org.luo.ai.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一轮对话的链路追踪视图（GET /api/trace 的返回项）。与实体 {@code AgentTrace} 的区别：把几个 JSON 字符串列
 * （工具调用、引用来源、记忆注入构成）解析成结构化列表供前端直接渲染，并屏蔽自增主键等内部字段。
 * <p>
 * <b>token 口径</b>：只统计「回答本身」相关的模型调用（正式回答及其工具调用循环）。路由判定、参数抽取、规划、
 * 记忆合并、视觉识别都走裸 {@code ChatModel}（不经 Advisor），不计入其中；故这些数值是「回答成本」而非
 * 「本轮全部成本」，横向对比足够，<b>别当账单</b>。
 *
 * @param mode           agent=普通/智能体对话，planner=规划模式
 * @param routeSource    BOUND=会话显式绑定，ROUTE=智能路由命中，NONE=通用助手，PLAN=规划编排，REVIEW=并行评审
 * @param retrievalQuery 本轮实际用于检索的问题（多轮改写产物）；null=未改写
 * @param memory         本轮注入的记忆构成；<b>null=未采集</b>（规划模式各步分别注入、或采集失败）—— 与「注入为空」不同，前端据此区分
 * @param toolCalls      工具调用明细（无调用为空表）
 * @param citations      RAG 引用来源（无引用为空表）
 * @param selfEval       本轮回答自评；<b>null=未自评</b>（采样未命中 / 回答过短 / 调用失败），与「自评给了低分」是两件事
 * @param status         ok / error
 */
public record TraceDto(String traceId, String conversationId, String mode, String routeSource, String agentCode,
                       String userMessage, String retrievalQuery, String planJson, MemoryInjectionDto memory,
                       List<ToolCallDto> toolCalls, int kbHitCount, List<KbCitation> citations, int promptTokens,
                       int completionTokens, int totalTokens, long elapsedMs, SelfEvalDto selfEval,
                       String status, String errorMessage, LocalDateTime createdAt) {

    /** 一条工具调用明细（参数与返回均已截断）。 */
    public record ToolCallDto(String name, String args, String result) {
    }

    /**
     * 本轮注入的记忆构成：窗口逐条 + 长期记忆两段字符数。
     * <p>
     * 窗口与长期记忆<b>刻意分开计</b>：窗口是近处原文（逐条可核对），事实/摘要是更早历史的压缩
     * （模型看到的是一整段文本）。混成一个总数就回答不了「它这次是靠摘要还是靠原文记起来的」。
     * <p>
     * {@code factsChars} 是<b>实际注入的那一段事实文本</b>的长度：逐条条目渲染后的文本，或条目为空时的
     * 旧版 {@code core_facts} 归档（注入侧本来就是二选一，故不再拆成两个数）。
     */
    public record MemoryInjectionDto(int windowChars, int summaryChars, int factsChars,
                                     List<InjectedMessage> window) {

        /** 本轮注入的总字符数（近似 token 口径）。 */
        public int totalChars() {
            return windowChars + summaryChars + factsChars;
        }
    }
}
