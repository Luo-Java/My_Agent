package org.luo.agent;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.luo.properties.PromptProperties;
import org.luo.entity.Agent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.luo.chat.ChatComposer;
import org.luo.service.AgentService;
import org.luo.service.ChatService;

/**
 * 动态规划器（Dynamic Planner）服务。
 * <p>
 * 与「手动预配置步骤的顺序工作流」不同，规划器在<b>运行时</b>根据用户自然语言目标与当前可用智能体清单，
 * 由 LLM 决定调用哪些智能体、以什么顺序、每步给什么指令，产出顺序计划后交 {@link ChatService} 执行
 * （前一步输出作为后一步输入）。用户无需任何预配置。
 * <p>
 * 复用 {@link ChatComposer#internalChatClient()}（无记忆，只产出计划 JSON、不写会话历史）；计划解析用
 * Hutool {@code JSONUtil}（与路由/参数解析一致，规避 Jackson ObjectMapper）。解析失败或无可执行步骤时
 * 返回空列表，由调用方回退普通回答，绝不阻断主流程。
 */
@Slf4j
@Service
public class PlannerService {

    private final AgentService agentService;
    private final ChatComposer composer;
    private final PromptProperties promptProperties;

    public PlannerService(AgentService agentService,
                          ChatComposer composer,
                          PromptProperties promptProperties) {
        this.agentService = agentService;
        this.composer = composer;
        this.promptProperties = promptProperties;
    }

    /** 计划中的一步：调用哪个智能体（按 agentCode）+ 给该智能体的指令。 */
    public record PlanStep(String agentCode, String instruction) {}

    /**
     * 动态规划：根据用户目标与可用智能体清单，让 LLM 产出「顺序执行」计划。
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
            String list = agentService.buildAgentListText(agents);
            String system = PromptProperties.render(promptProperties.plannerSystem(),
                    Map.of("agentList", list));
            String reply = composer.internalChatClient().prompt()
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

    /** 解析规划回复为步骤列表：识别 {@code steps} 数组，逐个取 agentCode + instruction（Hutool JSONUtil，规避 Jackson）。 */
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
