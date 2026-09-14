package org.luo.agent;

import lombok.extern.slf4j.Slf4j;
import org.luo.properties.MemoryProperties;
import org.luo.properties.PromptProperties;
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
 * 会话记忆合并服务：对话结束后把「溢出窗口的旧消息」与已有摘要/关键事实合并，一次 LLM 调用同时产出
 * 更新后的滚动摘要与用户核心信息，回写 conversation 表。
 * <p>
 * 用裸 {@link ChatModel} 直接调用（不走 advisor，否则摘要指令会被当作对话消息写入记忆）；失败一律回退
 * 已有记忆，不打断主流程。
 * <p>
 * <b>数据保留契约</b>：只把窗口外消息<b>排除出主模型上下文</b>，<b>不删除 chat_message 行</b>。
 * {@link ParamFillingService} 的澄清重放依赖该语义——若改为物理归档旧消息，需同步其实现。
 */
@Slf4j
@Service
public class MemoryMergeService {

    /** 批量合并阈值：累计溢出这么多条才触发一次 LLM 合并（默认 6 ≈ 每 3 轮一次）；设为 1 即每轮合并。 */
    private static final int SUMMARY_BATCH_SIZE = 6;

    private final ConversationService conversationService;
    private final ChatModel chatModel;
    /** 记忆合并提示词（纯静态，外置）。 */
    private final String memoryMergeSystem;
    /** 记忆合并专用线程池：与对话主链路（Reactor boundedElastic）隔离，合并再慢也不挤占对话执行线程。 */
    private final Executor memoryMergeExecutor;
    /** 记忆窗口配置：与 {@code DbChatMemory} 共用同一份——两处参数不一致会让「被摘要区间」与窗口错位。 */
    private final MemoryProperties memoryProperties;

    /** 合并中的会话：同一会话互斥，避免连发消息时并发触发多次合并相互覆盖（跳过的那次下一轮会重查）。 */
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
     * 异步触发记忆合并，<b>不阻塞对话主流程</b>：检查与合并整体丢到专用线程池（秒级 LLM 调用若同步执行
     * 会挡在用户看到回复之前），调用方立即返回。同一会话已有合并在进行中则跳过本次。
     */
    public void maybeMergeMemoryAsync(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        if (!merging.add(conversationId)) {
            log.debug("记忆合并已在进行中，跳过本次：会话={}", conversationId);
            return;
        }
        try {
            // runAsync 提交、whenComplete 统一收尾（释放占位 + 兜底记录异常）；maybeMergeMemory 内部已 catch
            CompletableFuture.runAsync(() -> maybeMergeMemory(conversationId), memoryMergeExecutor)
                    .whenComplete((v, ex) -> {
                        merging.remove(conversationId);
                        if (ex != null) {
                            log.warn("记忆合并任务异常：会话={}，原因={}", conversationId, ex.getMessage());
                        }
                    });
        } catch (RejectedExecutionException e) {
            // 线程池拒绝时任务未入队、whenComplete 不会触发，必须在此释放占位，否则该会话后续永远跳过合并
            merging.remove(conversationId);
            log.warn("记忆合并任务提交被拒绝，本次跳过：会话={}，原因={}", conversationId, e.getMessage());
        }
    }

    /**
     * 检查历史是否溢出窗口达阈值，是则触发一次 LLM 合并。
     * <b>窗口口径必须与 {@link DbChatMemory} 同源</b>：取同一段「最近 {@value DbChatMemory#SQL_FETCH_LIMIT} 条」
     * 列表、调同一个 {@link DbChatMemory#computeWindowStart}，再换算回全量绝对索引
     * （{@code total - recent.size() + startInRecent}）。早期实现分别在「全量」与「截断」列表上算起点，
     * 历史超过预取上限后两把尺子错位，中间那段既不进摘要也不进上下文（模型「忘了前面几轮」）。
     */
    public void maybeMergeMemory(String conversationId) {
        try {
            Conversation conv = conversationService.getConversation(conversationId);
            if (conv == null) return;
            int total = conversationService.countMessages(conversationId);
            if (total <= 0) return;
            List<ChatMessage> recent = conversationService.getRecentHistory(conversationId,
                    DbChatMemory.SQL_FETCH_LIMIT);
            int startInRecent = DbChatMemory.computeWindowStart(recent, memoryProperties);
            int windowStart = total - recent.size() + startInRecent;   // 换算成全量历史索引
            if (windowStart <= 0) {
                return; // 全部历史都在 token 预算内，无需合并
            }
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
            // 只取「尚未摘要的那一段」（按全量索引区间开窗），长会话下不再整段历史读进内存
            List<ChatMessage> delta = conversationService.getMessagesRange(
                    conversationId, alreadySummarized, windowStart);
            SummaryResult sr = summarize(conv.getSummary(), conv.getCoreFacts(), delta);
            conversationService.updateMemory(conversationId, sr.summary, sr.coreFacts, windowStart);
            log.info("记忆合并完成：合并 {} 条，已覆盖条数={}", delta.size(), windowStart);
        } catch (Exception e) {
            log.error("记忆合并失败：会话={}", conversationId, e);
        }
    }

    /**
     * 把「新增溢出的历史」与「已有摘要/关键事实」合并，一次 LLM 调用同时产出新摘要与新核心信息；
     * LLM 失败时两者均回退原值。
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

    /** 解析「摘要 + 关键事实」两段式文本：找不到分隔符则整段视为摘要、关键事实保留旧值；"无" 置 null。 */
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
