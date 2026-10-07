package org.luo.ai.dto;

/**
 * 切换某一轮生效版本的请求体（消息上「1/2 ‹ ›」点箭头）。
 * <p>
 * 两个字段都取自历史接口返回的 {@code MessageDto.turn}，前端不需要自己记状态。
 *
 * @param groupId 分支组 ID
 * @param version 目标版本号（从 1 开始）；该组内不存在此版本时后端返回 404
 */
public record SwitchTurnRequest(String groupId, int version) {
}
