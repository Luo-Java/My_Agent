package org.luo.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.luo.ai.dto.CostSummary;
import org.luo.ai.mapper.AgentTraceMapper;
import org.luo.ai.mapper.LlmUsageMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成本看板聚合服务：把「回答成本」（agent_trace）与「裸调用成本」（llm_usage）合并成全量成本视图。
 * <p>
 * 只读、纯聚合，不参与对话逻辑；数据源是两张旁路表（删掉不影响对话），故本服务也天然可降级——
 * 聚合查询异常时返回空视图而非抛错（成本看板是观测，不是业务）。
 */
@Slf4j
@Service
public class CostService {

    /** 查询默认跨度（天）；调用方未指定或越界时兜底。 */
    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 90;

    private final AgentTraceMapper agentTraceMapper;
    private final LlmUsageMapper llmUsageMapper;

    public CostService(AgentTraceMapper agentTraceMapper, LlmUsageMapper llmUsageMapper) {
        this.agentTraceMapper = agentTraceMapper;
        this.llmUsageMapper = llmUsageMapper;
    }

    /** 用途编码 → 中文标签（与 llm_usage.purpose 取值一致）。 */
    private static final Map<String, String> PURPOSE_LABELS = Map.of(
            "ROUTE", "智能路由",
            "CLARIFY", "参数抽取",
            "REWRITE", "查询改写",
            "PLAN", "任务规划",
            "VISION", "视觉识别",
            "MEMORY_MERGE", "记忆合并");

    /**
     * 聚合近 {@code days} 天的全量成本。任何一步聚合异常都降级为空视图（成本看板是观测，不阻断）。
     *
     * @param days 天数（≤0 或 null 取默认 30，超过 {@value #MAX_DAYS} 截断）
     */
    public CostSummary summary(Integer days) {
        int span = (days == null || days <= 0) ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        LocalDateTime since = LocalDate.now().minusDays(span - 1).atStartOfDay();
        try {
            List<AgentTraceMapper.AnswerAgg> answers = agentTraceMapper.aggregateDailyAnswer(since);
            List<LlmUsageMapper.UsageAgg> aux = llmUsageMapper.aggregateDailyByPurpose(since);
            return merge(answers, aux);
        } catch (Exception e) {
            log.warn("成本聚合失败，降级空视图：{}", e.getMessage());
            return new CostSummary(0, 0, 0, 0, List.of(), List.of());
        }
    }

    /** 合并两路聚合：按天对齐 answer/aux，按用途归类裸调用。 */
    private static CostSummary merge(List<AgentTraceMapper.AnswerAgg> answers,
                                     List<LlmUsageMapper.UsageAgg> aux) {
        // 按天合并 answer（每天唯一）
        Map<String, Long> answerByDay = new LinkedHashMap<>();
        long answerTotal = 0, rounds = 0;
        for (AgentTraceMapper.AnswerAgg a : answers) {
            long t = a.totalTokens() == null ? 0 : a.totalTokens();
            long r = a.rounds() == null ? 0 : a.rounds();
            answerByDay.put(a.day(), t);
            answerTotal += t;
            rounds += r;
        }

        // 按天合并 aux，同时按用途累计
        Map<String, Long> auxByDay = new LinkedHashMap<>();
        Map<String, long[]> purposeAcc = new LinkedHashMap<>();   // purpose -> [tokens, calls]
        long auxTotal = 0;
        for (LlmUsageMapper.UsageAgg u : aux) {
            long t = u.totalTokens() == null ? 0 : u.totalTokens();
            long c = u.calls() == null ? 0 : u.calls();
            auxByDay.merge(u.day(), t, Long::sum);
            purposeAcc.computeIfAbsent(u.purpose(), k -> new long[2])[0] += t;
            purposeAcc.computeIfAbsent(u.purpose(), k -> new long[2])[1] += c;
            auxTotal += t;
        }

        // 按天趋势：所有出现的日期并集，升序
        Map<String, Long> allDays = new LinkedHashMap<>(answerByDay);
        auxByDay.forEach((d, t) -> allDays.merge(d, 0L, (x, y) -> x));
        List<CostSummary.DailyCost> daily = new ArrayList<>(allDays.size());
        allDays.keySet().stream().sorted().forEach(d -> daily.add(new CostSummary.DailyCost(
                d, answerByDay.getOrDefault(d, 0L), auxByDay.getOrDefault(d, 0L))));

        // 按用途拆解：降序
        List<CostSummary.PurposeCost> byPurpose = new ArrayList<>(purposeAcc.size());
        purposeAcc.forEach((p, acc) -> byPurpose.add(new CostSummary.PurposeCost(
                p, PURPOSE_LABELS.getOrDefault(p, p), acc[0], acc[1])));
        byPurpose.sort(Comparator.comparingLong(CostSummary.PurposeCost::totalTokens).reversed());

        return new CostSummary(answerTotal, auxTotal, answerTotal + auxTotal, rounds, daily, byPurpose);
    }
}
