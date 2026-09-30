package org.luo.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.luo.ai.entity.AgentTrace;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AgentTraceMapper extends BaseMapper<AgentTrace> {

    /**
     * 按天聚合「回答成本」（agent_trace，正式回答 + 工具循环的 token）。返回每天的回答 token 合计与轮次数。
     * 与 {@code LlmUsageMapper.aggregateDailyByPurpose} 互补：后者记裸调用，本方法记回答本身，二者合并为全量成本。
     */
    List<AnswerAgg> aggregateDailyAnswer(@Param("since") LocalDateTime since);

    /**
     * 按「会话归属」过滤查询追踪，时间倒序。归属由 {@code JOIN conversation} 判定
     * （agent_trace 无 user_id 列，理由见 XML 内注释），因此只能查到会话属于 {@code userId} 的记录；
     * 会话已被删除而留下的孤儿记录<b>查不到</b> —— 这是有意的：无法证明归属即视为不可见。
     *
     * @param conversationId 会话过滤；null/空表示「不限会话」（仍是本用户名下的全部）
     * @param userId         归属用户ID
     * @param limit          条数上限（调用方已 clamp，受控 int 无注入风险）
     */
    List<AgentTrace> selectOwned(@Param("conversationId") String conversationId,
                                 @Param("userId") Long userId,
                                 @Param("limit") int limit);

    /** 按 traceId + 归属查单条；不存在<b>或不属于该用户</b>时返回 null（调用方统一转 404）。 */
    AgentTrace selectOwnedByTraceId(@Param("traceId") String traceId, @Param("userId") Long userId);

    /** 聚合中间结果：一天内回答成本的合计（字段名与 SQL 别名一致）。 */
    record AnswerAgg(String day, Long totalTokens, Long rounds) {
    }
}
