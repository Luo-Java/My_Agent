package org.luo.advisor;

import lombok.extern.slf4j.Slf4j;
import org.luo.trace.RoundTrace;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 链路追踪 Advisor：把「模型调用」这一层的事实采集进 {@link RoundTrace}——工具调用明细与 token 用量。
 * <p>
 * <b>为什么必须由 Advisor 采集</b>：工具调用循环发生在 Spring AI 内部（ToolCallingAdvisor 反复调模型、
 * 执行工具、再调模型），业务代码只看得到最终结果，中间调了哪些工具、每次花多少 token 只有 Advisor 看得见。
 * <p>
 * <b>放在链最内层</b>（order 取最大值附近）：这样它在工具循环<b>之内</b>，每一轮模型调用都会经过——
 * 工具调用明细与 token 才能随循环逐步累加，而不是只看到最终那一次。
 * <p>
 * <b>trace 从哪来</b>：{@code ChatService} 把当轮 {@link RoundTrace} 塞进 ChatClient 的 advisor 上下文
 * （键 {@link RoundTrace#CONTEXT_KEY}，与 MemoryChatMemoryAdvisor 用 CONVERSATION_ID 同款机制），
 * 本 Advisor 在 {@link #before} 里取回。取不到（如规划器内部的裸 ChatModel 调用）就整体跳过——
 * 追踪是旁路，缺数据可以接受，报错不可以。
 * <p>
 * <b>token 为什么用 ThreadLocal 过渡</b>：用量只有在模型返回后才拿得到，而 {@code after} 回调只有响应对象。
 * Spring AI 的 {@code before} 与 {@code after} 对同一次 advisor 调用保证<b>同线程同步</b>执行
 * （{@code adviseCall} = before → nextCall → after），故用 ThreadLocal 把 trace 从 before 带到 after 最直接；
 * 且这个传递窗口仅限单次调用内，即便未来规划器改成 DAG 并行（多线程），每线程各自持有自己的 trace，也不会串。
 */
@Slf4j
@Component
public class RoundTraceAdvisor implements BaseAdvisor {

    /**
     * before → after 的同线程传递（见类注释「token 为什么用 ThreadLocal 过渡」）。
     * 只在单次 advisor 调用的窗口内使用，用完即清，避免线程池复用导致串数据。
     */
    private static final ThreadLocal<RoundTrace> CURRENT = new ThreadLocal<>();

    @Override
    public int getOrder() {
        // 比 ToolUsageLoggingAdvisor（MAX_VALUE-100）更靠内，确保工具循环内每一轮都经过
        return Integer.MAX_VALUE - 90;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        RoundTrace trace = traceOf(request.context());
        if (trace == null) return request;
        CURRENT.set(trace);
        trace.syncToolCalls(extractToolCalls(request.prompt()));
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        try {
            // 优先取响应上下文（Spring AI 会把请求上下文带到响应）；取不到则用 before 放下的同线程引用兜底
            RoundTrace trace = traceOf(response.context());
            if (trace == null) trace = CURRENT.get();
            if (trace == null) return response;
            Usage usage = (response.chatResponse() == null || response.chatResponse().getMetadata() == null)
                    ? null : response.chatResponse().getMetadata().getUsage();
            if (usage != null) {
                trace.addUsage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
            }
        } finally {
            CURRENT.remove();
        }
        return response;
    }

    /** 从 advisor 上下文取当轮 trace；不是本项目的上下文（如中间步骤裸调用）返回 null。 */
    private static RoundTrace traceOf(java.util.Map<String, Object> context) {
        if (context == null) return null;
        Object value = context.get(RoundTrace.CONTEXT_KEY);
        return (value instanceof RoundTrace t) ? t : null;
    }

    /**
     * 从 prompt 的历史消息里抽出「已执行的工具调用」：工具名 + 入参 + 返回摘要。
     * <ul>
     *   <li>入参来自 {@link AssistantMessage#getToolCalls()}（模型发起的调用，含 arguments）；</li>
     *   <li>返回来自 {@link ToolResponseMessage}（工具执行结果，含 responseData）。</li>
     * </ul>
     * 两者在历史里<b>按执行顺序</b>各自成列，按下标对齐即可（数量不一致时以多者为准、缺失侧留空）。
     * 这个方法在每轮模型调用前都会被调一次，返回的是「此刻完整历史」——调用方
     * {@link RoundTrace#syncToolCalls} 只在变长时替换，保证最终拿到全量而不是最后一次的空集。
     */
    private static List<RoundTrace.ToolCall> extractToolCalls(Prompt prompt) {
        if (prompt == null || prompt.getInstructions() == null) return List.of();
        List<String> names = new ArrayList<>();
        List<String> args = new ArrayList<>();
        List<String> results = new ArrayList<>();
        for (Message m : prompt.getInstructions()) {
            if (m.getMessageType() == MessageType.ASSISTANT && m instanceof AssistantMessage am
                    && am.getToolCalls() != null) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    names.add(tc.name());
                    args.add(tc.arguments());
                }
            } else if (m.getMessageType() == MessageType.TOOL && m instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    results.add(r.responseData());
                }
            }
        }
        int size = Math.max(names.size(), results.size());
        List<RoundTrace.ToolCall> calls = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String name = (i < names.size()) ? names.get(i) : "tool";
            calls.add(RoundTrace.ToolCall.of(name,
                    i < args.size() ? args.get(i) : null,
                    i < results.size() ? results.get(i) : null));
        }
        return calls;
    }
}
