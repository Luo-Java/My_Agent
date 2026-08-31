package org.luo.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.luo.advisor.ToolUsageLoggingAdvisor;
import org.luo.entity.Agent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态规划器（Dynamic Planner）服务。
 * <p>
 * 与「顺序工作流（手动预配置步骤）」不同，规划器在<b>运行时</b>根据用户的自然语言目标与当前可用智能体清单，
 * 由 LLM 自行决定应该调用哪些智能体、以什么顺序、每步给什么指令，产出一份顺序计划后再交给 {@link ChatService}
 * 顺序执行（前一步输出作为后一步输入）。用户无需任何预配置，直接描述目标即可，对终端用户更友好。
 * <p>
 * 规划器使用独立的无记忆 {@link ChatClient}（只产出计划 JSON，不写入任何会话历史）；计划解析使用 Hutool
 * 的 {@code JSONUtil}（与路由 / 参数解析一致，规避 Jackson {@code ObjectMapper}）。解析失败或无可执行步骤时
 * 返回空列表，由调用方回退普通回答，绝不阻断主流程。
 */
@Slf4j
@Service
public class PlannerService {

    private final AgentService agentService;
    private final ChatClient plannerClient;

    public PlannerService(AgentService agentService,
                          ChatClient.Builder chatClientBuilder,
                          ToolUsageLoggingAdvisor toolUsageLoggingAdvisor) {
        this.agentService = agentService;
        // 规划器用独立的无记忆 ChatClient：只负责产出计划 JSON，不写会话历史、不挂工具
        this.plannerClient = chatClientBuilder.clone().defaultAdvisors(toolUsageLoggingAdvisor).build();
    }

    /** 计划中的一步：调用哪个智能体（按 agentCode）+ 给该智能体的指令。 */
    public record PlanStep(String agentCode, String instruction) {}

    /**
     * 动态规划：根据用户目标与当前可用智能体清单，让 LLM 产出一份「顺序执行」的计划。
     *
     * @param userGoal 用户当前自然语言目标
     * @return 计划步骤列表（可能为空，表示无需编排、直接普通回答）；解析失败返回空列表
     */
    public List<PlanStep> plan(String userGoal) {
        List<Agent> agents = agentService.listAgents();
        if (agents == null || agents.isEmpty()) {
            log.debug("动态规划：暂无可用智能体，无需编排");
            return List.of();
        }
        if (userGoal == null || userGoal.isBlank()) return List.of();
        try {
            StringBuilder list = new StringBuilder();
            for (Agent a : agents) {
                list.append("- ").append(a.getName())
                        .append(" (").append(a.getAgentCode()).append(")")
                        .append("：").append(a.getDescription()).append("\n");
            }
            String system = "你是一个任务规划器（Planner）。给定用户的自然语言目标，以及一组可用的智能体"
                    + "（每个智能体有 名称、编码、职责描述），请规划出完成该目标所需调用的智能体步骤"
                    + "（按顺序执行，前一步的输出会作为后一步的输入）。\n\n"
                    + "规则：\n"
                    + "1. 只能使用下面列出的智能体编码（agentCode）；可以重复或不使用某些智能体。\n"
                    + "2. 如果用户目标由一个智能体即可独立完成，则只规划 1 步。\n"
                    + "3. 如果多个智能体串联能更好地完成任务（例如：先查天气，再把结果翻译），则规划多步，"
                    + "并明确每步要做什么。\n"
                    + "4. 每一步都必须给出清晰的 instruction（给该智能体的具体指令，包含必要的上下文与用户原始诉求）。\n"
                    + "5. 如果用户的请求与任何智能体都不相关（例如纯闲聊、问候），则返回空步骤列表 steps:[]，"
                    + "由通用助手直接回答。\n\n"
                    + "可用智能体清单：\n" + list
                    + "\n你必须且只能输出一个 JSON 对象（不要输出任何其它文字、解释或代码块包裹，"
                    + "若不慎用了 ```json``` 代码块也只保留内部 JSON）：\n"
                    + "{\"steps\":[{\"agentCode\":\"<编码>\",\"instruction\":\"<给该智能体的指令>\"}]}\n";
            String reply = plannerClient.prompt()
                    .system(system)
                    .user("用户目标：\n" + userGoal)
                    .call()
                    .content();
            if (reply == null || reply.isBlank()) return List.of();
            return parsePlan(stripFences(reply));
        } catch (Exception e) {
            log.warn("动态规划失败，回退普通回答", e);
            return List.of();
        }
    }

    /**
     * 解析规划回复为步骤列表：识别 {@code steps} 数组，逐个取出 agentCode + instruction。
     * 使用 Hutool {@code JSONUtil} 解析，规避 Jackson {@code ObjectMapper}。
     */
    private List<PlanStep> parsePlan(String reply) {
        try {
            JSONObject obj = JSONUtil.parseObj(reply);
            JSONArray steps = obj.getJSONArray("steps");
            if (steps == null || steps.isEmpty()) return List.of();
            List<PlanStep> out = new ArrayList<>();
            for (Object o : steps) {
                if (!(o instanceof JSONObject js)) continue;
                String code = js.getStr("agentCode");
                if (code == null || code.isBlank()) continue;
                out.add(new PlanStep(code.trim(), js.getStr("instruction")));
            }
            return out;
        } catch (Exception e) {
            log.warn("动态规划：JSON 解析失败，回退普通回答：{}", e.getMessage());
            return List.of();
        }
    }

    /** 去掉可能残留的 ```json ... ``` 代码块围栏，只保留内部 JSON。 */
    private String stripFences(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNL = t.indexOf('\n');
            if (firstNL >= 0) t = t.substring(firstNL + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
            t = t.trim();
        }
        return t;
    }
}
