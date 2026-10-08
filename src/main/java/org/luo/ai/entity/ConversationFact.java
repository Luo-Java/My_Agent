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

    private LocalDateTime createdAt;

    /** 最后被确认/改写的时间 —— 合并里仍然有效就会刷新，故它等于「这条最近还在被维护」。 */
    private LocalDateTime updatedAt;
}
