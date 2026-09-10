package org.luo.memory;

import com.openai.models.realtime.SessionCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.luo.entity.ChatMessage;
import org.luo.service.ConversationService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.luo.service.ChatService;

/**
 * 基于 MySQL 的 {@link ChatMemory} 实现：把 Spring AI 标准会话记忆接口接到现有 chat_message 表。
 *
 * <p>职责：
 * <ul>
 *   <li>{@link #add}：由 {@code MessageChatMemoryAdvisor} 在对话前后自动调用，把 user/assistant 消息落库</li>
 *   <li>{@link #get}：返回 token 预算窗口内的历史消息（更早的旧消息已由滚动摘要覆盖，不再进入上下文）</li>
 *   <li>{@link #clear}：清空指定会话的消息</li>
 * </ul>
 *
 * <p>说明：Spring AI 2.0 中 {@code MessageChatMemoryAdvisor} 会把 {@link #get} 返回的
 * 全部消息注入 prompt，因此「token 预算窗口」的收敛逻辑（原在 ChatService 手写）统一放到 {@link #get} 内。
 */
@Slf4j
public class DbChatMemory implements ChatMemory {

    /** 维持上下文的最大原文 token 预算（近似值，按字符数估算）；超出部分由滚动摘要覆盖。
     *  <p>数值考虑：数据类回复（如分布表/成绩表）可达 1~3k 字符，1000 会把上一轮整体切掉，
     *  导致模型看不到已有数据而重复查库；4000 可在成本可控前提下保住 1~2 轮数据型对话。 */
    static final int MAX_RECENT_TOKENS = 4000;

    /**
     * SQL 层预取上限条数：先取最近这么多条，再由 {@link #computeWindowStart} 按 token 预算精确截断。
     * 单条消息通常远小于 4000 字符，200 条足以覆盖整个预算窗口；极端超长消息由内存截断兜底。
     * 好处：长会话（数百条）下每轮读取不再全表 selectList。
     */
    private static final int SQL_FETCH_LIMIT = 200;

    private final ConversationService conversationService;

    public DbChatMemory(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (conversationId == null || conversationId.isBlank()
                || messages == null || messages.isEmpty()) {
            return;
        }
        try {
            LocalDateTime base = LocalDateTime.now();
            List<ChatMessage> records = new ArrayList<>(messages.size());
            long nanoOffset = 0;
            for (Message m : messages) {
                String role = toRole(m);
                if (role == null) {
                    continue; // system / tool 等不进对话历史
                }
                ChatMessage cm = new ChatMessage();
                cm.setConversationId(conversationId);
                cm.setRole(role);
                String text = m.getText();
                cm.setContent(text == null ? "" : text);
                // 同一批内 created_at 依次递增，保证顺序稳定
                cm.setCreatedAt(base.plusNanos(nanoOffset));
                nanoOffset += 1_000;
                records.add(cm);
            }
            conversationService.saveMessages(records);
            log.debug("记忆写入：会话={}，落库 {} 条消息", conversationId, records.size());
        } catch (Exception e) {
            // 记忆存储故障不影响主对话流程
            log.error("记忆写入失败：会话={}", conversationId, e);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        try {
            // SQL 层先取最近 N 条（走 (conversation_id, created_at) 索引），再按 token 预算精确截断，
            // 避免长会话每轮全量 selectList 后再在内存里丢窗口。
            List<ChatMessage> history = conversationService.getRecentHistory(conversationId, SQL_FETCH_LIMIT);
            int windowStart = computeWindowStart(history);
            List<Message> result = new ArrayList<>(history.size() - windowStart);
            for (int i = windowStart; i < history.size(); i++) {
                result.add(toMessage(history.get(i)));
            }
            log.debug("记忆读取：会话={}，共 {} 条，窗口=[{},{}))",
                    conversationId, history.size(), windowStart, history.size());
            return result;
        } catch (Exception e) {
            log.error("记忆读取失败：会话={}", conversationId, e);
            return List.of();
        }
    }

    @Override
    public void clear(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        try {
            conversationService.clearMessages(conversationId);
            log.info("记忆清空：会话={}", conversationId);
        } catch (Exception e) {
            log.error("记忆清空失败：会话={}", conversationId, e);
        }
    }

    /**
     * 计算 token 预算窗口的起点索引：从最新消息往前累计 token（字符数近似），
     * 累计值首次超过 {@link #MAX_RECENT_TOKENS} 时，窗口起点为该位置之后。
     * 返回 0 表示全部历史都在预算内（窗口覆盖全部）。
     */
    public static int computeWindowStart(List<ChatMessage> history) {
        int tokens = 0;
        int start = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            String content = history.get(i).getContent();
            tokens += content == null ? 0 : content.length();
            if (tokens > MAX_RECENT_TOKENS) {
                start = i + 1;
                break;
            }
        }
        return start;
    }

    /** 将 Spring AI Message 映射为数据库 role（user/assistant）；其他类型返回 null 表示跳过。 */
    private String toRole(Message m) {
        if (m instanceof UserMessage) {
            return "user";
        }
        if (m instanceof AssistantMessage) {
            return "assistant";
        }
        return null;
    }

    /** 把持久化的单条消息转为 Spring AI 的 Message。 */
    private Message toMessage(ChatMessage m) {
        if ("assistant".equals(m.getRole())) {
            return new AssistantMessage(m.getContent());
        }
        return new UserMessage(m.getContent());
    }
}
