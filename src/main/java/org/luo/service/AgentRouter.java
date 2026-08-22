package org.luo.service;

import lombok.extern.slf4j.Slf4j;
import org.luo.entity.Agent;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.List;

/**
 * 智能路由服务：对「未绑定智能体的普通会话」，根据消息内容判断是否应交给某个专属智能体（agent）处理。
 * <p>
 * 使用裸 {@link ChatModel} 调用（不走 advisor、不写任何会话记忆）；判断失败或未命中一律回退普通对话，
 * 绝不阻断主流程。路由只影响本次回复的人设/模型参数，不会修改会话的 agentId。
 * <p>
 * 不使用 Jackson：强制模型输出 JSON 文本 {@code {"route":true,"agentCode":"编码"}} 或 {@code {"route":false}}，
 * 解析用 Hutool 的 {@code JSONUtil}（规避 {@code ObjectMapper}）。
 */
@Slf4j
@Service
public class AgentRouter {

    private final AgentService agentService;
    private final ChatModel chatModel;

    public AgentRouter(AgentService agentService, ChatModel chatModel) {
        this.agentService = agentService;
        this.chatModel = chatModel;
    }

    /**
     * 判断本次请求应路由到的智能体；未命中或判断失败返回 null（普通对话）。
     */
    public Agent route(String message) {
        List<Agent> agents = agentService.listAgents();
        if (agents == null || agents.isEmpty()) {
            log.debug("智能路由：暂无智能体，跳过路由");
            return null;
        }
        if (message == null || message.isBlank()) return null;
        try {
            StringBuilder list = new StringBuilder();
            for (Agent a : agents) {
                list.append("- ").append(a.getName())
                        .append(" (").append(a.getAgentCode()).append(")")
                        .append("：").append(a.getDescription())
                        .append("\n");
            }
            log.debug("智能路由：调用 LLM 判断路由，可用智能体={}", agents.size());
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage("你是多智能体路由决策器。根据用户消息的内容判断：是否应该交由某个专属智能体（agent）来处理本次请求。\n\n"
                            + "判断规则：\n"
                            + "1. 只有当用户请求的意图与某个智能体的职责高度匹配时才路由（例如用户明确要求翻译 → 交给翻译类智能体；要求写代码 → 交给编程类智能体）。\n"
                            + "2. 一般闲聊、寒暄，或用户请求没有对应智能体能胜任时，不路由，进行普通对话。\n"
                            + "3. 最多路由到一个智能体；犹豫时选择职责最匹配的，仍不匹配就不路由。\n\n"
                            + "可用智能体清单：\n" + list
                            + "\n你必须且只能输出一个 JSON 对象（不要输出任何其它文字、解释或代码块包裹），二选一：\n"
                            + "需要路由时：{\"route\":true,\"agentCode\":\"<该智能体的编码>\"}\n"
                            + "不需要路由时：{\"route\":false}\n"
                            + "注意：agentCode 必须是上方清单里某个智能体括号内给出的确切编码。"),
                    new UserMessage("用户消息：\n" + message))));
            var generation = response.getResult();
            var assistantMessage = generation != null ? generation.getOutput() : null;
            String reply = assistantMessage != null ? assistantMessage.getText() : null;
            if (reply == null || reply.isBlank()) return null;
            String code = parseRouteCode(reply);
            if (code == null) {
                log.debug("智能路由：未命中，普通对话");
                return null;
            }
            Agent target = agentService.getByCode(code);
            if (target == null) {
                log.warn("智能路由：路由编码 {} 不存在，回退普通对话", code);
                return null;
            }
            log.info("智能路由命中：会话按智能体「{}」（{}）处理", target.getName(), target.getAgentCode());
            return target;
        } catch (Exception e) {
            log.warn("智能路由判断失败，回退普通对话", e);
            return null;
        }
    }

    /**
     * 从路由回复解析目标智能体编码：要求 JSON 中 {@code route} 为 true 且给出 {@code agentCode} 才返回编码，
     * 否则返回 null（普通对话）。使用 Hutool {@code JSONUtil} 解析，规避 Jackson {@code ObjectMapper}。
     */
    private String parseRouteCode(String reply) {
        JSONObject obj;
        try {
            obj = JSONUtil.parseObj(reply);
        } catch (Exception e) {
            log.warn("智能路由：JSON 解析失败，回退普通对话：{}", e.getMessage());
            return null;
        }
        if (!obj.getBool("route", false)) {
            return null; // route 缺失或明确为 false → 不路由
        }
        String code = obj.getStr("agentCode");
        if (code == null || code.isBlank()) {
            log.warn("智能路由：route 为 true 但缺失 agentCode，回退普通对话");
            return null;
        }
        return code.trim();
    }
}
