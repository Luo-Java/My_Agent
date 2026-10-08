package org.luo.ai.dto;

import org.luo.ai.entity.Agent;

/**
 * 智能体的可移植定义（导入 / 导出的交换格式）。
 * <p>
 * 与 {@link UpsertAgentRequest} 的差别就在「可移植」：<b>不含 {@code id}、不含时间戳</b>（id 是本机自增主键，
 * 导到别的环境毫无意义）。导入一律按 {@code agentCode} 匹配既有智能体，故它是本格式的<b>业务主键</b>；
 * 编码为空会导致每次导入都新建一条。
 * <b>专属知识库不在包内</b>：{@code kb.agent_id} 挂着文件与向量、属知识库模块且体积可能很大，导入后需在新环境
 * 另行重建。这里只保证「智能体本身」（人设、参数、工具装配、模型参数）完整迁移。
 *
 * @param name         智能体名称
 * @param agentCode    智能体唯一编码（导入时的匹配键；为空则按名称自动生成）
 * @param icon         图标（可选）
 * @param description  描述（必填，缺失会跳过该条）
 * @param systemPrompt 系统提示词 / 人设（必填，缺失会跳过该条）
 * @param paramSchema  参数清单 JSON（可选）
 * @param toolsJson    工具装配 JSON；{@code null}=挂全部、{@code "[]"}=不挂、{@code ["名"]}=白名单
 * @param model        模型覆盖（可选）
 * @param temperature  温度（可选）
 * @param avatarColor  主题色（可选）
 */
public record AgentPortable(
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

    /** 由库内实体生成可移植定义（剥掉 id 与 created/updated）。 */
    public static AgentPortable of(Agent a) {
        return new AgentPortable(a.getName(), a.getAgentCode(), a.getIcon(), a.getDescription(),
                a.getSystemPrompt(), a.getParamSchema(), a.getToolsJson(),
                a.getModel(), a.getTemperature(), a.getAvatarColor());
    }
}
