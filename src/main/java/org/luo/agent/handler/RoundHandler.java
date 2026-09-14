package org.luo.agent.handler;

import org.luo.entity.Conversation;
import org.luo.trace.RoundTrace;

import java.util.function.Consumer;
import org.luo.service.ChatService;

/**
 * 一轮对话的处理策略：不同会话形态（规划 / 普通）各自实现差异化编排。记忆写入契约差异是二者不可合并为
 * 同一方法体的根本原因：普通路径信任记忆 Advisor 自动落库；规划路径全程绕开 Advisor（避免「指令+上一步
 * 输出」合成串污染历史），改由 PlannerRoundHandler 在回复产出后显式补写整对。
 * <p>
 * 由 {@code ChatService.runRound} 按会话形态选择调用；新增形态只需新增实现类，主流程不用再动。
 */
public interface RoundHandler {

    /**
     * 处理一轮对话。
     *
     * @param conv     会话实体（由调用方一次查出后传入，本方法不再查库）
     * @param message  当前用户输入（纯提问文本，不含附件内容；附件走 material 通道）
     * @param material 本轮附件材料（图片 caption / 文档解析文本），仅当轮注入、不进记忆；空串表示无附件
     * @param progress 执行过程播报回调（流式推 progress 事件，同步传空回调）；这些文本只展示、不进记忆
     * @param trace    本轮追踪上下文：实现类写入本环节事实（路由结论 / 计划 / RAG 引用），工具调用与 token
     *                 由 Advisor 采集。纯旁路，写不进去也不影响对话，故允许为 null
     * @return 本轮结果；本策略无法处理时返回 {@link RoundResult#fallback()}（其 reply 为 null），由调用方
     *         回退普通对话策略——请勿返回裸 {@code null}，以免与异常/未处理语义混淆
     */
    RoundResult handle(Conversation conv, String conversationId, String message, String material,
                       Consumer<String> progress, RoundTrace trace);
}
