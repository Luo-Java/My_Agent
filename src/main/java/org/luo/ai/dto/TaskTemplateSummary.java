package org.luo.ai.dto;

import java.time.LocalDateTime;

/**
 * 规划模板列表项（前端模板弹窗渲染用）。
 * <p>
 * {@code stepCount} 从 {@code steps_json} 现算，不在表里冗余一列 —— 模板是只读快照，套用时不需要它，
 * 只有列表展示要；多一列就多一处「写入时忘了同步」的可能。
 *
 * @param id          模板 ID
 * @param name        模板名称
 * @param description 备注（适用场景 / 套用时该填什么目标）
 * @param stepCount   步骤数
 * @param useCount    被套用次数（帮用户判断哪个模板值得留）
 * @param createdAt   创建时间
 */
public record TaskTemplateSummary(Long id, String name, String description, int stepCount, int useCount,
                                  LocalDateTime createdAt) {
}
