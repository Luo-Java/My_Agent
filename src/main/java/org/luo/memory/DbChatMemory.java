package org.luo.memory;

import lombok.extern.slf4j.Slf4j;
import org.luo.config.MemoryProperties;
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
 * <p>
 * <b>窗口的三重约束</b>（由 {@link MemoryProperties} 统一配置，{@link #computeWindowStart} 实现）：
 * <ol>
 *   <li><b>预算</b>：从最新往前累计，超出 {@code recent-tokens} 的位置即窗口起点；</li>
 *   <li><b>下限</b>：至少保留最近 {@code min-keep-messages} 条——<b>关键防线</b>，
 *       没有它时「单条消息自己就超预算」会把起点推到列表末尾，窗口塌缩为空（见该函数注释）；</li>
 *   <li><b>单条截断</b>：超长单条在进上下文前截断，避免个别巨型消息独占整个预算。</li>
 * </ol>
 * 三者共同保证「窗口里永远至少有最近一组问答」，同时上下文长度有上界。
 */
@Slf4j
public class DbChatMemory implements ChatMemory {

    /**
     * SQL 层预取上限条数：先取最近这么多条，再由 {@link #computeWindowStart} 按 token 预算精确截断。
     * 单条消息通常远小于 4000 字符，200 条足以覆盖整个预算窗口；极端超长消息由内存截断兜底。
     * 好处：长会话（数百条）下每轮读取不再全表 selectList。
     */
    private static final int SQL_FETCH_LIMIT = 200;

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
     * 计算 token 预算窗口的起点索引。
     * <p>
     * 先按预算算出一个「自然起点」：从最新消息往前累计字符数（近似 token），
     * 累计值首次超过 {@code recent-tokens} 时，起点取该位置之后。
     * <p>
     * <b>然后必须夹一次下限</b>：起点最多只能到 {@code size - minKeepMessages}。
     * 因为上面那个循环有个静默失效点——<b>若最新一条消息自己就超过预算</b>
     * （例如上一轮是一张上万字符的数据表回复），第一次累加就越界，起点被推到 {@code size}，
     * 于是整段历史一条都不进上下文。用户看到的是模型「突然失忆」、把已知信息再问一遍，
     * 而日志里只有一条不起眼的 debug。夹下限后窗口至少保留最近 {@code minKeepMessages} 条，
     * 退化成「上下文略超预算」而不是「完全没有上下文」——前者只是贵一点，后者是功能坏了。
     * <p>
     * 该函数被 {@link #get} 与 {@code MemoryMergeService} 共用（同一个函数 + 同一份
     * {@link MemoryProperties}），两处必须一致，否则「被摘要掉的区间」会与实际上下文窗口错位，
     * 表现为记忆重复或静默丢失。函数值对 {@code size} 单调不减，保证随历史增长窗口起点只前进不后退
     * （合并侧的 {@code summarizedCount} 水位依赖这一性质）。
     *
     * @param history 按时间正序的消息列表（可空）
     * @param props   记忆窗口配置（预算 / 下限 / 截断）
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

    /** 把持久化的单条消息转为 Spring AI 的 Message（超长内容先按配置截断）。 */
    private Message toMessage(ChatMessage m) {
        String content = truncateOverlong(m.getContent());
        if ("assistant".equals(m.getRole())) {
            return new AssistantMessage(content);
        }
        return new UserMessage(content);
    }

    /**
     * 单条消息截断：长度超过 {@code max-message-chars} 时保留前 N 字符并追加截断标注。
     * <p>
     * 与 {@link #computeWindowStart} 的下限保护互补——下限保证「窗口不为空」，
     * 截断保证「窗口不会因为一条巨型消息而整体超预算」。配置为 0 则原样返回。
     */
    private String truncateOverlong(String content) {
        if (content == null || !props.truncateLongMessageOn() || content.length() <= props.maxMessageChars()) {
            return content;
        }
        log.debug("历史消息超长已截断：原长 {} 字符 → {} 字符", content.length(), props.maxMessageChars());
        return content.substring(0, props.maxMessageChars()) + TRUNCATED_SUFFIX;
    }
}
