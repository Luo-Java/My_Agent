package org.luo.ai.advisor;

import lombok.extern.slf4j.Slf4j;
import org.luo.ai.properties.ToolCallProperties;
import org.luo.ai.trace.RoundTrace;
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
 * 继承 {@link ToolCallingAdvisor} 并重写 {@link #doBeforeCall}（工具循环内每轮模型调用前触发）：轮数上限、
 * 连续同名同参重复检测、单轮 token 预算（读 {@link RoundTrace#getTotalTokens()}，规划模式下跨步骤累加）。
 * 最后一道拦的是「每步都合规、合起来烧穿一轮」—— 多步规划 + 长工具链可完全绕过前两道。都是<b>软刹车</b>
 * （理由见 {@link ToolCallProperties}），故<b>不是硬上限</b>：刹车后仍会再调一次模型，用量可能高出上限一到两次。
 * <p>
 * 计数用 ThreadLocal：ChatClient 是单例、被并发请求复用，成员变量会串号；用完即清，避免线程池复用残留。
 * 刹车播报 + WARN（含 traceId）留痕，<b>不落库</b>（为默认关闭的治理能力新增 agent_trace 列不划算）。
 */
@Slf4j
public class BoundedToolCallingAdvisor extends ToolCallingAdvisor {

    /** 软刹车时追加进 system 的指令：要求模型停止调工具、基于已有信息作答。 */
    static final String SOFT_STOP_INSTRUCTION =
            "\n\n[工具调用已达上限] 你已进行多次工具调用。请不要再调用任何工具，"
                    + "直接基于目前已获取的信息，用中文给用户一个完整、诚实的回答；"
                    + "若信息仍不完整，请如实说明还缺什么，而不是继续尝试调用工具。";

    /** 预算用尽时的软刹车指令：与轮数/重复刹车分开，因为它必须让模型把「答案不完整」这件事说出来。 */
    static final String BUDGET_STOP_INSTRUCTION =
            "\n\n[本轮 token 预算已用尽] 请不要再调用任何工具，直接基于目前已获取的信息，"
                    + "用中文给用户一个完整、诚实的回答，并在结尾明确说明："
                    + "本轮因 token 预算上限而提前收尾，结果可能不完整、还需要哪些信息。"
                    + "不要继续尝试调用工具，也不要假装信息已经查全。";

    private final int maxIterations;
    private final int repeatThreshold;
    /** 单轮累计 token 上限；<=0 表示不启用（见 {@link ToolCallProperties#roundBudgetOn()}）。 */
    private final long roundBudgetTokens;

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
     * @param props 失控刹车配置（max-iterations / repeat-threshold / round-budget-tokens）
     */
    public BoundedToolCallingAdvisor(ToolCallingManager toolCallingManager,
                                     ToolExecutionEligibilityChecker toolExecutionEligibilityChecker,
                                     int advisorOrder, ToolCallProperties props) {
        super(toolCallingManager, toolExecutionEligibilityChecker, advisorOrder, true);
        this.maxIterations = props.maxIterations();
        this.repeatThreshold = props.repeatThreshold();
        this.roundBudgetTokens = props.roundBudgetTokens();
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

        // 已软刹车则不再重复追加指令/告警：软刹车后模型再被调用时（产出最终答案的那一轮），
        // 历史里仍留有上一条 toolCalls，fingerprint 会继续命中，但无需二次追加。
        if (state.braked) {
            return chatClientRequest;
        }

        boolean overIterations = state.iterations > maxIterations;
        boolean overRepeat = state.repeatCount >= repeatThreshold;
        // 单轮 token 预算：取本轮累计值（RoundTrace 在每次模型返回后累加；取不到 trace 的裸调用不受管辖）
        RoundTrace trace = RoundTrace.from(chatClientRequest.context());
        long usedTokens = trace == null ? 0L : trace.getTotalTokens();
        boolean overBudget = roundBudgetTokens > 0 && trace != null && usedTokens >= roundBudgetTokens;
        if (!overIterations && !overRepeat && !overBudget) {
            return chatClientRequest;
        }
        state.braked = true;

        // 软刹车：追加「停止调工具」指令，让模型基于已有信息作答，而非抛错中断整轮
        String reason;
        String instruction;
        if (overBudget) {
            reason = "单轮 token 预算已用尽（已用 " + usedTokens + " / 上限 " + roundBudgetTokens + "）";
            instruction = BUDGET_STOP_INSTRUCTION;
        } else if (overRepeat) {
            reason = "连续重复调用同一工具";
            instruction = SOFT_STOP_INSTRUCTION;
        } else {
            reason = "工具调用轮数已达上限";
            instruction = SOFT_STOP_INSTRUCTION;
        }
        // 日志带 traceId：进度只在当轮可见，日志才是事后检索整轮链路的那一份
        log.warn("工具循环软刹车：{}（第 {} 轮，重复 {} 次，traceId={}）", reason, state.iterations,
                state.repeatCount, trace == null ? "-" : trace.getTraceId());
        if (trace != null) {
            trace.reportProgress("⚠️ " + reason + "，本轮提前收尾：将基于已有信息作答，结果可能不完整");
        }
        var prompt = chatClientRequest.prompt().augmentSystemMessage(systemMessage -> {
            String existing = systemMessage.getText();
            String text = (existing == null ? "" : existing) + instruction;
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
