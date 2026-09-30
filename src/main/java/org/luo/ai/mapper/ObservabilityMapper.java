package org.luo.ai.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.luo.ai.dto.ObservabilitySummary.AgentBucket;
import org.luo.ai.dto.ObservabilitySummary.DailyBucket;
import org.luo.ai.dto.ObservabilitySummary.ModeBucket;
import org.luo.ai.dto.ObservabilitySummary.Overview;
import org.luo.ai.dto.ObservabilitySummary.RouteBucket;
import org.luo.ai.dto.ObservabilitySummary.SlowRound;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 可观测面板的聚合查询（只读，聚合 {@code agent_trace}）。
 * <p>
 * 与 {@link AgentTraceMapper} 的分工：后者做「逐条追踪」的归属过滤查询（TraceController 用）；
 * 本接口只做「跨会话聚合统计」（ObservabilityController 用，仅 ADMIN）。SQL 统一落 XML，
 * 各查询行一律类型化投影到 {@code ObservabilitySummary} 的嵌套 record。
 * <p>
 * ⚠ <b>record 投影的硬约束</b>：MyBatis 对无默认构造的类型走构造器自动映射，本项目按<b>列序</b>映射，
 * 因此每个 record 的<b>分量个数必须等于对应 SELECT 的列数、顺序也要一致</b> —— 多一个分量会在取数时抛
 * {@code ExecutorException}（编译期无感）。派生指标（如 {@code Overview.successRate}）一律做成 record 上的
 * 方法，不靠 SQL 凑列。改这里的 SQL 后跑 {@code python .workbuddy/tools/check_record_projection_columns.py} 自查。
 */
@Mapper
public interface ObservabilityMapper {

    /** 总览：窗口内轮次 / 失败数 / 平均耗时 / token。只回原始计数，successRate 由 {@code Overview} 派生。 */
    Overview aggregateOverview(@Param("since") LocalDateTime since);

    /** 按天趋势（升序）。 */
    List<DailyBucket> aggregateDaily(@Param("since") LocalDateTime since);

    /** 按形态拆解（agent / planner）。 */
    List<ModeBucket> aggregateByMode(@Param("since") LocalDateTime since);

    /** 按处理方来源拆解（BOUND / ROUTE / NONE / PLAN）。 */
    List<RouteBucket> aggregateByRouteSource(@Param("since") LocalDateTime since);

    /** 按智能体拆解（agent_code 降序轮次）。 */
    List<AgentBucket> aggregateByAgent(@Param("since") LocalDateTime since);

    /** 窗口内最慢的 N 轮（耗时降序），用于定位瓶颈。 */
    List<SlowRound> selectSlowest(@Param("since") LocalDateTime since, @Param("limit") int limit);
}
