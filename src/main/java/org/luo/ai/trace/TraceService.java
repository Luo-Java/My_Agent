package org.luo.ai.trace;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.dto.InjectedMessage;
import org.luo.ai.dto.KbCitation;
import org.luo.ai.entity.AgentTrace;
import org.luo.ai.mapper.AgentTraceMapper;
import org.luo.ai.properties.PiiProperties;
import org.luo.common.util.PiiJsonMasker;
import org.luo.common.util.PiiMasker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 链路追踪服务：把 {@link RoundTrace} 收集到的一轮事实落库，并提供查询。四条不可破的原则：
 * ① <b>异步</b>——落库走 {@code traceExecutor}，在回复产出之后执行，绝不挡在用户看到答案之前；
 * ② <b>旁路</b>——不参与任何对话逻辑，业务代码从不读 agent_trace，删掉整张表对话照常运行；
 * ③ <b>不抛错</b>——从提交任务到 INSERT 全链路 try/catch，失败只记日志（队列满时宁可丢追踪）；
 * ④ <b>归属边界</b>——查询一律以「会话归属」为可见性边界（管理员的全量视角是唯一的例外），
 * 非本人记录按「不存在」处理。归属判定不写进表、不改写入链路，见 {@code AgentTraceMapper.xml}。
 */
@Slf4j
@Service
public class TraceService {

    /** 单次查询上限：避免调用方传巨大 limit 把整表拉出来。 */
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 50;

    private final AgentTraceMapper mapper;
    private final Executor traceExecutor;
    private final PiiProperties piiProperties;

    public TraceService(AgentTraceMapper mapper, @Qualifier("traceExecutor") Executor traceExecutor,
                        PiiProperties piiProperties) {
        this.mapper = mapper;
        this.traceExecutor = traceExecutor;
        this.piiProperties = piiProperties;
    }

    /**
     * 落库前的 PII 脱敏；开关关闭时原样返回。
     * <p>
     * 与 {@code chat_message} 侧共用<b>同一个开关</b>：存在跨表逐字匹配契约（见 {@link PiiProperties}），
     * 两处必须同开同关，否则「反馈转用例」「点踩自评」的匹配会静默失效。
     */
    private String mask(String text) {
        return piiProperties.enabledOn() ? PiiMasker.mask(text) : text;
    }

    /**
     * JSON 串脱敏：委托 {@link PiiJsonMasker} 做<b>结构化</b>处理（只替换字符串值，null 与数字原样）。
     * <p>
     * 本项目的 JSON 列都<b>不能</b>整串替换 —— 前端 {@code JSON.parse} 消费的那些（{@code plan_json}）
     * 一旦在数字值位置被替换就产出非法 JSON，面板直接解析失败；{@code clarify_state} 那类则要保持
     * 可解析、可比对。实现与理由见 {@link PiiJsonMasker}，不在这儿重复一份。
     */
    private String maskJson(String json) {
        if (!piiProperties.enabledOn() || json == null || json.isBlank()) {
            return json;
        }
        return PiiJsonMasker.mask(json);
    }

    /**
     * 异步落库一轮追踪（调用方在回复产出<b>之后</b>调用，本方法立即返回）。会先调
     * {@link RoundTrace#finish()} 收口总耗时——必须在此刻定格，不能等异步线程里再算，否则耗时混进排队等待时间。
     * <p>
     * <b>返回的是「这一行真的写进库了」的凭证</b>，不是可选的观察量：自评等「回写追踪行」的旁路任务必须先等它
     * 完成，否则 UPDATE 会打到一行还不存在的记录上（表现是自评随机丢失，且日志只留一句「未命中」）。
     * 追踪被丢弃时该 future 也会正常完成 —— 那种情况下自评写不进去是预期结果，无需重试。
     *
     * @param trace 本轮追踪上下文；null 直接忽略（如异常早退路径）
     * @return 落库完成的凭证（提交失败时返回一个已完成的 future，调用方无需区分）
     */
    public CompletableFuture<Void> saveAsync(RoundTrace trace) {
        if (trace == null) return CompletableFuture.completedFuture(null);
        trace.finish();
        try {
            // 用 runAsync 而不是 execute：需要一个「完成信号」，供自评等回写方串在后面。
            // 注意 persist 内部已吞掉所有异常，故这个 future 不会以异常完成。
            return CompletableFuture.runAsync(() -> persist(trace), traceExecutor);
        } catch (Exception e) {
            // 队列满 / 线程池已关：追踪数据可丢，绝不影响对话
            log.debug("追踪任务提交失败（忽略本轮追踪）：traceId={}，原因={}", trace.getTraceId(), e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    /** 真正落库（在 traceExecutor 线程上执行，任何异常都只记日志）。 */
    private void persist(RoundTrace trace) {
        try {
            AgentTrace t = new AgentTrace();
            t.setTraceId(trace.getTraceId());
            t.setConversationId(trace.getConversationId());
            t.setMode(trace.getMode());
            t.setRouteSource(trace.getRouteSource());
            t.setAgentCode(trace.getAgentCode());
            t.setUserMessage(mask(trace.getUserMessage()));
            t.setRetrievalQuery(mask(trace.getRetrievalQuery()));
            t.setPlanJson(maskJson(trace.getPlanJson()));
            t.setMemoryJson(memoryJson(trace));
            t.setToolCalls(toolCallsJson(trace));
            t.setKbHitCount(trace.citationCount());
            // 引用片段来自知识库文档（企业自有资料、另行管理），不在「用户随口说的个人信息」范围内，故不脱敏
            t.setCitationsJson(KbCitation.toJson(trace.getCitations()));
            t.setPromptTokens(trace.getPromptTokens());
            t.setCompletionTokens(trace.getCompletionTokens());
            t.setTotalTokens(trace.getTotalTokens());
            t.setElapsedMs(trace.getElapsedMs());
            t.setStatus(trace.getStatus());
            t.setErrorMessage(mask(trace.getErrorMessage()));
            t.setCreatedAt(trace.getStartedAt() == null ? LocalDateTime.now() : trace.getStartedAt());
            mapper.insert(t);
            log.debug("追踪落库：traceId={}，会话={}，耗时={}ms，token={}，工具={}，RAG={}",
                    t.getTraceId(), t.getConversationId(), t.getElapsedMs(), t.getTotalTokens(),
                    trace.toolCallCount(), trace.citationCount());
        } catch (Exception e) {
            log.warn("追踪落库失败（忽略）：traceId={}，原因={}", trace.getTraceId(), e.getMessage());
        }
    }

    /**
     * 本轮注入的记忆构成 → JSON；<b>未采集返回 null</b>，让列保持 NULL —— 「没采到」与「注入为空」
     * 对排查是两件事（前者是规划模式/采集失败，后者是首轮对话），不能都写成一个空对象。
     * 窗口逐条只留预览，长期记忆两段只留长度：追踪表是旁路留痕，不是第二份对话历史。
     */
    private String memoryJson(RoundTrace trace) {
        RoundTrace.MemoryInjection mi = trace.getMemoryInjection();
        if (mi == null) return null;
        JSONArray items = new JSONArray(mi.window().size());
        for (InjectedMessage it : mi.window()) {
            JSONObject o = new JSONObject();
            o.set("role", it.role());
            // 预览取自历史正文。本轮之前的历史来自 DB（已脱敏），本轮注入的首轮内容可能含原文 ——
            // 脱敏是幂等的（138****8000 不会被二次识别），故无条件再过一遍是安全的
            o.set("preview", mask(it.preview()));
            o.set("chars", it.chars());
            items.add(o);
        }
        JSONObject o = new JSONObject();
        o.set("windowChars", mi.windowChars());
        o.set("summaryChars", mi.summaryChars());
        o.set("factsChars", mi.factsChars());
        o.set("window", items);
        return o.toString();
    }

    /** 工具调用明细 → JSON 数组字符串；无调用返回 null（让列保持 NULL，便于「有没有调工具」直接判空）。 */
    private String toolCallsJson(RoundTrace trace) {
        List<RoundTrace.ToolCall> calls = trace.getToolCalls();
        if (calls.isEmpty()) return null;
        JSONArray arr = new JSONArray(calls.size());
        for (RoundTrace.ToolCall c : calls) {
            JSONObject o = new JSONObject();
            o.set("name", c.name());
            // 逐字段脱敏而非对最终 JSON 串整体替换：args/result 是自由文本（可能是数据库查询明细，
            // 真的会带出学生/员工手机号），而最终串里还混着非字符串值，整体替换有破坏 JSON 的风险
            o.set("args", mask(c.args()));
            o.set("result", mask(c.result()));
            arr.add(o);
        }
        return arr.toString();
    }

    /**
     * 查询追踪记录，按时间倒序。
     *
     * @param conversationId 会话过滤；null/空表示「不限会话」
     * @param limit          条数；null/≤0 用默认 50，超过 {@value #MAX_LIMIT} 截断到上限
     * @param userId         调用者用户ID，由调用方在 HTTP 线程从登录态取出后传下来
     * @param allUsers       {@code true}=管理员全量视角（不限归属）；{@code false}=只回 {@code userId} 名下会话的记录
     */
    public List<AgentTrace> list(String conversationId, Integer limit, Long userId, boolean allUsers) {
        int size = clamp(limit);
        if (allUsers) {
            QueryWrapper<AgentTrace> qw = new QueryWrapper<>();
            if (conversationId != null && !conversationId.isBlank()) {
                qw.eq("conversation_id", conversationId);
            }
            qw.orderByDesc("id").last("LIMIT " + size);   // 受控 int 参数，无注入风险
            return mapper.selectList(qw);
        }
        // 非全量视角一律带归属条件。注意 userId 为 null 时 SQL 的 `c.user_id = NULL` 恒不成立，
        // 结果自然为空 —— 即「无法证明归属就不可见」，不需要额外防御分支。
        return mapper.selectOwned(conversationId, userId, size);
    }

    /**
     * 按 traceId 查单条。
     *
     * @param userId   调用者用户ID
     * @param allUsers {@code true}=管理员全量视角；{@code false}=仅限 {@code userId} 名下会话的记录
     * @return 不存在<b>或不属于该用户</b>时返回 null —— 两者都由调用方统一转 404，
     *         不区分「没有这条」与「不是你的」，避免用 traceId 探测他人记录是否存在
     */
    public AgentTrace get(String traceId, Long userId, boolean allUsers) {
        if (traceId == null || traceId.isBlank()) return null;
        if (allUsers) {
            return mapper.selectOne(new QueryWrapper<AgentTrace>().eq("trace_id", traceId).last("LIMIT 1"));
        }
        return mapper.selectOwnedByTraceId(traceId, userId);
    }

    /** limit 兜底与上限截断。 */
    private static int clamp(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * 按「会话 + 用户输入原文」定位<b>最近一条</b>追踪记录。
     * <p>
     * 这是「从一条消息倒推它是哪一轮跑出来的」的<b>唯一匹配口径</b>，两个调用方共用：
     * 反馈转回归用例（要读那一轮的路由/计划结论）与点踩强制自评。
     * <p>
     * <b>匹配是近似的，必须当成可能失败来看</b>：{@code agent_trace.user_message} 落库时截断到 1000 字，
     * 而反馈里的 {@code user_input} 是消息原文 —— 超长输入会导致两者不相等。故调用方一致按
     * <br>
     * <b>脱敏不会额外破坏这个匹配</b>：{@code user_input} 取自 {@code chat_message}（已脱敏），
     * {@code user_message} 在本类落库时脱敏，两边是<b>同一份原文、同一个 PiiMasker、同一个开关</b>，
     * 结果必然相等（脱敏是确定性的）。这也是开关必须做成单一总开关的原因 —— 见 {@link PiiProperties}。
     * 「取不到就不做」处理（转用例报 400、自评记 WARN 后放过），而不是在这里做模糊匹配：
     * 模糊匹配可能把另一轮的事实挂到这条反馈上，那比匹配不上糟得多。
     *
     * @return 匹配不到返回 {@code null}
     */
    public AgentTrace findLatestByUserMessage(String conversationId, String userMessage) {
        if (conversationId == null || conversationId.isBlank() || userMessage == null || userMessage.isBlank()) {
            return null;
        }
        return mapper.selectOne(new QueryWrapper<AgentTrace>()
                .eq("conversation_id", conversationId)
                .eq("user_message", userMessage)
                .orderByDesc("id")
                .last("LIMIT 1"));
    }

    /**
     * 把自评结果<b>补写</b>到已落库的那一轮追踪上。
     * <p>
     * <b>为什么是 UPDATE 而不是塞进 INSERT</b>：自评是一次秒级模型往返，而 {@link #saveAsync} 的整条设计
     * 前提是「追踪尽快可见」。让 INSERT 等自评，等于把每一轮的追踪记录都推迟几秒才出现在页面上；
     * 自评本身又是可以缺席的旁路数据（采样未命中、调用失败都不会有结果），不该拖住主记录。
     * <p>
     * 命中 0 行只记 WARN，<b>不抛错</b>：可能这一轮因队列满被丢弃过、或记录已被清理。自评是纯旁路，
     * 写不进去不该影响任何东西 —— 但必须出声，否则表现是「自评功能时灵时不灵」而日志里什么都没有。
     *
     * @param score 1~5 的自评分
     * @param json  自评明细 JSON（与 score 同生共死）
     */
    public void updateSelfEval(String traceId, int score, String json) {
        if (traceId == null || traceId.isBlank()) return;
        try {
            int n = mapper.update(null, new LambdaUpdateWrapper<AgentTrace>()
                    .eq(AgentTrace::getTraceId, traceId)
                    .set(AgentTrace::getSelfEvalScore, score)
                    .set(AgentTrace::getSelfEvalJson, json));
            if (n == 0) {
                log.warn("自评写入未命中任何追踪记录（该轮追踪可能被丢弃或已清理）：traceId={}", traceId);
            } else {
                log.debug("自评写入完成：traceId={}，score={}", traceId, score);
            }
        } catch (Exception e) {
            log.warn("自评写入失败（忽略）：traceId={}，原因={}", traceId, e.getMessage());
        }
    }
}
