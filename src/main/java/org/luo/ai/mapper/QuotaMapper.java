package org.luo.ai.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 成本配额统计 mapper：按「用户 + 时间窗」汇总全量 token 消耗。
 * <p>
 * 口径与成本看板一致（{@code agent_trace} 的回答成本 + {@code llm_usage} 的裸调用成本），只是换了分组维度：
 * 看板按「天 / 用途」聚合，配额按「用户」聚合。两表都没有 {@code user_id}，归属一律靠
 * {@code JOIN conversation} 判定（与 {@code AgentTraceMapper.selectOwned} 同一理由与同一口径）。
 * <p>
 * 因此<b>无会话的调用（如生成智能体人设）不计入任何用户的配额</b>——它们本就无法归属到人，硬凑一个归属
 * 只会让配额数字变得不可解释。会话已删而留下的孤儿成本记录同理查不到。
 */
@Mapper
public interface QuotaMapper {

    /**
     * 汇总某用户在给定时间点之后的全量 token 消耗（回答成本 + 裸调用成本）。
     * <p>
     * 两路都<b>必须</b> {@code COALESCE(..., 0)}：零行时 {@code SUM} 返回 NULL，而调用方拿到的是要参与
     * 比较运算的数值——NULL 一路传下去会在减/除时炸成 NPE，或在比较时静默判 false（表现为「配额永远不生效」）。
     *
     * @param userId 用户 ID
     * @param since  统计起点（含）；自然日配额传「今日 00:00」
     * @return token 合计（永不为 null）
     */
    long sumUserTokensSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);
}
