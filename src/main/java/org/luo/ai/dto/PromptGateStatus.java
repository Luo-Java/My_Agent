package org.luo.ai.dto;

import org.luo.ai.entity.PromptSnapshot;

import java.util.List;

/**
 * 提示词门禁状态（只读视图，供前端在评测面板顶部渲染一条结论横幅）。
 *
 * @param fingerprint 当前 {@code prompts.yaml} 的内容指纹
 * @param changed     当前指纹是否与最近一次快照不同（true = 改了提示词，尚无对应结论）
 * @param verified    true = 最近快照的指纹与当前一致（这一版提示词已经过验证）
 * @param latest      最近一次快照（无任何记录时为 null）
 * @param recent      最近若干次快照（时间倒序），供展开看历史
 */
public record PromptGateStatus(String fingerprint, boolean changed, boolean verified,
                               PromptSnapshot latest, List<PromptSnapshot> recent) {
}
