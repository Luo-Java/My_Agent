package org.luo.trace;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.dto.KbCitation;
import org.luo.entity.AgentTrace;
import org.luo.mapper.AgentTraceMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 链路追踪服务：把 {@link RoundTrace} 收集到的一轮事实落库，并提供查询。
 * <p>
 * <b>三条不可破的原则</b>：
 * <ol>
 *   <li><b>异步</b>：落库走 {@code traceExecutor}，在回复产出之后执行，绝不挡在用户看到答案之前；</li>
 *   <li><b>旁路</b>：不参与任何对话逻辑，业务代码也从不读取 agent_trace——删掉整张表对话照常运行；</li>
 *   <li><b>不抛错</b>：从提交任务到 INSERT 全链路 try/catch，失败只记日志（队列满时宁可丢追踪也不影响对话）。</li>
 * </ol>
 */
@Slf4j
@Service
public class TraceService {

    /** 单次查询上限：避免调用方传个巨大 limit 把整表拉出来。 */
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 50;

    private final AgentTraceMapper mapper;
    private final Executor traceExecutor;

    public TraceService(AgentTraceMapper mapper, @Qualifier("traceExecutor") Executor traceExecutor) {
        this.mapper = mapper;
        this.traceExecutor = traceExecutor;
    }

    /**
     * 异步落库一轮追踪。调用方在回复产出<b>之后</b>调用即可，本方法立即返回。
     * <p>
     * 会先把 {@link RoundTrace#finish()} 收口（记录总耗时）——必须在此刻定格，不能等异步线程里再算，
     * 否则耗时里会混进排队等待时间。
     *
     * @param trace 本轮追踪上下文；null 直接忽略（如异常早退路径）
     */
    public void saveAsync(RoundTrace trace) {
        if (trace == null) return;
        trace.finish();
        try {
            traceExecutor.execute(() -> persist(trace));
        } catch (Exception e) {
            // 队列满 / 线程池已关：追踪数据可丢，绝不影响对话
            log.debug("追踪任务提交失败（忽略本轮追踪）：traceId={}，原因={}", trace.getTraceId(), e.getMessage());
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
            t.setUserMessage(trace.getUserMessage());
            t.setRetrievalQuery(trace.getRetrievalQuery());
            t.setPlanJson(trace.getPlanJson());
            t.setToolCalls(toolCallsJson(trace));
            t.setKbHitCount(trace.citationCount());
            t.setCitationsJson(KbCitation.toJson(trace.getCitations()));
            t.setPromptTokens(trace.getPromptTokens());
            t.setCompletionTokens(trace.getCompletionTokens());
            t.setTotalTokens(trace.getTotalTokens());
            t.setElapsedMs(trace.getElapsedMs());
            t.setStatus(trace.getStatus());
            t.setErrorMessage(trace.getErrorMessage());
            t.setCreatedAt(trace.getStartedAt() == null ? LocalDateTime.now() : trace.getStartedAt());
            mapper.insert(t);
            log.debug("追踪落库：traceId={}，会话={}，耗时={}ms，token={}，工具={}，RAG={}",
                    t.getTraceId(), t.getConversationId(), t.getElapsedMs(), t.getTotalTokens(),
                    trace.toolCallCount(), trace.citationCount());
        } catch (Exception e) {
            log.warn("追踪落库失败（忽略）：traceId={}，原因={}", trace.getTraceId(), e.getMessage());
        }
    }

    /** 工具调用明细 → JSON 数组字符串；无调用返回 null（让列保持 NULL，便于「有没有调工具」直接判空）。 */
    private static String toolCallsJson(RoundTrace trace) {
        List<RoundTrace.ToolCall> calls = trace.getToolCalls();
        if (calls.isEmpty()) return null;
        JSONArray arr = new JSONArray(calls.size());
        for (RoundTrace.ToolCall c : calls) {
            JSONObject o = new JSONObject();
            o.set("name", c.name());
            o.set("args", c.args());
            o.set("result", c.result());
            arr.add(o);
        }
        return arr.toString();
    }

    /**
     * 查询追踪记录，按时间倒序。
     *
     * @param conversationId 会话 ID；为空则查全部会话（调试用）
     * @param limit          条数；null/≤0 用默认 50，超过 {@value #MAX_LIMIT} 截断到上限
     */
    public List<AgentTrace> list(String conversationId, Integer limit) {
        QueryWrapper<AgentTrace> qw = new QueryWrapper<>();
        if (conversationId != null && !conversationId.isBlank()) {
            qw.eq("conversation_id", conversationId);
        }
        qw.orderByDesc("id").last("LIMIT " + clamp(limit));   // 受控 int 参数，无注入风险
        return mapper.selectList(qw);
    }

    /** 按 traceId 查单条；不存在返回 null。 */
    public AgentTrace get(String traceId) {
        if (traceId == null || traceId.isBlank()) return null;
        return mapper.selectOne(new QueryWrapper<AgentTrace>().eq("trace_id", traceId).last("LIMIT 1"));
    }

    /** limit 兜底与上限截断。 */
    private static int clamp(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }
}
