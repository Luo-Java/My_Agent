package org.luo.ai.trace;

import lombok.Getter;
import org.luo.ai.dto.InjectedMessage;
import org.luo.ai.dto.KbCitation;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

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

    /** 处理方来源：BOUND=会话显式绑定 / ROUTE=智能路由命中 / NONE=通用助手 / PLAN=规划编排 / REVIEW=并行评审。 */
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

    /**
     * 本轮注入的记忆构成（窗口逐条 + 长期摘要/长期事实的长度）。
     * {@code null} = <b>未采集</b>，可能是规划模式（各步骤分别注入，本字段不展开）、非对话链路，
     * 或采集本身失败 —— 与「采集到空窗口」是两件事，前端据此区分「没数据」与「这一轮确实什么都没注入」。
     */
    private MemoryInjection memoryInjection;

    /**
     * 执行过程进度回调（可选）。流式接口由 {@code ChatService} 注入，把各环节（路由 / 检索 / 工具调用）
     * 的进展实时推到前端（{@code StreamEvent.progress}）；同步接口为默认空实现。与「纯旁路、不影响对话」
     * 一脉相承：回调只做展示、抛异常由调用方兜住，缺数据可接受。
     */
    private Consumer<String> progress = text -> {};

    /** 工具名 → 中文动作的映射，用于把 Advisor 采集到的工具名翻译成可读的进度文案（未知工具名原样回显）。 */
    private static final Map<String, String> TOOL_LABELS = Map.of(
            "query", "查询数据库",
            "describe_table", "查看表结构",
            "sample_rows", "查看样例数据",
            "validate_sql", "校验 SQL",
            "chart_echarts", "生成图表",
            "chart_histogram", "生成分布图",
            "queryWeatherByDate", "查询天气",
            "queryWeatherByRange", "查询天气",
            "resolveDate", "解析日期");

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

    /**
     * 从 Spring AI advisor 上下文里取当轮追踪对象；不是本项目的上下文（如规划器内部的裸调用）返回 null。
     * <p>
     * 收在本类而不是各 Advisor 各写一份：键 {@link #CONTEXT_KEY} 与取值类型都是本类的私有约定，
     * 复制一份就多一处「键改了但那边没改」的静默失配——表现是追踪/预算悄悄失效且不报错。
     */
    public static RoundTrace from(Map<String, Object> context) {
        if (context == null) return null;
        Object value = context.get(CONTEXT_KEY);
        return (value instanceof RoundTrace t) ? t : null;
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
     * 记录本轮注入的记忆构成（覆盖语义，一轮只采一次）。
     * <p>
     * 三类入参刻意分开：窗口（近处原文，逐条可核对）与长期记忆两段（事实 / 摘要，模型看到的是一整段文本，
     * 只报长度）性质不同，混成一个总数就回答不了「它这次是靠摘要还是靠原文记起来的」。
     *
     * @param window  窗口内将被注入的历史（时间正序）；null 视为空
     * @param summary 长期摘要原文（null / 空 = 未注入）；只取长度
     * @param facts   注入的长期事实原文（条目文本，或条目为空时的旧版归档）；null / 空 = 未注入；只取长度
     */
    public void memoryInjection(List<InjectedMessage> window, String summary, String facts) {
        List<InjectedMessage> items = (window == null || window.isEmpty())
                ? List.of() : List.copyOf(window);
        int windowChars = items.stream().mapToInt(InjectedMessage::chars).sum();
        this.memoryInjection = new MemoryInjection(items, windowChars, lengthOf(summary), lengthOf(facts));
    }

    /**
     * 本轮注入的记忆构成：窗口明细 + 长期记忆两段的字符数。
     * <p>
     * {@code factsChars} 是<b>实际注入的那一段事实文本</b>的长度 —— 条目非空时是渲染后的条目列表，
     * 条目为空时是旧版 {@code core_facts} 归档。两者刻意不再分成两个数：注入侧本来就是「二选一」，
     * 分开只会多一个恒为 0 的字段，反而让人以为「有一类记忆没被注入」。
     */
    public record MemoryInjection(List<InjectedMessage> window, int windowChars, int summaryChars, int factsChars) {

        /** 本轮注入的总字符数（近似 token 口径）——「这一轮光记忆就占了多少上下文」。 */
        public int totalChars() {
            return windowChars + summaryChars + factsChars;
        }
    }

    /**
     * 记录本轮 RAG 引用（<b>覆盖</b>语义）。用覆盖而非追加：规划模式每步各自从 [1] 编号，
     * 跨步追加会出现重复序号、与正文角标对不上；最终回答由最后一步产出，故只保留那份编号。
     * 同步：DAG 并行的同层步骤可能并发回写，clear+addAll 非原子，需加锁。
     */
    public synchronized void citations(List<KbCitation> hits) {
        this.citations.clear();
        if (hits != null) {
            this.citations.addAll(hits);
        }
    }

    /**
     * 同步工具调用明细（<b>只增不减</b>语义）。Advisor 每轮模型调用都经过，传入的是「此刻完整历史里
     * 已执行的工具」（随循环单调增长）；故只在更长时替换，避免后一次调用（如最后一轮无工具）把已有记录清空。
     * 同时把<b>新增</b>的工具调用经 {@link #progress} 播报出去（仅运行期展示，不进记忆、不进落库）。
     * 同步：DAG 并行的同层步骤并发经此回写，「比较大小 + 替换」与进度播报有竞态，需加锁保证只增不减。
     */
    public synchronized void syncToolCalls(List<ToolCall> calls) {
        if (calls == null || calls.size() <= toolCalls.size()) {
            return;
        }
        // 只播报本次新增的部分（历史已有的不重复提醒）
        List<ToolCall> fresh = calls.subList(toolCalls.size(), calls.size());
        for (ToolCall tc : fresh) {
            progress.accept("🔧 调用工具：" + labelOf(tc.name()));
        }
        toolCalls.clear();
        toolCalls.addAll(calls);
    }

    /** 注入执行过程进度回调（流式接口传入，同步接口保持默认空实现）。 */
    public void onProgress(Consumer<String> callback) {
        if (callback != null) {
            this.progress = callback;
        }
    }

    /** 各环节直接播报一条执行过程（路由 / 检索 / 改写等前置链与工具循环共用同一通道）。 */
    public void reportProgress(String text) {
        if (text != null && !text.isBlank()) {
            this.progress.accept(text);
        }
    }

    /** 工具名 → 中文动作文案（未知工具名原样回显，避免「tool」这类兜底名显示空泛）。 */
    private static String labelOf(String name) {
        return name == null ? "未知工具" : TOOL_LABELS.getOrDefault(name, name);
    }

    /** 累加一次模型调用的 token 用量（工具循环内会有多次，累加得到本轮总量）。同步：DAG 并行下多线程累加需原子。 */
    public synchronized void addUsage(Integer prompt, Integer completion, Integer total) {
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

    /** 文本长度（null 安全）。 */
    private static int lengthOf(String text) {
        return text == null ? 0 : text.length();
    }

    /** 文本截断（null 安全）。 */
    private static String chop(String text, int limit) {
        if (text == null) return null;
        return text.length() <= limit ? text : text.substring(0, limit) + "...";
    }
}
