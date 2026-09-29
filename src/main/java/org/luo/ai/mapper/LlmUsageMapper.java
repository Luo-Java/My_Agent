package org.luo.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.luo.ai.dto.CostSummary;
import org.luo.ai.entity.LlmUsage;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface LlmUsageMapper extends BaseMapper<LlmUsage> {

    /**
     * 按「天 + 用途」聚合裸调用成本（近 N 天）。返回的每条是一个 (day, purpose) 组合的 token 合计与调用次数；
     * 组装层再按天合并、按用途归类。GROUP BY 字段与 SELECT 保持一致（MySQL ONLY_FULL_GROUP_BY 下合规）。
     */
    List<UsageAgg> aggregateDailyByPurpose(@Param("since") LocalDateTime since);

    /** 聚合中间结果：一天内某用途的成本合计（字段名与 SQL 别名一致）。 */
    record UsageAgg(String day, String purpose, Long totalTokens, Long calls) {
    }
}
