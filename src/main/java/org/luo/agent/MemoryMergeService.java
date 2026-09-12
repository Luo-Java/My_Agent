package org.luo.agent;

import lombok.extern.slf4j.Slf4j;
import org.luo.config.MemoryProperties;
import org.luo.config.PromptProperties;
import org.luo.entity.ChatMessage;
import org.luo.entity.Conversation;
import org.luo.memory.DbChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.luo.service.ConversationService;

/**
 * 会话记忆合并服务：对话结束后，将「溢出窗口的旧消息」与「已有摘要/关键事实」合并，
 * 一次 LLM 调用同时产出更新后的滚动摘要与用户核心信息，回写 conversation 表。
 * <p>
 * 使用裸 {@link ChatModel} 直接调用（不走 advisor），否则 advisor 会把摘要指令当作对话消息写入记忆造成污染。
 * 失败一律回退到已有记忆，保证主流程不被打断、长期记忆不丢。
 * <p>
 * <b>数据保留契约</b>：合并只把窗口外消息<b>排除出主模型上下文</b>并回写摘要/关键事实，
 * <b>不删除 chat_message 行</b>（历史仍可全量回放）。{@link ParamFillingService} 的澄清重放
 * （追问计数 / 参数抽取）依赖该保留语义——若未来改为物理归档旧消息，需同步其实现，
 * 否则跨轮参数补全会静默断裂。
 */
@Slf4j
@Service
public class MemoryMergeService {

    /**
     * 批量记忆合并阈值：累计溢出这么多条消息才触发一次 LLM 记忆合并（摘要 + 关键事实）。
     * 值越大调用越少（默认 6 条 ≈ 每 3 轮一次），但批次之间溢出的消息会暂时缺席上下文；
     * 值越小记忆越细但调用越频繁（设为 1 即回到每轮合并）。可调。
     */
    private static final int SUMMARY_BATCH_SIZE = 6;

    private final ConversationService conversationService;
    private final ChatModel chatModel;
    /** 记忆合并提示词（纯静态，外置）。 */
    private final String memoryMergeSystem;
    /** 记忆合并专用线程池：与对话主链路（Reactor boundedElastic）隔离，合并再慢也不挤占对话执行线程。 */
    private final Executor memoryMergeExecutor;
    /**
     * 记忆窗口配置：与 {@code DbChatMemory} 共用同一份参数计算窗口边界。
     * 必须注入而非各写一份默认值——两处参数一旦不一致，「被摘要掉的区间」就会与实际
     * 上下文窗口错位（重复摘要或静默丢记忆）。
     */
    private final MemoryProperties memoryProperties;

    /**
     * 正在合并中的会话集合：同一会话的记忆合并互斥，避免用户连发消息时并发触发多次 LLM 合并、
     * 相互覆盖摘要（跳过的那次不会丢，下一轮对话结束时会再检查一遍）。
     */
    private final Set<String> merging = ConcurrentHashMap.newKeySet();

    public MemoryMergeService(ConversationService conversationService, ChatModel chatModel,
                              @Qualifier("memoryMergeExecutor") Executor memoryMergeExecutor,
                              PromptProperties promptProperties,
                              MemoryProperties memoryProperties) {
        this.conversationService = conversationService;
        this.chatModel = chatModel;
        this.memoryMergeExecutor = memoryMergeExecutor;
        this.memoryMergeSystem = promptProperties.memoryMergeSystem();
        this.memoryProperties = memoryProperties;
    }

    /**
     * 异步触发记忆合并：<b>不阻塞对话主流程</b>。
     * <p>
     * 合并达到阈值时需要一次额外的 LLM 调用（秒级），若同步执行会挡在「用户看到回复」之前。
     * 因此本方法把检查与合并整体丢到 {@link #memoryMergeExecutor 专用线程池} 执行，调用方（回复已返回/已推送后）立即返回。
     * 同一会话已有合并在进行中时直接跳过本次。
     *
     * @param conversationId 会话 ID
     */
    public void maybeMergeMemoryAsync(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        if (!merging.add(conversationId)) {
            log.debug("记忆合并已在进行中，跳过本次：会话={}", conversationId);
            return;
        }
        try {
            // 专用线程池 + CompletableFuture：runAsync 提交，whenComplete 统一收尾
            // （释放合并占位 + 兜底记录未预期异常），比手写 try/finally 更声明式。
            // maybeMergeMemory 内部已 catch 业务异常，这里仅兜底，正常路径 ex 为 null。
            CompletableFuture.runAsync(() -> maybeMergeMemory(conversationId), memoryMergeExecutor)
                    .whenComplete((v, ex) -> {
                        merging.remove(conversationId);
                        if (ex != null) {
                            log.warn("记忆合并任务异常：会话={}，原因={}", conversationId, ex.getMessage());
                        }
                    });
        } catch (RejectedExecutionException e) {
            // 提交被线程池拒绝（队列满/已关闭）：runAsync 的 execute 会同步抛出，此时任务未进队、
            // whenComplete 永远不会触发，必须在这里释放 merging 占位，否则该会话后续轮次永远跳过合并。
            // 本次不合并可接受：下一轮对话结束会再检查。
            merging.remove(conversationId);
            log.warn("记忆合并任务提交被拒绝，本次跳过：会话={}，原因={}", conversationId, e.getMessage());
        }
    }

    /**
     * 对话结束后检查：历史是否溢出窗口达到合并阈值，若是则触发一次 LLM 记忆合并。
     * 本轮消息已由 advisor 通过 ChatMemory 落库，这里基于全量历史计算窗口起点，
     * 窗口之外的旧消息由「滚动摘要 + 用户核心信息」接管，之后不再进入上下文。
     */
    public void maybeMergeMemory(String conversationId) {
        try {
            List<ChatMessage> history = conversationService.getHistory(conversationId);
            int windowStart = DbChatMemory.computeWindowStart(history, memoryProperties);
            if (windowStart <= 0) {
                return; // 全部历史都在 token 预算内，无需合并
            }
            Conversation conv = conversationService.getConversation(conversationId);
            if (conv == null) return;
            int alreadySummarized = conv.getSummarizedCount() == null ? 0 : conv.getSummarizedCount();
            int newOverflow = windowStart - alreadySummarized;
            if (newOverflow < SUMMARY_BATCH_SIZE) {
                if (newOverflow > 0) {
                    log.debug("记忆待合并：{} 条溢出消息未合并（阈值={}）", newOverflow, SUMMARY_BATCH_SIZE);
                }
                return;
            }
            if (alreadySummarized >= windowStart) {
                log.warn("记忆状态不一致：已覆盖条数={} >= 窗口起点={}，跳过合并", alreadySummarized, windowStart);
                return;
            }
            List<ChatMessage> delta = history.subList(alreadySummarized, windowStart);
            SummaryResult sr = summarize(conv.getSummary(), conv.getCoreFacts(), delta);
            conversationService.updateMemory(conversationId, sr.summary, sr.coreFacts, windowStart);
            log.info("记忆合并完成：合并 {} 条，已覆盖条数={}", delta.size(), windowStart);
        } catch (Exception e) {
            log.error("记忆合并失败：会话={}", conversationId, e);
        }
    }

    /**
     * 将"新增溢出的历史"与"已有摘要/关键事实"合并，一次 LLM 调用同时产出：
     * 更新后的滚动摘要 + 更新后的用户核心信息（关键事实清单）。
     *
     * @param existingSummary   之前的滚动摘要（首次为 {@code null}）
     * @param existingCoreFacts 已提取的用户核心信息（首次为 {@code null}）
     * @param newMessages       新溢出到记忆池的消息列表（按时间正序）
     * @return 合并结果（摘要 + 核心信息）；LLM 调用失败时两者均回退原值
     */
    private SummaryResult summarize(String existingSummary, String existingCoreFacts, List<ChatMessage> newMessages) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("【已有摘要】\n").append(existingSummary == null || existingSummary.isBlank() ? "（无）" : existingSummary).append("\n\n");
            sb.append("【已有关键事实】\n").append(existingCoreFacts == null || existingCoreFacts.isBlank() ? "（无）" : existingCoreFacts).append("\n\n");
            sb.append("【新增对话内容】\n");
            for (ChatMessage m : newMessages) {
                sb.append(m.getRole()).append("：").append(m.getContent()).append("\n");
            }
            log.debug("生成摘要：调用 LLM 合并 {} 条新消息", newMessages.size());
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(memoryMergeSystem),
                    new UserMessage(sb.toString()))));
            var generation = response.getResult();
            var assistantMessage = generation != null ? generation.getOutput() : null;
            String reply = assistantMessage != null ? assistantMessage.getText() : null;
            if (reply == null || reply.isBlank()) {
                log.warn("生成摘要：LLM 返回为空，回退到已有记忆");
                return new SummaryResult(existingSummary, existingCoreFacts);
            }
            SummaryResult sr = parseSummaryResult(reply, existingSummary, existingCoreFacts);
            log.info("生成摘要完成：摘要长度={}，关键事实长度={}",
                    sr.summary != null ? sr.summary.length() : 0,
                    sr.coreFacts != null ? sr.coreFacts.length() : 0);
            return sr;
        } catch (Exception e) {
            log.error("生成摘要失败：LLM 调用异常", e);
            return new SummaryResult(existingSummary, existingCoreFacts);   // 失败：保留旧记忆，不阻断对话
        }
    }

    /**
     * 解析 LLM 返回的「摘要 + 关键事实」两段式文本，容错处理格式偏差：
     * 找不到分隔符时整段视为摘要、关键事实保留旧值；关键事实为"无"时置为 null。
     */
    private SummaryResult parseSummaryResult(String reply, String existingSummary, String existingCoreFacts) {
        String text = reply.trim();
        int idx = text.indexOf("## 关键事实");
        if (idx < 0) idx = text.indexOf("关键事实");
        if (idx < 0) {
            return new SummaryResult(text, existingCoreFacts);
        }
        String summaryPart = text.substring(0, idx).replaceAll("^##?\\s*摘要\\s*", "").trim();
        String factsPart = text.substring(idx).replaceFirst("^##?\\s*关键事实\\s*", "").trim();
        String summary = summaryPart.isBlank() ? existingSummary : summaryPart;
        String coreFacts;
        if (factsPart.isBlank() || "无".equals(factsPart)) {
            coreFacts = null;
        } else {
            coreFacts = factsPart;
        }
        return new SummaryResult(summary, coreFacts);
    }

    /** LLM 一次记忆合并的产出：更新后的滚动摘要 + 用户核心信息。 */
    private static final class SummaryResult {
        final String summary;
        final String coreFacts;

        SummaryResult(String summary, String coreFacts) {
            this.summary = summary;
            this.coreFacts = coreFacts;
        }
    }
}
