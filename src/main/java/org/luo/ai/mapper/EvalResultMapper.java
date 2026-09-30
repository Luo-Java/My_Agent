package org.luo.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.luo.ai.dto.EvalBatchSummary;
import org.luo.ai.entity.EvalResult;

import java.util.List;

/**
 * 提示词回归评测结果 Mapper。
 * <p>
 * 批次明细（按 batch_id 取）与单条写入走 {@link BaseMapper}；只有「跨批次聚合」与「保留窗口清理」
 * 需要显式 SQL，落在 {@code mapper/ai/EvalResultMapper.xml}（与 {@code AgentTraceMapper} 同约定：
 * 显式 SQL 一律进 XML，不用注解）。
 */
public interface EvalResultMapper extends BaseMapper<EvalResult> {

    /**
     * 最近若干批次的摘要（按批次时间倒序）。
     * 批次时间取同批次各行的最小 created_at —— 不用 batch_id 排序：它是随机 UUID，无时序含义。
     */
    List<EvalBatchSummary> selectBatchSummaries(int limit);

    /** 只保留最近 {@code keep} 个批次，其余整批删除（防表无限增长；评测价值在「和上一次比」，不需长期归档）。 */
    int deleteBatchesBeyond(int keep);
}
