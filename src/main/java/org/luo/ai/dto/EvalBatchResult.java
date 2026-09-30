package org.luo.ai.dto;

import java.util.List;

/**
 * 一次跑批的结果。
 *
 * @param batchId      批次 ID（后续按它查明细、做批次间对比）
 * @param total        用例总数
 * @param passed       通过数
 * @param failed       失败数（不含配置错误）
 * @param configErrors 用例配置错误数（与 failed 分开统计）
 * @param costMs       整批耗时（毫秒）
 * @param results      逐条结果
 */
public record EvalBatchResult(String batchId, int total, int passed, int failed, int configErrors,
                              long costMs, List<EvalCaseResult> results) {
}
