package org.luo.service;

import lombok.extern.slf4j.Slf4j;
import org.luo.config.PromptProperties;
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
import java.util.Map;

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
    private final PromptProperties promptProperties;

    public AgentRouter(AgentService agentService, ChatModel chatModel, PromptProperties promptProperties) {
        this.agentService = agentService;
        this.chatModel = chatModel;
        this.promptProperties = promptProperties;
    }

    /**
     * 判断本次请求应路由到的智能体；未命中或判断失败返回 null（普通对话）。等价于 {@link #route(String, String)} 不带追问上下文。
     */
    public Agent route(String message) {
        return route(message, null, null).agent();
    }

    /**
     * 携带「待回答的追问」上下文做路由决策。
     *
     * @param message         当前用户输入
     * @param pendingQuestion 编排层正在等待用户回答的追问文本（可空；为空时退化为普通路由）
     * @return {@link RouteDecision}：命中 agent（routeTo）/ 普通对话（none）/ 正在回答追问（continueTask）
     */
    public RouteDecision route(String message, String pendingQuestion) {
        return route(message, pendingQuestion, null);
    }

    /**
     * 携带「待回答的追问」与「最近若干轮对话上下文」做路由决策。
     *
     * @param message         当前用户输入
     * @param pendingQuestion 编排层正在等待用户回答的追问文本（可空；为空时退化为普通路由）
     * @param recentContext   最近若干轮对话文本（可空；用于识别「承接上一轮的短追问」，
     *                        如上一轮在查天气、用户只说「北京呢？」这类缺主语的短句，避免被误判为普通对话）
     * @return {@link RouteDecision}：命中 agent（routeTo）/ 普通对话（none）/ 正在回答追问（continueTask）
     */
    public RouteDecision route(String message, String pendingQuestion, String recentContext) {
        List<Agent> agents = agentService.listAgents();
        if (agents == null || agents.isEmpty()) {
            log.debug("智能路由：暂无智能体，跳过路由");
            return RouteDecision.none();
        }
        if (message == null || message.isBlank()) return RouteDecision.none();
        try {
            // 智能体清单文本与动态规划共用一处维护（AgentService.buildAgentListText），避免两处手拼漂移
            String list = agentService.buildAgentListText(agents);
            // 追问上下文提示：仅当存在待回答的追问时追加，引导模型区分「回答追问」与「新话题」
            String continuationHint = (pendingQuestion != null && !pendingQuestion.isBlank())
                    ? PromptProperties.render(promptProperties.routerContinuationHint(),
                            Map.of("pendingQuestion", pendingQuestion))
                    : "";
            // 对话上下文：最近若干轮，用于识别「承接上一轮的短追问」（如上一轮查天气、用户只说「北京呢？」）
            String contextBlock = (recentContext != null && !recentContext.isBlank())
                    ? PromptProperties.render(promptProperties.routerContextBlock(),
                            Map.of("recentContext", recentContext))
                    : "";
            log.debug("智能路由：调用 LLM 判断路由，可用智能体={}，有追问上下文={}，有对话上下文={}", agents.size(),
                    pendingQuestion != null && !pendingQuestion.isBlank(),
                    recentContext != null && !recentContext.isBlank());
            String system = PromptProperties.render(promptProperties.routerSystem(), Map.of(
                    "continuationHint", continuationHint,
                    "contextBlock", contextBlock,
                    "agentList", list));
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(system),
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
