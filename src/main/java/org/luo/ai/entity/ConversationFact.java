package org.luo.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话长期事实条目（对应 {@code conversation_fact} 表）：把此前 {@code conversation.core_facts}
 * 那一段平铺文本拆成<b>逐条可寻址</b>的行。
 * <p>
 * <b>为什么拆</b>：文本字段的问题是它只能整体覆盖 —— 聊久了话题一杂，新旧事实混成一段长文，
 * 要删掉记错的那一条无从下手，要确认「它到底记住了什么」也只能通读。拆成行之后，事实可以逐条查看、
 * 逐条修正、逐条删除，注入时也能明确告诉模型「这是第几条」。
 * <p>
 * <b>两条来源的待遇不同</b>：{@link #SOURCE_MERGE} 由记忆合并按 diff 重写（模型每次输出完整列表，
 * 不在列表里的即视为过时并删除）；{@link #SOURCE_USER} 是用户手加的，合并<b>绝不覆盖也绝不删除</b> ——
 * 用户明确写下的东西不该被一次自动合并悄悄抹掉。
 */
@Data
@NoArgsConstructor
@TableName("conversation_fact")
public class ConversationFact {

    /** 来源：自动合并产出（每次合并按 diff 重写）。 */
    public static final String SOURCE_MERGE = "MERGE";

    /** 来源：用户手动添加（合并不覆盖、不删除）。 */
    public static final String SOURCE_USER = "USER";

    /** 主题标签的合法取值（与提示词、面板下拉同一份口径）；模型写别的值会被归到「其它」。 */
    public static final List<String> TOPICS = List.of("身份", "偏好", "待办", "背景", "其它");

    /** 兜底主题：模型给了白名单外的标签、或用户没填时使用。 */
    public static final String TOPIC_DEFAULT = "其它";

    /** 单条事实的字符上限（与 DDL 列宽一致）。 */
    public static final int FACT_MAX = 500;

    /** 主题标签的字符上限（与 DDL 列宽一致）。 */
    public static final int TOPIC_MAX = 32;

    /** 状态：生效中（会参与注入与合并）。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态：已被同主题的新说法替代（不再注入，但留档可查「它曾经是什么」）。 */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    /** 置信度下限 / 上限（1~5）。 */
    public static final int CONFIDENCE_MIN = 1;
    public static final int CONFIDENCE_MAX = 5;

    /** 用户手写条目的初始置信度（用户明确说的，天然最高）。 */
    public static final int CONFIDENCE_USER = 5;

    /** 合并产出条目的初始置信度（模型整理出来的，先按中性看待）。 */
    public static final int CONFIDENCE_MERGE = 3;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属会话 ID，关联 {@code conversation.id}。 */
    private String conversationId;

    /** 主题标签（身份 / 偏好 / 待办 / 背景 / 其它），供面板分组与人工扫读。 */
    private String topic;

    /** 事实内容（单条，不含主题前缀）。 */
    private String fact;

    /** {@code topic + fact} 的 MD5，用于同会话内去重（条目由模型每次重新生成，措辞会微变）。 */
    private String factHash;

    /** 来源：MERGE / USER（见类注释的待遇差异）。 */
    private String source;

    /**
     * 置信度 1~5。<b>不是模型打的分</b>，而是「这条事实被确认过几次」的可见化：用户手写的起始 5，
     * 模型整理的起始 3，此后<b>每次合并里仍然被列出就 +1</b>（封顶 5）。于是「最近还在被反复确认」的条目
     * 自然浮到高分区，只被提过一次的臆测停在低位 —— 面板据此排序，用户一眼看出哪条更可信。
     */
    private Integer confidence;

    /**
     * 有效期（可空 = 永不过期）。过期的条目<b>不再注入</b>，但仍留在面板上（标注「已过期」）。
     * <p>
     * 这是「下周三要交报告」这类<b>有时限的事实</b>的归宿：此前它们会一直躺在提示词里，
     * 成了模型眼中永远的待办。注意过期只影响注入，<b>不自动删除</b> —— 删不删由用户决定。
     */
    private LocalDateTime expiresAt;

    /** 状态：{@link #STATUS_ACTIVE} / {@link #STATUS_SUPERSEDED}。 */
    private String status;

    /** 被哪一条取代（{@code id}）：仅在 {@link #STATUS_SUPERSEDED} 时有值，用于面板上「被……取代」的回链。 */
    private Long supersededBy;

    private LocalDateTime createdAt;

    /** 最后被确认/改写的时间 —— 合并里仍然有效就会刷新，故它等于「这条最近还在被维护」。 */
    private LocalDateTime updatedAt;
}
