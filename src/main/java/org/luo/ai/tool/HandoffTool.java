package org.luo.ai.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.entity.Agent;
import org.luo.ai.mapper.AgentMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 「把整个对话转交给另一个智能体」工具（{@value #TOOL_NAME}）—— 与 {@link SubAgentTool} 是<b>两种不同关系</b>。
 * <p>
 * <table>
 *   <tr><th></th><th>call_agent（子任务转交）</th><th>handoff_agent（会话转交）</th></tr>
 *   <tr><td>关系</td><td>调一次，取回产出</td><td>交出去，由它接着谈</td></tr>
 *   <tr><td>后续轮次</td><td>仍由原智能体回答</td><td><b>由目标智能体回答</b></td></tr>
 *   <tr><td>用户感知</td><td>看不到（黑盒）</td><td>看得到（会话已交给 X）</td></tr>
 * </table>
 * <b>为什么要有第二种</b>：子任务转交适合「我这儿缺一块拼图」；而真实场景里更常见的是「这个问题整体不该由我答」
 * —— 用户在一个会话里聊着聊着从闲聊拐进了专业问题，期望的是「换个人来」，不是「他替你问一句再回来」。
 * 后者在原智能体的人设下继续作答，答案风格与能力边界都会错位。
 * <p>
 * 三条实现红线：
 * <ol>
 *   <li><b>动态构造</b>而非 {@code @Tool} 注解式：候选智能体是运行时数据（agent 表随时增删），
 *       与 {@link SubAgentTool} 同理，实例由 {@code ChatComposer} 每轮现构。</li>
 *   <li><b>白名单专属</b>：转交是策略性能力，放进「全量工具」会一次性改变所有既有智能体的行为，
 *       必须在 {@code tools_json} 里显式写 {@value #TOOL_NAME} 才挂载。</li>
 *   <li><b>只带指令、不带正文</b>：工具只记录「交给谁」，真正的接力回答由编排层用目标智能体的完整人设重跑——
 *       本工具<b>不</b>自己去调模型（那是 {@link SubAgentTool} 的做法，会把答案锁在原智能体的回复里）。</li>
 * </ol>
 * 工具命中后返回给模型的是一句话：让模型<b>立刻收尾</b>，别继续作答（它的正文随后会被目标智能体的回答覆盖，
 * 见 {@code ConversationService#overwriteLatestAssistantMessage}）。
 */
@Slf4j
@Service
public class HandoffTool {

    /** 工具名：{@code agent.tools_json} 白名单按此匹配，也是前端「工具装配」里显示的名字。 */
    public static final String TOOL_NAME = "handoff_agent";

    /** 前端工具分组名（沿用「分组 = 来源类名」的既有约定）。 */
    public static final String GROUP = "HandoffTool";

    /** 入参 schema（手写：参数固定两个，写死比引 schema 生成器更直白）。 */
    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{"agentCode":{"type":"string","description":"目标智能体的 agent_code，取值见工具描述里列出的可交接清单"},"reason":{"type":"string","description":"为什么这个问题该交给它（一句话，会展示给用户）"}},"required":["agentCode"]}
            """.strip();

    private final AgentMapper agentMapper;

    public HandoffTool(AgentMapper agentMapper) {
        this.agentMapper = agentMapper;
    }

    /** 前端「工具装配」清单里的元信息（只登记名字与说明，实例每轮现构）。 */
    public static ToolRegistry.ToolInfo toolInfo() {
        return new ToolRegistry.ToolInfo(TOOL_NAME,
                "把整个对话转交给另一个更合适的智能体，由它接续后续轮次（需在白名单中显式声明本工具才会挂载）",
                GROUP);
    }

    /**
     * 为 {@code caller} 现场构造本轮的转交工具。
     *
     * @param caller 调用方智能体（用于排除「转交给自己」并生成候选清单）
     * @param holder 本轮转交结果持有者：工具一旦命中即写入，编排层据此执行接力
     */
    public ToolCallback build(Agent caller, HandoffHolder holder) {
        List<Agent> candidates = listCandidates(caller);
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description(buildDescription(candidates))
                .inputSchema(INPUT_SCHEMA)
                .build();
        return new HandoffCallback(definition, caller, holder);
    }

    /** 候选智能体：全部智能体里排除调用方自己（未落库、无 id 的排除在外）。 */
    private List<Agent> listCandidates(Agent caller) {
        List<Agent> all;
        try {
            all = agentMapper.selectList(new QueryWrapper<Agent>().orderByDesc("updated_at"));
        } catch (Exception e) {
            log.warn("转交工具：读取智能体清单失败，本轮按「无候选」处理：{}", e.getMessage());
            return List.of();
        }
        if (all == null) return List.of();
        return all.stream()
                .filter(a -> a.getId() != null)
                .filter(a -> caller == null || !a.getId().equals(caller.getId()))
                .toList();
    }

    /** 工具描述：固定说明 + 动态候选清单（模型据此才知道「能交给谁」，必须每轮现算）。 */
    private static String buildDescription(List<Agent> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("把整个对话交给另一个更合适的智能体，由它接续后面的对话。\n\n");
        if (candidates.isEmpty()) {
            sb.append("当前没有其他可交接的智能体，不要调用本工具，请直接回答。");
            return sb.toString();
        }
        sb.append("可交接的智能体：\n");
        for (Agent a : candidates) {
            sb.append("- ").append(a.getName()).append(" (").append(a.getAgentCode()).append(")");
            if (a.getDescription() != null && !a.getDescription().isBlank()) {
                sb.append("：").append(a.getDescription());
            }
            sb.append("\n");
        }
        sb.append("\n使用要点：\n")
                .append("1. 只在「这个问题的整体专长不属于你」时调用；自己勉强能答的不要交出去。\n")
                .append("2. 与「转交子任务」不同：调用后<b>整个会话都归它</b>，你不会再参与后续回答。\n")
                .append("3. 调用后请立刻停止作答，只回一句「好的，已转交」。\n")
                .append("4. 不要把会话交给自己。");
        return sb.toString();
    }

    /** 每轮现构的回调：候选清单随库变化，故 ToolDefinition 必须随实例一起重建。 */
    private final class HandoffCallback implements ToolCallback {

        private final ToolDefinition definition;
        private final Agent caller;
        private final HandoffHolder holder;

        private HandoffCallback(ToolDefinition definition, Agent caller, HandoffHolder holder) {
            this.definition = definition;
            this.caller = caller;
            this.holder = holder;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            JSONObject in;
            try {
                in = JSONUtil.parseObj(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
            } catch (Exception e) {
                return "调用失败：参数不是合法 JSON 对象，应为 {\"agentCode\":\"...\"}";
            }
            String code = in.getStr("agentCode");
            if (code == null || code.isBlank()) {
                return "调用失败：缺少必填参数 agentCode（目标智能体编码）。";
            }
            Agent target = agentMapper.selectOne(new QueryWrapper<Agent>()
                    .eq("agent_code", code.trim()).last("LIMIT 1"));
            if (target == null) {
                return "调用失败：智能体编码不存在 —— " + code + "。可交接编码："
                        + listCandidates(caller).stream().map(Agent::getAgentCode).toList();
            }
            if (caller != null && target.getId() != null && target.getId().equals(caller.getId())) {
                return "调用失败：不能把会话交给自己（" + code + "）。请直接回答。";
            }
            holder.set(target, in.getStr("reason"));
            log.info("智能体转交（handoff）：{} → {}（原因：{}）",
                    caller == null ? "-" : caller.getAgentCode(), target.getAgentCode(), in.getStr("reason"));
            // 让本轮尽快收尾：正文随后会被目标智能体的回答覆盖，这里的措辞只是给模型一个体面的收场
            return "转交已受理，会话将交由智能体「" + target.getName() + "」接续。请立刻停止作答，只回复一句「好的，已转交」。";
        }
    }

    /** 本轮转交结果的持有者：工具线程写入、编排层读取（同一轮内，无需同步但用 volatile 保证可见性）。 */
    public static final class HandoffHolder {

        private volatile Agent target;
        private volatile String reason;

        void set(Agent target, String reason) {
            this.target = target;
            this.reason = reason;
        }

        /** 本轮是否发生了转交。 */
        public boolean handedOff() {
            return target != null;
        }

        /** 目标智能体；未转交返回 null。 */
        public Agent target() {
            return target;
        }

        /** 转交原因（可空，仅用于进度播报）。 */
        public String reason() {
            return reason;
        }
    }
}
