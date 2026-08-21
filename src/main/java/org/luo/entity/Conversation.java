package org.luo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@TableName("conversation")
public class Conversation {

    /** 会话 ID，业务层生成 UUID 后写入（INPUT 表示自行赋值）。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String title;

    /** 绑定的智能体 ID，关联 agent.id（自增主键）；为空表示使用默认助手。 */
    private Long agentId;

    /** 较早对话的滚动摘要（长期记忆），超出最近窗口的历史会被 LLM 压缩进这里。 */
    private String summary;

    /** 已被摘要覆盖的最旧消息条数（与 chat_message 按时间正序的索引对齐）。 */
    private Integer summarizedCount;

    /** 用户核心信息（长期关键事实：姓名/身份/偏好/待办等），随摘要一起由 LLM 提取更新。 */
    private String coreFacts;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
