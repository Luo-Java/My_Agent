package org.luo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 智能体（Agent）实体：可在页面创建的各类 AI 角色。
 * 绑定到会话后，其 systemPrompt / model / temperature 会决定该会话的对话人设与模型参数。
 */
@Data
@NoArgsConstructor
@TableName("agent")
public class Agent {

    /** 智能体 ID，数据库自增主键（AUTO 表示由数据库生成）。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 智能体名称，如「翻译官」「Python 老师」。 */
    private String name;

    /** 智能体唯一编码（agent_code），多智能体协作时用于路由到具体智能体，如 translator、python-teacher。 */
    private String agentCode;

    /** 图标（可选），前端展示用，通常是 emoji，如 🤖。 */
    private String icon;

    /** 智能体描述（可选）。 */
    private String description;

    /** 系统提示词 / 人设：作为该智能体对话的 SystemMessage 注入。 */
    private String systemPrompt;

    /** 模型名称覆盖（可选），如 gpt-4o、deepseek-chat。 */
    private String model;

    /** 温度（可选），控制回复随机性，通常 0~2。 */
    private Double temperature;

    /** 主题色（可选），前端展示用，如 #3b82f6。 */
    private String avatarColor;

    /**
     * 参数清单（JSON）：声明该智能体执行任务所需的参数，用于对话中的「追问 / 参数补全」。
     * 例如：[{"key":"targetLang","label":"目标语言","required":true,"hint":"如：英语/日语","options":["英语","日语","韩语"]}]
     * 为空表示不启用参数补全（走普通对话）。
     */
    private String paramSchema;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
