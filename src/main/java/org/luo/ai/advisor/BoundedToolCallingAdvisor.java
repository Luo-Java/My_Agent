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
 * <p>
 * Spring AI 2.0 的 {@link ToolCallingAdvisor} 用 {@code do...while(isToolCall)} 循环，只靠模型自己停止；
 * 模型反复调同一个工具就会死循环、token 无限累积直到全局超时。本类继承它并重写 {@link #doBeforeCall}
 * 钩子（该钩子在工具循环内<b>每轮模型调用前</b>触发），实现三道防线：
 * <ul>
 *   <li><b>轮数上限</b>：累计往返轮数超过 {@code maxIterations} 即软刹车（不抛错，让模型用已有信息作答）；</li>
 *   <li><b>连续重复检测</b>：模型连续 {@code repeatThreshold} 次请求「同名工具且入参相同」判定原地打转，提前软刹车；</li>
 *   <li><b>单轮 token 预算</b>：读本轮累计用量（{@link RoundTrace#getTotalTokens()}，规划模式下跨步骤累加），
 *       达到 {@code roundBudgetTokens} 即软刹车。轮数与重复检测拦的是「死循环」，这一道拦的是「每一步都合规、
 *       合起来烧穿一轮」——多步规划 + 长工具链可以完全绕过前两道而不违反其中任何一条。</li>
 * </ul>
 * <p>
 * <b>软刹车为什么不是硬中断</b>：见 {@link ToolCallProperties} 的类注释——已跑出的中间结果是花过钱的，
 * 硬中断等于全丢并只回一条错误；软刹车让本轮以「不完整但基于事实、且明确说明不完整」收场。
 * 代价是<b>它不是硬上限</b>：刹车后模型仍会被调用一次来产出最终答案（规划模式下每个被刹住的步骤各一次），
 * 真实用量可能高出上限一到两次调用。
 * <p>
 * <b>计数为何用 ThreadLocal</b>：工具循环的 {@code doBeforeCall} 在同一条执行链上反复触发，但每次都可能跨
 * advisor 调用（{@code adviseCall} 内部 {@code do...while} 同线程推进）；同时 ChatClient 是单例、被并发请求
 * 复用，成员变量计数会串号。ThreadLocal 保证每个请求各自计数，用完即清，避免线程池复用残留。
 * <p>
 * <b>可见性</b>：工具调用本身的进度播报已由 {@code RoundTraceAdvisor}（经 RoundTrace.syncToolCalls）完成；
 * 本类补「刹车」这条播报（含触发原因与用量），并在 WARN 日志里带上 traceId —— 进度只当轮可见，
 * 日志才是事后能检索到的那一份。<b>不落库</b>：为一项默认关闭的治理能力新增 {@code agent_trace} 列不划算。
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
