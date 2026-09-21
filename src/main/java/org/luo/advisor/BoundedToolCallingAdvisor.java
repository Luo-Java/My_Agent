package org.luo.advisor;

import lombok.extern.slf4j.Slf4j;
import org.luo.properties.ToolCallProperties;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;

import java.util.List;

/**
 * 有界工具循环 Advisor：给 Spring AI 默认的「无限工具循环」装上刹车与可见性。
 * <p>
 * Spring AI 2.0 的 {@link ToolCallingAdvisor} 用 {@code do...while(isToolCall)} 循环，只靠模型自己停止；
 * 模型反复调同一个工具就会死循环、token 无限累积直到全局超时。本类继承它并重写 {@link #doBeforeCall}
 * 钩子（该钩子在工具循环内<b>每轮模型调用前</b>触发），实现三道防线：
 * <ul>
 *   <li><b>轮数上限</b>：累计往返轮数超过 {@code maxIterations} 即软刹车（不抛错，让模型用已有信息作答）；</li>
 *   <li><b>连续重复检测</b>：模型连续 {@code repeatThreshold} 次请求「同名工具且入参相同」判定原地打转，提前软刹车；</li>
 *   <li><b>软刹车</b>：把「已达工具调用上限，请基于已查到的信息作答、不要再调用工具」追加进 system，而非直接抛错中断。</li>
 * </ul>
 * <p>
 * <b>计数为何用 ThreadLocal</b>：工具循环的 {@code doBeforeCall} 在同一条执行链上反复触发，但每次都可能跨
 * advisor 调用（{@code adviseCall} 内部 {@code do...while} 同线程推进）；同时 ChatClient 是单例、被并发请求
 * 复用，成员变量计数会串号。ThreadLocal 保证每个请求各自计数，用完即清，避免线程池复用残留。
 * <p>
 * <b>可见性</b>：工具调用本身的进度播报已由 {@code RoundTraceAdvisor}（经 RoundTrace.syncToolCalls）完成，
 * 本类只补「刹车」这一条播报，与既有进度通道一致。
 */
@Slf4j
public class BoundedToolCallingAdvisor extends ToolCallingAdvisor {

    /** 软刹车时追加进 system 的指令：要求模型停止调工具、基于已有信息作答。 */
    static final String SOFT_STOP_INSTRUCTION =
            "\n\n[工具调用已达上限] 你已进行多次工具调用。请不要再调用任何工具，"
                    + "直接基于目前已获取的信息，用中文给用户一个完整、诚实的回答；"
                    + "若信息仍不完整，请如实说明还缺什么，而不是继续尝试调用工具。";

    private final int maxIterations;
    private final int repeatThreshold;

    /** 单次 adviseCall 的循环状态：迭代计数 + 上一轮请求的工具指纹（工具名 + 入参）。 */
    private static final ThreadLocal<LoopState> LOOP = new ThreadLocal<>();

    /** 循环状态：当前轮数、上一轮请求的工具指纹、连续重复次数、是否已软刹车（刹车只触发一次）。 */
    private static final class LoopState {
        int iterations = 0;
        String lastToolKey = null;
        int repeatCount = 0;
        boolean braked = false;
    }

    /**
     * @param toolCallingManager 工具执行管理器（由自动配置注入）
     * @param toolExecutionEligibilityChecker 判定「是否工具调用」的检查器
     * @param advisorOrder Advisor 顺序（沿用默认 {@link ToolCallingAdvisor#DEFAULT_ORDER}）
     * @param props 工具循环上限配置（max-iterations / repeat-threshold）
     */
    public BoundedToolCallingAdvisor(ToolCallingManager toolCallingManager,
                                     ToolExecutionEligibilityChecker toolExecutionEligibilityChecker,
                                     int advisorOrder, ToolCallProperties props) {
        super(toolCallingManager, toolExecutionEligibilityChecker, advisorOrder, true);
        this.maxIterations = props.maxIterations();
        this.repeatThreshold = props.repeatThreshold();
    }

    @Override
    protected ChatClientRequest doBeforeCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        LoopState state = LOOP.get();
        if (state == null) {
            state = new LoopState();
            LOOP.set(state);
        }
        state.iterations++;

        // 本轮模型请求的工具指纹（来自上一轮 AssistantMessage 里的 toolCalls，即模型上一轮决定要调的工具）
        String toolKey = fingerprint(chatClientRequest);
        if (toolKey != null && toolKey.equals(state.lastToolKey)) {
            state.repeatCount++;
        } else {
            state.repeatCount = 1;
            state.lastToolKey = toolKey;
        }

        boolean overIterations = state.iterations > maxIterations;
        boolean overRepeat = state.repeatCount >= repeatThreshold;
        // 已软刹车则不再重复追加指令/告警：软刹车后模型再被调用时（产出最终答案的那一轮），
        // 历史里仍留有上一条 toolCalls，fingerprint 会继续命中，但无需二次追加。
        if (state.braked || (!overIterations && !overRepeat)) {
            return chatClientRequest;
        }
        state.braked = true;

        // 软刹车：追加「停止调工具」指令，让模型基于已有信息作答，而非抛错中断整轮
        String reason = overRepeat ? "连续重复调用同一工具" : "工具调用轮数已达上限";
        log.warn("工具循环软刹车：{}（第 {} 轮，重复 {} 次）", reason, state.iterations, state.repeatCount);
        var prompt = chatClientRequest.prompt().augmentSystemMessage(systemMessage -> {
            String existing = systemMessage.getText();
            String text = (existing == null ? "" : existing) + SOFT_STOP_INSTRUCTION;
            return systemMessage.mutate().text(text).build();
        });
        return chatClientRequest.mutate().prompt(prompt).build();
    }

    /**
     * 本轮模型请求的工具指纹：取 prompt 历史里最后一条 {@link AssistantMessage} 的工具调用，
     * 拼成「工具名 + 入参」的稳定键，用于重复检测。无工具调用返回 {@code null}（正常收尾轮，不计重复）。
     */
    private static String fingerprint(ChatClientRequest request) {
        List<Message> messages = request.prompt().getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.getMessageType() == MessageType.ASSISTANT && m instanceof AssistantMessage am
                    && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                // 取最后一个工具调用作为本轮指纹（重复死循环通常是「反复调同一个」）
                AssistantMessage.ToolCall last = am.getToolCalls().get(am.getToolCalls().size() - 1);
                return last.name() + "::" + last.arguments();
            }
        }
        return null;
    }

    /** 循环结束（{@code adviseCall} 返回后）清理 ThreadLocal，避免线程池复用残留旧状态。 */
    @Override
    protected ChatClientResponse doFinalizeLoop(ChatClientResponse chatClientResponse,
                                                CallAdvisorChain callAdvisorChain) {
        LOOP.remove();
        return chatClientResponse;
    }
}
