package org.luo.memory;

import lombok.extern.slf4j.Slf4j;
import org.luo.properties.MemoryProperties;
import org.luo.entity.ChatMessage;
import org.luo.service.ConversationService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 MySQL 的 {@link ChatMemory} 实现：把 Spring AI 标准会话记忆接口接到 chat_message 表。
 * add / get / clear 分别落库、读取预算窗口内的历史、清空会话。
 * <p>
 * Spring AI 2.0 中 {@code MessageChatMemoryAdvisor} 会把 {@link #get} 返回的全部消息注入 prompt，
 * 故「token 预算窗口」的收敛逻辑统一放在 {@link #get} 内（原在 ChatService 手写）。
 * <p>
 * <b>窗口三重约束</b>（{@link MemoryProperties} 配置，{@link #computeWindowStart} 实现）：
 * ① 预算——从最新往前累计，超出 {@code recent-tokens} 即窗口起点；② 下限——至少保留最近
 * {@code min-keep-messages} 条（<b>关键防线</b>，见该函数注释）；③ 单条截断——超长单条进上下文前截断。
 * 三者共同保证「窗口里永远至少有最近一组问答」，同时上下文长度有上界。
 */
@Slf4j
public class DbChatMemory implements ChatMemory {

    /**
     * SQL 层预取上限条数：先取最近这么多条，再由 {@link #computeWindowStart} 按预算精确截断。
     * 单条通常远小于 4000 字符，200 条足以覆盖整个预算窗口；长会话每轮不再全表 selectList。
     * <p>
     * <b>必须 public</b>：{@code MemoryMergeService} 要用<b>同一个值</b>取<b>同一段列表</b>算窗口起点，
     * 两处一旦取不同列表，摘要区间与实际注入区间就会错位（历史超上限时裂出空洞 → 记忆静默丢失）。
     */
    public static final int SQL_FETCH_LIMIT = 200;

    /** 单条消息被截断时追加的标注，让模型知道此处不完整、不要据此断言。 */
    private static final String TRUNCATED_SUFFIX = "\n…（该条历史消息过长，已截断）";

    private final ConversationService conversationService;
    private final MemoryProperties props;

    public DbChatMemory(ConversationService conversationService, MemoryProperties props) {
        this.conversationService = conversationService;
        this.props = props;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (conversationId.isBlank() || messages.isEmpty()) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            List<ChatMessage> records = new ArrayList<>(messages.size());
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
                // 整批共用同一 created_at：created_at 是秒级 DATETIME（写入纳秒会被静默截断），
                // 同一轮的先后顺序由自增主键 id 兜底——所有读取路径按 (created_at, id) 排序。
                cm.setCreatedAt(now);
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
        if (conversationId.isBlank()) {
            return List.of();
        }
        try {
            // SQL 层先取最近 N 条（走 (conversation_id, created_at) 索引），再按预算精确截断
            List<ChatMessage> history = conversationService.getRecentHistory(conversationId, SQL_FETCH_LIMIT);
            int windowStart = computeWindowStart(history, props);
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
        if (conversationId.isBlank()) {
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
     * 计算 token 预算窗口的起点索引：先按预算从最新往前累计字符数（近似 token）得到「自然起点」，
     * <b>再夹一次下限</b>（起点最多到 {@code size - minKeepMessages}）。
     * <p>
     * 下限是<b>关键防线</b>：若最新一条消息自己就超过预算（如上一轮是上万字符的数据表回复），
     * 第一次累加就越界，起点被推到 {@code size} → 整段历史一条都不进上下文，用户看到模型「突然失忆」。
     * 夹下限后窗口至少保留最近 {@code minKeepMessages} 条，退化为「上下文略超预算」而非「完全没有上下文」。
     * <p>
     * 该函数被 {@link #get} 与 {@code MemoryMergeService} 共用（同一函数 + 同一份 {@link MemoryProperties}），
     * 两处必须一致，否则「被摘要掉的区间」会与实际窗口错位（记忆重复或静默丢失）。函数值对 {@code size}
     * 单调不减，保证随历史增长窗口起点只前进不后退（合并侧的 summarizedCount 水位依赖此性质）。
     *
     * @param history 按时间正序的消息列表（可空）
     * @return 窗口起点索引（含）；0 表示全部历史都在窗口内
     */
    public static int computeWindowStart(List<ChatMessage> history, MemoryProperties props) {
        if (history == null || history.isEmpty()) {
            return 0;
        }
        int budget = props.recentTokens();
        int tokens = 0;
        int start = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            String content = history.get(i).getContent();
            tokens += content == null ? 0 : content.length();
            if (tokens > budget) {
                start = i + 1;
                break;
            }
        }
        int floor = history.size() - props.minKeepMessages();
        return Math.max(0, Math.min(start, floor));
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

    /** 把持久化的单条消息转为 Spring AI Message（超长内容先按配置截断）。 */
    private Message toMessage(ChatMessage m) {
        String content = truncateOverlong(m.getContent());
        if ("assistant".equals(m.getRole())) {
            return new AssistantMessage(content);
        }
        return new UserMessage(content);
    }

    /**
     * 单条消息截断：超过 {@code max-message-chars} 时保留前 N 字符并追加截断标注（配置为 0 则原样返回）。
     * 与 {@link #computeWindowStart} 的下限保护互补——下限保证「窗口不为空」，截断保证「窗口不因一条巨型消息超预算」。
     */
    private String truncateOverlong(String content) {
        if (content == null || !props.truncateLongMessageOn() || content.length() <= props.maxMessageChars()) {
            return content;
        }
        log.debug("历史消息超长已截断：原长 {} 字符 → {} 字符", content.length(), props.maxMessageChars());
        return content.substring(0, props.maxMessageChars()) + TRUNCATED_SUFFIX;
    }
}
