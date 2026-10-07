package org.luo.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@TableName("chat_message")
public class ChatMessage {

    /** 自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String conversationId;

    /** user / assistant。 */
    private String role;

    private String content;

    /**
     * 本轮附件元数据（JSON 数组：type/filename/storedName/size），仅用于历史展示。
     * 不含附件正文、不参与记忆读取（DbChatMemory.get 只取 content），对 LLM 上下文零影响。
     */
    private String attachmentsJson;

    /**
     * 本轮 RAG 引用来源（JSON 数组：index/kbName/source/chunkId/score），仅 assistant 消息可能有值。
     * 与 {@link #attachmentsJson} 同理：只服务前端引用角标与来源列表，不含正文，
     * 也不参与记忆读取（DbChatMemory.get 只读 content），对 LLM 上下文与 token 零影响。
     */
    private String citationsJson;

    /**
     * 对话分支组 ID：同一轮提问的多个「版本」（编辑重发 / 重新生成）共用同一个值。
     * <b>NULL = 从未分叉</b>（该轮只有一个版本）—— 刻意不做存量回填：读取侧一律按
     * 「{@code turn_group_id IS NULL OR turn_active = 1}」过滤，NULL 行天然全量可见，与改造前行为一致。
     */
    private String turnGroupId;

    /** 组内版本序号（从 1 开始）。写入侧保证连续递增，故 {@code versionCount == max(version)}。 */
    private Integer turnVersion;

    /**
     * 该版本是否为当前生效版本。同一组内至多一行为 true，未分叉的行恒为 true。
     * <p>
     * <b>只有生效版本参与读取</b>（会话历史 / 记忆窗口 / 消息计数 / 摘要切片）—— 否则被替换掉的旧版本
     * 会一起进 prompt，模型会看到同一轮提问的两个不同回答。
     */
    private Boolean turnActive;

    private LocalDateTime createdAt;
}
