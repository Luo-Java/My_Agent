package org.luo.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一轮对话的链路追踪视图（GET /api/trace 的返回项）。
 * <p>
 * 与实体 {@code AgentTrace} 的区别：把两个 JSON 字符串列（工具调用、引用来源）解析成结构化的列表，
 * 前端直接渲染即可，不必自己 JSON.parse；同时屏蔽自增主键等内部字段。
 * <p>
 * <b>token 口径</b>：只统计「回答本身」相关的模型调用——正式回答及其工具调用循环。
 * 路由判定、参数抽取、规划、记忆合并、视觉识别都走裸 {@code ChatModel}（不经 Advisor），不计入其中；
 * 因此这里的数值是「回答成本」而非「本轮全部成本」，用作横向对比足够，别当账单。
 *
 * @param traceId          本轮唯一追踪 ID
 * @param conversationId   所属会话 ID
 * @param mode             agent=普通/智能体对话，planner=规划模式
 * @param routeSource      BOUND=会话显式绑定，ROUTE=智能路由命中，NONE=通用助手，PLAN=规划编排
 * @param agentCode        本轮实际处理（规划模式为最终步骤）的智能体编码
 * @param userMessage      用户本轮输入（已截断）
 * @param retrievalQuery   本轮实际用于知识库检索的问题（多轮查询改写产物）；null=未改写
 * @param planJson         规划模式的步骤计划 JSON（未规划为 {@code []} 或 null）
 * @param toolCalls        工具调用明细（无调用为空表）
 * @param kbHitCount       RAG 命中条数
 * @param citations        RAG 引用来源（无引用为空表）
 * @param promptTokens     输入 token 合计（口径见下）
 * @param completionTokens 输出 token 合计
 * @param totalTokens      token 合计
 * @param elapsedMs        本轮总耗时（毫秒）
 * @param status           ok / error
 * @param errorMessage     异常信息（status=error 时）
 * @param createdAt        记录时间
 */
public record TraceDto(String traceId, String conversationId, String mode, String routeSource, String agentCode,
                       String userMessage, String retrievalQuery, String planJson, List<ToolCallDto> toolCalls,
                       int kbHitCount, List<KbCitation> citations, int promptTokens, int completionTokens,
                       int totalTokens, long elapsedMs, String status, String errorMessage, LocalDateTime createdAt) {

    /** 一条工具调用明细（参数与返回均已截断）。 */
    public record ToolCallDto(String name, String args, String result) {
    }
}
