package org.luo.ai.dto;

import org.luo.ai.entity.ConversationFact;

import java.time.LocalDateTime;

/**
 * 会话长期事实条目（页面视图）。
 * <p>
 * <b>为什么直接暴露 {@code source}</b>：面板上「自动整理」与「手动添加」两种条目必须看得出区别 ——
 * 自动整理的条目会被下一次合并按 diff 淘汰（模型这次不输出它就没了），手动添加的绝不会被合并动到。
 * 用户删除某条时是否要提醒「下次合并可能重新出现」，完全取决于这一个字段。
 * <p>
 * <b>为什么把置信度 / 有效期 / 状态也暴露出来</b>：这三项决定「这条现在还起不起作用」。面板若只显示文本，
 * 用户看到一串事实却不知道哪条已过期、哪条已被新说法替代，等于还是「一段平铺文本」。
 *
 * @param id           条目 ID（编辑 / 删除的定位符）
 * @param topic        主题标签（身份 / 偏好 / 待办 / 背景 / 其它）
 * @param fact         事实内容（单条）
 * @param source       MERGE=自动整理（可被合并淘汰）/ USER=手动添加（合并不动）
 * @param confidence   置信度 1~5（用户手写起始 5，模型整理起始 3，每次被重新确认 +1）
 * @param expiresAt    有效期（null=永不过期）；已过期的条目不再注入
 * @param expired      是否已过期（<b>服务端算好</b>：前端不重复判断时钟，避免时区/时钟差让两边结论不同）
 * @param status       ACTIVE=生效中（会注入）/ SUPERSEDED=已被同主题的新说法替代（不注入，留档）
 * @param supersededBy 被哪条取代（status=SUPERSEDED 时有值）
 * @param createdAt    首次写入时间
 * @param updatedAt    最后被确认 / 改写时间（合并里仍然有效即刷新）
 */
public record ConversationFactDto(Long id, String topic, String fact, String source, Integer confidence,
                                  LocalDateTime expiresAt, boolean expired, String status, Long supersededBy,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {

    /** 实体 → 视图（条目为空时返回 null，调用方自行过滤）。 */
    public static ConversationFactDto of(ConversationFact f) {
        return of(f, LocalDateTime.now());
    }

    /** 实体 → 视图，可注入「当前时刻」：批量渲染共用同一个基准时间，避免同一列表里出现两条不同判断。 */
    public static ConversationFactDto of(ConversationFact f, LocalDateTime now) {
        if (f == null) return null;
        boolean expired = f.getExpiresAt() != null && !f.getExpiresAt().isAfter(now);
        return new ConversationFactDto(f.getId(), f.getTopic(), f.getFact(), f.getSource(),
                f.getConfidence(), f.getExpiresAt(), expired, f.getStatus(), f.getSupersededBy(),
                f.getCreatedAt(), f.getUpdatedAt());
    }
}
