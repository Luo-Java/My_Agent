package org.luo.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 消息反馈实体（对应 {@code message_feedback} 表）：用户对某条助手回复的显式评价。
 * <p>
 * 它是「线上翻车 → 回归资产」这条链路的起点：点踩本身只是留档，有价值的是随后把它转成一条可重复跑的
 * 断言（见 {@code EvalCaseService#promote}）。
 * <p>
 * <b>一人对一条消息一票</b>（唯一键 {@code message_id + user_id}）：改主意是<b>改票</b>而不是追加历史 ——
 * 「我一开始觉得不好、后来觉得还行」不该在库里留下两条互相矛盾的记录。
 */
@Data
@NoArgsConstructor
@TableName("message_feedback")
public class MessageFeedback {

    /** 评价：有用。 */
    public static final String RATING_UP = "UP";

    /** 评价：有问题。 */
    public static final String RATING_DOWN = "DOWN";

    /** 问题分类：答非所问。 */
    public static final String REASON_ANSWERS_OFF = "ANSWERS_OFF";

    /** 问题分类：编造（与资料/事实不符）。 */
    public static final String REASON_FABRICATED = "FABRICATED";

    /** 问题分类：路由或规划不对（走错了智能体、计划步骤本身不合理）。 */
    public static final String REASON_ROUTING = "ROUTING";

    /** 问题分类：其他。 */
    public static final String REASON_OTHER = "OTHER";

    /** 四个问题分类的合法取值（校验用；与 DDL 注释、前端下拉保持同一份口径）。 */
    public static final String[] REASONS = {REASON_ANSWERS_OFF, REASON_FABRICATED, REASON_ROUTING, REASON_OTHER};

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 被评价的消息 ID，关联 {@code chat_message.id}（恒为助手回复，见 FeedbackService 的校验）。 */
    private Long messageId;

    /** 所属会话 ID，关联 {@code conversation.id}。 */
    private String conversationId;

    /** 评价人用户 ID，关联 {@code sys_user.id}。 */
    private Long userId;

    /** 评价：UP / DOWN。 */
    private String rating;

    /** 问题分类（仅 DOWN 时有意义）：ANSWERS_OFF / FABRICATED / ROUTING / OTHER。 */
    private String reason;

    /** 补充说明（用户填的原文，供人工排查；不参与任何断言）。 */
    private String comment;

    /**
     * 那一轮的用户输入快照。
     * <p>
     * <b>存快照而不是联查</b>：反馈是「对那一轮的评价」，会话被删、消息被改、用户把那条标成「不参与记忆」，
     * 都不该让它失效（与 {@code agent_trace} 存 {@code user_message} 是同一取舍）。转回归用例时直接拿它当
     * 用例的 {@code input}。
     */
    private String userInput;

    /** 转成的库内回归用例 ID（关联 {@code eval_case.id}）；null=尚未转为用例。也用于防重复转（见 promote）。 */
    private Long evalCaseId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
