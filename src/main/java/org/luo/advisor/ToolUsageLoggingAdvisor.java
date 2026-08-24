package org.luo.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工具使用监控 Advisor：放在 Advisor 链最内层（order 最大），
 * 处于 ToolCallingAdvisor 工具循环内部，每轮模型调用都会经过这里。
 * <p>
 * 日志同时记录两件事：
 * <ul>
 *   <li>本轮暴露 —— options 里当前挂了哪些工具（Agent 对话挂全局能力池；普通对话不挂工具，暴露为空列表）；</li>
 *   <li>已执行 —— 对话历史里 ToolResponseMessage 记录的、真正跑过的工具。</li>
 * </ul>
 */
@Slf4j
@Component
public class ToolUsageLoggingAdvisor implements BaseAdvisor {

    @Override
    public int getOrder() {
        // 最大 order = 链最内层，位于工具调用循环之内，能看到每轮真实的工具集合
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!(request.prompt().getOptions() instanceof ToolCallingChatOptions toolOptions)) {
            return request;
        }
        String sessionId = String.valueOf(request.context().getOrDefault(ChatMemory.CONVERSATION_ID, "unknown"));

        // 本轮暴露给模型的工具：普通对话（未挂载任何工具）时 getToolCallbacks() 为 null，需判空
        List<String> exposed = toolOptions.getToolCallbacks() == null
                ? List.of()
                : toolOptions.getToolCallbacks().stream()
                        .map(tc -> tc.getToolDefinition().name())
                        .toList();

        // 对话历史中已实际执行的工具（ToolResponseMessage）
        List<String> executed = request.prompt().getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.TOOL)
                .map(m -> ((ToolResponseMessage) m).getResponses().stream()
                        .map(ToolResponseMessage.ToolResponse::name)
                        .collect(Collectors.joining(",")))
                .toList();

        log.info("[ToolUsage] 会话={}, 本轮暴露={}, 已执行={}", sessionId, exposed, executed);
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }
}
