package org.luo.ai.dto;

/**
 * 一条消息反馈（回显给前端）。
 * <p>
 * {@code userInput} 一起返回：评测弹窗里的「待转用例」列表要展示「用户当时问的是什么」，否则一列反馈
 * 全是「答非所问」，看不出该怎么变成断言。
 *
 * @param id         反馈 ID（转用例时用它定位）
 * @param messageId  被评价的消息 ID
 * @param rating     UP / DOWN
 * @param reason     问题分类（可空）
 * @param comment    补充说明（可空）
 * @param userInput  那一轮的用户输入快照（可空：该消息是本会话第一条时取不到）
 * @param evalCaseId 已转成的库内用例 ID；null=尚未转为用例（前端据此决定是否还显示「转用例」按钮）
 */
public record MessageFeedbackDto(Long id, Long messageId, String rating, String reason, String comment,
                                 String userInput, Long evalCaseId) {

    /** 由实体转换。 */
    public static MessageFeedbackDto of(org.luo.ai.entity.MessageFeedback f) {
        return new MessageFeedbackDto(f.getId(), f.getMessageId(), f.getRating(), f.getReason(), f.getComment(),
                f.getUserInput(), f.getEvalCaseId());
    }
}
