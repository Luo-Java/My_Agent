package org.luo.ai.dto;

/**
 * 「把一条反馈转成回归用例」的请求体。
 *
 * @param feedbackId 反馈 ID（来自某会话的反馈列表）；不存在 404，已转过则 400（不重复生成）
 */
public record PromoteFeedbackRequest(Long feedbackId) {
}
