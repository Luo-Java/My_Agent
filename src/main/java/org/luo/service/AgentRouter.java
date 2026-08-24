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
 * 除基础路由外，还支持「携带待回答的追问」判断：追问进行中（CLARIFY 绑定）由编排层传入上一条追问文本，
 * 让模型区分用户本条消息是「在回答追问」（continueTask）还是「开启新话题」（正常路由），
 * 取代脆弱的关键词/语气词启发式。
 * <p>
 * 不使用 Jackson：强制模型输出 JSON 文本
 * {@code {"route":true,"agentCode":"编码"}} / {@code {"route":false}} / {@code {"route":false,"continue":true}}，
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
     * 判断本次请求应路由到的智能体；未命中或判断失败返回 null（普通对话）。等价于 {@link #route(String, String)} 不带追问上下文。
     */
    public Agent route(String message) {
        return route(message, null).agent();
    }

    /**
     * 携带「待回答的追问」上下文做路由决策。
     *
     * @param message         当前用户输入
     * @param pendingQuestion 编排层正在等待用户回答的追问文本（可空；为空时退化为普通路由）
     * @return {@link RouteDecision}：命中 agent（routeTo）/ 普通对话（none）/ 正在回答追问（continueTask）
     */
    public RouteDecision route(String message, String pendingQuestion) {
        List<Agent> agents = agentService.listAgents();
        if (agents == null || agents.isEmpty()) {
            log.debug("智能路由：暂无智能体，跳过路由");
            return RouteDecision.none();
        }
        if (message == null || message.isBlank()) return RouteDecision.none();
        try {
            StringBuilder list = new StringBuilder();
            for (Agent a : agents) {
                list.append("- ").append(a.getName())
                        .append(" (").append(a.getAgentCode()).append(")")
                        .append("：").append(a.getDescription())
                        .append("\n");
            }
            // 追问上下文提示：仅当存在待回答的追问时追加，引导模型区分「回答追问」与「新话题」
            String continuationHint = (pendingQuestion != null && !pendingQuestion.isBlank())
                    ? "【重要】当前有一个待补全信息的追问（用户此前请求的任务）：\n" + pendingQuestion + "\n"
                    + "判断规则：若用户本条消息是对该追问的直接回答（如给出城市名、日期、语言等具体取值，"
                    + "或与追问相关的简短补充），则视为「正在回答追问、继续当前任务」，输出 {\"route\":false,\"continue\":true}；\n"
                    + "若用户本条消息是新的问题或新的请求（不是对该追问的回答，即便其中含有与追问参数相似的词，"
                    + "如问吃的、问别的），则按上述规则正常判断是否路由。\n\n"
                    : "";
            log.debug("智能路由：调用 LLM 判断路由，可用智能体={}，有追问上下文={}", agents.size(),
                    pendingQuestion != null && !pendingQuestion.isBlank());
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage("你是多智能体路由决策器。根据用户消息的内容判断：是否应该交由某个专属智能体（agent）来处理本次请求。\n\n"
                            + "判断规则：\n"
                            + "1. 只有当用户请求的意图与某个智能体的职责高度匹配时才路由（例如用户明确要求翻译 → 交给翻译类智能体；要求写代码 → 交给编程类智能体）。\n"
                            + "2. 一般闲聊、寒暄，或用户请求没有对应智能体能胜任时，不路由，进行普通对话。\n"
                            + "3. 最多路由到一个智能体；犹豫时选择职责最匹配的，仍不匹配就不路由。\n\n"
                            + continuationHint
                            + "可用智能体清单：\n" + list
                            + "\n你必须且只能输出一个 JSON 对象（不要输出任何其它文字、解释或代码块包裹），三选一：\n"
                            + "需要路由时：{\"route\":true,\"agentCode\":\"<该智能体的编码>\"}\n"
                            + "不需要路由时：{\"route\":false}\n"
                            + "用户在回答当前追问、继续当前任务时：{\"route\":false,\"continue\":true}\n"
                            + "注意：agentCode 必须是上方清单里某个智能体括号内给出的确切编码。"),
                    new UserMessage("用户消息：\n" + message))));
            var generation = response.getResult();
            var assistantMessage = generation != null ? generation.getOutput() : null;
            String reply = assistantMessage != null ? assistantMessage.getText() : null;
            if (reply == null || reply.isBlank()) return RouteDecision.none();
            return parseRouteDecision(reply);
        } catch (Exception e) {
            log.warn("智能路由判断失败，回退普通对话", e);
            return RouteDecision.none();
        }
    }

    /**
     * 解析路由回复为三态决策：优先识别 {@code continue:true}（正在回答追问），其次 {@code route:true}（命中 agent），
     * 其余一律普通对话。使用 Hutool {@code JSONUtil} 解析，规避 Jackson {@code ObjectMapper}。
     */
    private RouteDecision parseRouteDecision(String reply) {
        JSONObject obj;
        try {
            obj = JSONUtil.parseObj(reply);
        } catch (Exception e) {
            log.warn("智能路由：JSON 解析失败，回退普通对话：{}", e.getMessage());
            return RouteDecision.none();
        }
        if (obj.getBool("continue", false)) {
            log.debug("智能路由：用户在回答追问，继续当前任务");
            return RouteDecision.continueTask();
        }
        if (!obj.getBool("route", false)) {
            return RouteDecision.none(); // route 缺失或明确为 false → 不路由
        }
        String code = obj.getStr("agentCode");
        if (code == null || code.isBlank()) {
            log.warn("智能路由：route 为 true 但缺失 agentCode，回退普通对话");
            return RouteDecision.none();
        }
        Agent target = agentService.getByCode(code.trim());
        if (target == null) {
            log.warn("智能路由：路由编码 {} 不存在，回退普通对话", code);
            return RouteDecision.none();
        }
        log.info("智能路由命中：会话按智能体「{}」（{}）处理", target.getName(), target.getAgentCode());
        return RouteDecision.routeTo(target);
    }

    /** 路由决策三态：命中 agent / 普通对话 / 正在回答追问（继续当前任务）。 */
    public record RouteDecision(Agent agent, boolean continuation) {
        /** 未命中（普通对话）。 */
        static RouteDecision none() {
            return new RouteDecision(null, false);
        }

        /** 命中指定智能体。 */
        static RouteDecision routeTo(Agent a) {
            return new RouteDecision(a, false);
        }

        /** 正在回答追问：不路由、不切换，继续当前任务。 */
        static RouteDecision continueTask() {
            return new RouteDecision(null, true);
        }
    }
}
