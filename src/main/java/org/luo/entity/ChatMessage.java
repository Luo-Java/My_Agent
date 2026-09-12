package org.luo.entity;

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

    private LocalDateTime createdAt;
}
