package org.luo.ai.service;

import org.luo.ai.dto.ObservabilitySummary;
import org.luo.ai.dto.ObservabilitySummary.Overview;
import org.luo.ai.dto.ObservabilitySummary.SelfEvalSummary;
import org.luo.ai.mapper.ObservabilityMapper;
import org.luo.ai.properties.SelfEvalProperties;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 可观测面板查询服务（只读，聚合 {@code agent_trace}）。回答「最近 N 天整体运行得怎么样」——
 * 成功率、平均耗时、按天/模式/来源/智能体拆解、最慢的几轮，以及回答自评的覆盖与低分轮次。
 * <p>
 * <b>仅 ADMIN 视角</b>：聚合口径跨会话、全站，天然运营数据，与成本看板同一收敛逻辑（见
 * {@code CostController} 注释）。身份/角色校验在 Controller 层（{@code @RequireRole(ADMIN)}），
 * 本服务不感知身份。
 */
@Service
public class ObservabilityService {

    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 90;
    private static final int SLOWEST_LIMIT = 10;

    /** 低分轮次列表条数上限：它是「看点」而不是「台账」，给多了反而没人看。 */
    private static final int LOW_SCORE_LIMIT = 20;

    private final ObservabilityMapper mapper;
    /** 自评配置：低分阈值只从这里取（与采样自评同一份口径，见其类注释）。 */
    private final SelfEvalProperties selfEvalProperties;

    public ObservabilityService(ObservabilityMapper mapper, SelfEvalProperties selfEvalProperties) {
        this.mapper = mapper;
        this.selfEvalProperties = selfEvalProperties;
    }

    /**
     * 聚合可观测指标。
     *
     * @param days 窗口天数；null/≤0 用默认 7，超过 {@value #MAX_DAYS} 截断
     */
    public ObservabilitySummary summary(Integer days) {
        int d = clamp(days);
        LocalDateTime since = LocalDateTime.now().minusDays(d);
        // successRate 不在这里算、也不在 SQL 里算 —— 它是 Overview 上的派生方法（见该 record 注释）。
        // 服务层只管兜底：COUNT(*) 聚合恒有且只有一行，非空表下 ov 不会为 null，这里仅防御性处理。
        Overview ov = mapper.aggregateOverview(since);
        Overview overview = ov == null ? new Overview(0, 0, 0, 0, 0) : ov;
        // 低分阈值来自 agent.self-eval，面板与自评服务共用同一个数（改一处两边同时生效）
        int threshold = selfEvalProperties.lowScoreThreshold();
        SelfEvalSummary se = mapper.aggregateSelfEval(since, threshold);
        return new ObservabilitySummary(
                overview,
                mapper.aggregateDaily(since),
                mapper.aggregateByMode(since),
                mapper.aggregateByRouteSource(since),
                mapper.aggregateByAgent(since),
                mapper.selectSlowest(since, SLOWEST_LIMIT),
                se == null ? new SelfEvalSummary(0, 0, 0, 0) : se,
                threshold,
                mapper.selectLowScore(since, threshold, LOW_SCORE_LIMIT));
    }

    /** days 兜底与上限截断。 */
    private static int clamp(Integer days) {
        if (days == null || days <= 0) return DEFAULT_DAYS;
        return Math.min(days, MAX_DAYS);
    }
}
