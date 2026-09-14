package org.luo.trace;

import lombok.Getter;
import org.luo.dto.KbCitation;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 单轮对话的可观测上下文（一次对话 = 一个 RoundTrace）。
 * <p>
 * 生命期：{@code ChatService} 在本轮开始前 new，一路向下传到各编排环节，结束后交 {@link TraceService}
 * 异步落库 agent_trace。<b>可变收集器</b>——各环节往里塞自己那段事实（路由结论 / RAG 命中与引用 /
 * 计划 / Advisor 的工具调用与 token），谁也不必知道别人。
 * <p>
 * <b>为什么不用 ThreadLocal</b>：规划器要做 DAG 并行（同层步骤跑在不同线程），ThreadLocal 会静默丢数据。
 * 故本对象<b>显式</b>沿调用链传递，同时塞进 Spring AI 的 advisor 上下文（{@link #CONTEXT_KEY}）——
 * Advisor 只有这条路能看到它（与 CONVERSATION_ID 同款机制）。
 * <p>
 * <b>纯旁路、绝不影响对话</b>：收集方法都只是内存写、不抛异常、不做 IO；落库异步，失败只记日志。
 */
@Getter
public class RoundTrace {

    /** 在 Spring AI advisor 上下文里传递本对象的键（与 {@code ChatMemory.CONVERSATION_ID} 同级）。 */
    public static final String CONTEXT_KEY = "luo.roundTrace";

    /** 工具调用明细单条的长度上限（args / result 各自截断，防止超长 JSON 撑爆 TEXT 列）。 */
    private static final int TOOL_TEXT_LIMIT = 300;

    /** 用户输入与错误信息的长度上限（对齐 agent_trace.user_message / error_message 的列宽）。 */
    private static final int USER_MESSAGE_LIMIT = 1000;

    /** 本轮唯一 ID（同一轮内所有阶段共用，作为 agent_trace 的业务主键）。 */
    private final String traceId = UUID.randomUUID().toString();
    private final String conversationId;
    /**
     * 本轮形态：agent=普通/智能体对话，planner=规划模式。非 final：形态由 {@code ChatService.runRound}
     * 选中策略后才确定（请求级 planner 开关可临时改变），而 trace 在进入 runRound 前就要建好。
     */
    private String mode;
    /** 用户本轮输入（构造时截断）。 */
    private final String userMessage;

    private final LocalDateTime startedAt = LocalDateTime.now();
    private final long startNanos = System.nanoTime();

    /** 处理方来源：BOUND=会话显式绑定 / ROUTE=智能路由命中 / NONE=通用助手 / PLAN=规划编排。 */
    private String routeSource;
    /** 本轮实际处理（或规划最终步骤）的智能体编码。 */
    private String agentCode;
    /** 规划模式的步骤计划 JSON。 */
    private String planJson;
    /**
     * 本轮实际用于知识库检索的问题（多轮查询改写的产物）。
     * null = 未改写（未开 RAG / 首轮无历史 / 关闭改写 / 模型判定原话已自包含）——「没改写」本身就是信息，
     * 单列一项是为了让「RAG 没命中」可归因：是改写跑偏了，还是知识库里确实没有。
     */
    private String retrievalQuery;

    private final List<ToolCall> toolCalls = new ArrayList<>();
    private final List<KbCitation> citations = new ArrayList<>();

    private int promptTokens;
    private int completionTokens;
    private int totalTokens;

    private String status = "ok";
    private String errorMessage;
    private long elapsedMs;
    private LocalDateTime finishedAt;

    public RoundTrace(String conversationId, String userMessage) {
        this.conversationId = conversationId;
        this.userMessage = chop(userMessage, USER_MESSAGE_LIMIT);
    }

    // ==================== 各环节写入 ====================

    /** 记录本轮形态（agent / planner），由编排层选定策略后设置。 */
    public void mode(String mode) {
        this.mode = mode;
    }

    /** 记录处理方来源与智能体（重复调用以最后一次为准）。 */
    public void route(String routeSource, String agentCode) {
        this.routeSource = routeSource;
        this.agentCode = agentCode;
    }

    /** 记录规划模式产出的计划（JSON 文本）。 */
    public void plan(String planJson) {
        this.planJson = planJson;
    }

    /** 记录本轮实际用于检索的问题；调用方只在改写结果与用户原话不同时写入。 */
    public void retrievalQuery(String query) {
        this.retrievalQuery = chop(query, USER_MESSAGE_LIMIT);
    }

    /**
     * 记录本轮 RAG 引用（<b>覆盖</b>语义）。用覆盖而非追加：规划模式每步各自从 [1] 编号，
     * 跨步追加会出现重复序号、与正文角标对不上；最终回答由最后一步产出，故只保留那份编号。
     */
    public void citations(List<KbCitation> hits) {
        this.citations.clear();
        if (hits != null) {
            this.citations.addAll(hits);
        }
    }

    /**
     * 同步工具调用明细（<b>只增不减</b>语义）。Advisor 每轮模型调用都经过，传入的是「此刻完整历史里
     * 已执行的工具」（随循环单调增长）；故只在更长时替换，避免后一次调用（如最后一轮无工具）把已有记录清空。
     */
    public void syncToolCalls(List<ToolCall> calls) {
        if (calls != null && calls.size() > toolCalls.size()) {
            toolCalls.clear();
            toolCalls.addAll(calls);
        }
    }

    /** 累加一次模型调用的 token 用量（工具循环内会有多次，累加得到本轮总量）。 */
    public void addUsage(Integer prompt, Integer completion, Integer total) {
        if (prompt != null) promptTokens += prompt;
        if (completion != null) completionTokens += completion;
        if (total != null) {
            totalTokens += total;
        } else {
            totalTokens = promptTokens + completionTokens;
        }
    }

    /** 标记本轮异常（不抛错，只记录；由 TraceService 落库时体现）。 */
    public void markError(String message) {
        this.status = "error";
        this.errorMessage = chop(message, USER_MESSAGE_LIMIT);
    }

    /** 本轮结束：记录结束时间与总耗时（幂等，重复调用不覆盖首次结果）。 */
    public void finish() {
        if (finishedAt != null) return;
        this.elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        this.finishedAt = LocalDateTime.now();
    }

    /** 引用条数（供落库与日志用，避免调用方自己判空）。 */
    public int citationCount() {
        return citations.size();
    }

    /** 工具调用条数。 */
    public int toolCallCount() {
        return toolCalls.size();
    }

    /**
     * 只读视图：防止外部拿到内部可变列表（收集一律走 {@link #syncToolCalls}）。
     * 方法名与 Lombok 生成的 getter 同名，Lombok 检测到已存在则跳过生成，不会冲突。
     */
    public List<ToolCall> getToolCalls() {
        return Collections.unmodifiableList(toolCalls);
    }

    /** 只读视图：防止外部拿到内部可变列表（收集一律走 {@link #citations}）。 */
    public List<KbCitation> getCitations() {
        return Collections.unmodifiableList(citations);
    }

    /** 一条工具调用：工具名 + 入参 + 返回（后两者均截断）。 */
    public record ToolCall(String name, String args, String result) {
        /** 从原始值构造并截断（args / result 可能很长，如完整 JSON 行集）。 */
        public static ToolCall of(String name, String args, String result) {
            return new ToolCall(name, chop(args, TOOL_TEXT_LIMIT), chop(result, TOOL_TEXT_LIMIT));
        }
    }

    /** 文本截断（null 安全）。 */
    private static String chop(String text, int limit) {
        if (text == null) return null;
        return text.length() <= limit ? text : text.substring(0, limit) + "...";
    }
}
