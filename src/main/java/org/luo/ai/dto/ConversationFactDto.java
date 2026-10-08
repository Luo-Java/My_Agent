package org.luo.ai.dto;

import org.luo.ai.entity.ConversationFact;

import java.time.LocalDateTime;

/**
 * 会话长期事实条目（页面视图）。
 * <p>
 * <b>为什么直接暴露 {@code source}</b>：面板上「自动整理」与「手动添加」两种条目必须看得出区别 ——
 * 自动整理的条目会被下一次合并按 diff 淘汰（模型这次不输出它就没了），手动添加的绝不会被合并动到。
 * 用户删除某条时是否要提醒「下次合并可能重新出现」，完全取决于这一个字段。
 *
 * @param id        条目 ID（编辑 / 删除的定位符）
 * @param topic     主题标签（身份 / 偏好 / 待办 / 背景 / 其它）
 * @param fact      事实内容（单条）
 * @param source    MERGE=自动整理（可被合并淘汰）/ USER=手动添加（合并不动）
 * @param createdAt 首次写入时间
 * @param updatedAt 最后被确认 / 改写时间（合并里仍然有效即刷新）
 */
public record ConversationFactDto(Long id, String topic, String fact, String source,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {

    /** 实体 → 视图（条目为空时返回 null，调用方自行过滤）。 */
    public static ConversationFactDto of(ConversationFact f) {
        if (f == null) return null;
        return new ConversationFactDto(f.getId(), f.getTopic(), f.getFact(), f.getSource(),
                f.getCreatedAt(), f.getUpdatedAt());
    }
}
