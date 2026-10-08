package org.luo.ai.dto;

/**
 * 提交消息反馈的请求体。
 *
 * @param rating  评价：{@code UP}（有用）/ {@code DOWN}（有问题）；缺省或非法值一律 400
 * @param reason  问题分类（仅 {@code DOWN} 时有意义）：{@code ANSWERS_OFF} / {@code FABRICATED} /
 *                {@code ROUTING} / {@code OTHER}；空表示不分类
 * @param comment 补充说明（可空，≤500 字）；<b>不参与任何断言</b>，只作人工排查线索
 */
public record FeedbackRequest(String rating, String reason, String comment) {
}
