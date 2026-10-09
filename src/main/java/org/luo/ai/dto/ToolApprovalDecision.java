package org.luo.ai.dto;

/**
 * 工具审批决断请求体（{@code POST /api/tool-approval/{id}/approve|reject}）。
 *
 * @param note 决断备注（选填）：拒绝时写清原因，将来回看「为什么当初不让它查库」才有答案
 */
public record ToolApprovalDecision(String note) {
}
