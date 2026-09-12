package org.luo.dto;

/**
 * 智能体创建 / 更新请求体。
 *
 * @param id            智能体 ID（更新时必填，创建时传 null 由数据库自增生成）
 * @param name          智能体名称
 * @param agentCode     智能体唯一编码（多智能体协作路由用，可选；创建时留空自动生成，更新时留空保持原值）
 * @param icon          图标（可选，通常是 emoji）
 * @param description   智能体描述（可选）
 * @param systemPrompt  系统提示词 / 人设
 * @param paramSchema   参数清单（JSON），声明执行所需参数用于追问/参数补全（可选）
 * @param toolsJson     工具装配（JSON 数组，可选）：{@code null}=不限制（挂全部工具）、
 *                      {@code "[]"}=不挂工具、{@code ["工具名"]}=白名单。注意 null 是有意义取值
 *                      （表示「全部工具」），更新时会原样写入，故不可用「跳过空值」处理
 * @param model         模型名称覆盖（可选）
 * @param temperature   温度（可选，0~2）
 * @param avatarColor   主题色（可选）
 */
public record UpsertAgentRequest(
        Long id,
        String name,
        String agentCode,
        String icon,
        String description,
        String systemPrompt,
        String paramSchema,
        String toolsJson,
        String model,
        Double temperature,
        String avatarColor
) {
}
