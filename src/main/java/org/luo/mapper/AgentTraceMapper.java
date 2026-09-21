package org.luo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.luo.entity.AgentTrace;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AgentTraceMapper extends BaseMapper<AgentTrace> {

    /**
     * 按天聚合「回答成本」（agent_trace，正式回答 + 工具循环的 token）。返回每天的回答 token 合计与轮次数。
     * 与 {@code LlmUsageMapper.aggregateDailyByPurpose} 互补：后者记裸调用，本方法记回答本身，二者合并为全量成本。
     */
    @Select("SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS day, "
            + "SUM(total_tokens) AS totalTokens, COUNT(*) AS rounds "
            + "FROM agent_trace WHERE created_at >= #{since} "
            + "GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d')")
    List<AnswerAgg> aggregateDailyAnswer(@Param("since") LocalDateTime since);

    /** 聚合中间结果：一天内回答成本的合计（字段名与 SQL 别名一致）。 */
    record AnswerAgg(String day, Long totalTokens, Long rounds) {
    }
}
