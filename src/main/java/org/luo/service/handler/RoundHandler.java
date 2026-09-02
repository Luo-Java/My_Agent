package org.luo.service.handler;

import org.luo.entity.Conversation;

import java.util.function.Consumer;

/**
 * 一轮对话的处理策略：不同会话形态（规划 / 普通）各自实现差异化编排。
 * 记忆写入契约差异是二者不可合并为同一方法体的根本原因：
 * 普通路径信任记忆 Advisor 自动落库；规划路径全程绕开 Advisor（避免「指令+上一步输出」
 * 合成串污染历史），改由 PlannerRoundHandler 在回复产出后显式补写整对。
 * <p>
 * 由 ChatService 的 {@code runRound} 统一入口按会话形态选择并调用；
 * 新增会话形态（如手工编排的工作流）只需新增实现类，主流程不用再动。
 */
public interface RoundHandler {

    /**
     * 处理一轮对话。
     *
     * @param conv           会话实体（由调用方一次查出后传入，本方法不再查库）
     * @param conversationId 会话 ID
     * @param message        当前用户输入
     * @param progress       执行过程播报回调（流式接口推 progress 事件，同步接口传空回调），
     *                       这些文本只展示、不进记忆
     * @return 本轮结果；本策略无法处理时返回 {@link RoundResult#fallback()}（其 reply 为 null），
     *         由调用方回退到普通对话策略——请勿返回裸 {@code null}，以免与异常/未处理语义混淆
     */
    RoundResult handle(Conversation conv, String conversationId, String message, Consumer<String> progress);
}
