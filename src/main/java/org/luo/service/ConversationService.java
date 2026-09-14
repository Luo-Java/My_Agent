package org.luo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.constant.AgentBindSource;
import org.luo.entity.ChatMessage;
import org.luo.entity.Conversation;
import org.luo.exception.AiBusinessException;
import org.luo.exception.AiErrorCode;
import org.luo.mapper.ChatMessageMapper;
import org.luo.mapper.ConversationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.luo.entity.Agent;
import org.luo.memory.DbChatMemory;

/**
 * 会话与消息业务服务：负责 conversation 与 chat_message 两张表的持久化。
 */
@Slf4j
@Service
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;

    public ConversationService(ConversationMapper conversationMapper, ChatMessageMapper chatMessageMapper) {
        this.conversationMapper = conversationMapper;
        this.chatMessageMapper = chatMessageMapper;
    }

    /** 创建新会话，可绑定智能体（其名称作为初始标题）。 */
    @Transactional
    public Conversation createConversation(Long agentId, String agentName) {
        log.info("创建会话：agentId={}", agentId);
        Conversation c = new Conversation();
        c.setId(UUID.randomUUID().toString());
        if (agentId != null) {
            c.setAgentId(agentId);
            c.setAgentBindSource(AgentBindSource.EXPLICIT);   // 用户显式绑定：保持粘住，不因话题切换解绑
            c.setTitle(agentName != null ? agentName : "新对话");
        } else {
            c.setTitle("新对话");
        }
        LocalDateTime now = LocalDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        conversationMapper.insert(c);
        return c;
    }

    /** 创建新会话（默认助手，不绑定智能体）。 */
    public Conversation createConversation() {
        return createConversation(null, null);
    }

    /** 创建新会话：可绑定智能体或标记为规划模式（planner 优先，与 agentId 互斥）。 */
    @Transactional
    public Conversation createConversation(Long agentId, String agentName, boolean planner) {
        log.info("创建会话：agentId={}，planner={}", agentId, planner);
        Conversation c = new Conversation();
        c.setId(UUID.randomUUID().toString());
        if (planner) {
            // 规划模式会话：不参与 Agent 路由
            c.setPlanner(true);
            c.setTitle("🧭 智能规划");
        } else if (agentId != null) {
            c.setAgentId(agentId);
            c.setAgentBindSource(AgentBindSource.EXPLICIT);   // 用户显式绑定：保持粘住，不因话题切换解绑
            c.setTitle(agentName != null ? agentName : "新对话");
        } else {
            c.setTitle("新对话");
        }
        LocalDateTime now = LocalDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        conversationMapper.insert(c);
        return c;
    }

    /** 创建规划模式会话。 */
    @Transactional
    public Conversation createPlannerConversation() {
        return createConversation(null, null, true);
    }

    /** 重命名会话（标题自动 trim）。 */
    @Transactional
    public void renameConversation(String conversationId, String title) {
        if (conversationId == null || conversationId.isBlank()) return;
        log.info("重命名会话：id={}，新标题={}", conversationId, title != null ? title.trim() : "(null)");
        // 单趟 UPDATE：无需先查库（会话不存在时更新 0 行无副作用）
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .set(Conversation::getTitle, title == null ? "" : title.trim()));
    }

    /** 更新 planner 单列（不覆盖其他内存态字段）。 */
    public void updatePlanner(String conversationId, boolean planner) {
        if (conversationId == null || conversationId.isBlank()) return;
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .set(Conversation::getPlanner, planner));
        log.debug("更新会话规划标记：id={}，planner={}", conversationId, planner);
    }

    /**
     * 前端「🧭 智能规划」开关写回（仅 planner 单列）。<b>planner 与 agentId 互斥</b>：
     * 开启规划且会话已绑定智能体时抛 {@link AiBusinessException}。
     */
    @Transactional
    public void updatePlannerSwitch(String conversationId, Boolean enabled) {
        if (conversationId == null || conversationId.isBlank()) return;
        boolean on = Boolean.TRUE.equals(enabled);
        if (on) {
            Conversation c = conversationMapper.selectById(conversationId);
            if (c == null) return;   // 不存在的会话静默（更新 0 行无副作用）
            if (c.getAgentId() != null) {
                throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                        "绑定智能体的会话不支持规划模式（planner 与 agentId 互斥）");
            }
        }
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .set(Conversation::getPlanner, on));
        log.debug("更新会话规划开关：id={}，enabled={}", conversationId, on);
    }

    /** 前端「📚 RAG」开关写回（仅 rag_enabled 单列）。不校验库是否存在：库被删则检索侧自动降级为不带资料。 */
    public void updateRagEnabled(String conversationId, Boolean enabled) {
        if (conversationId == null || conversationId.isBlank()) return;
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .set(Conversation::getRagEnabled, Boolean.TRUE.equals(enabled)));
        log.debug("更新会话 RAG 开关：id={}，enabled={}", conversationId, Boolean.TRUE.equals(enabled));
    }

    /** 删除会话及其全部消息。 */
    @Transactional
    public void deleteConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        log.info("删除会话：id={}", conversationId);
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId);
        chatMessageMapper.delete(qw);
        conversationMapper.deleteById(conversationId);
    }

    /** 列出会话，按最近更新时间倒序。 */
    public List<Conversation> listConversations() {
        QueryWrapper<Conversation> qw = new QueryWrapper<>();
        qw.orderByDesc("updated_at");
        return conversationMapper.selectList(qw);
    }

    /** 会话内当前最大消息 ID（无消息返回 null）：落库前的「水位」。 */
    public Long maxMessageId(String conversationId) {
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId).orderByDesc("id").last("LIMIT 1");
        ChatMessage last = chatMessageMapper.selectOne(qw);
        return last == null ? null : last.getId();
    }

    /**
     * 附件元数据写到「{@code id > afterId} 的最新一条用户消息」。仅历史回看用、不参与记忆读取；
     * {@code afterId}（见 {@link #maxMessageId}）防止本轮失败时误挂到历史消息。
     */
    @Transactional
    public void attachToLatestUserMessage(String conversationId, Long afterId, String attachmentsJson) {
        if (conversationId == null || conversationId.isBlank()
                || attachmentsJson == null || attachmentsJson.isBlank()) {
            return;
        }
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId).eq("role", "user");
        if (afterId != null) qw.gt("id", afterId);
        qw.orderByDesc("id").last("LIMIT 1");
        ChatMessage last = chatMessageMapper.selectOne(qw);
        if (last == null) {
            log.warn("附件元数据未找到可挂载的用户消息：会话={}", conversationId);
            return;
        }
        chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getId, last.getId())
                .set(ChatMessage::getAttachmentsJson, attachmentsJson));
        log.debug("附件元数据写入：会话={}，消息={}", conversationId, last.getId());
    }

    /** RAG 引用写到「{@code id > afterId} 的最新一条助手消息」，与附件元数据完全对称，仅供前端渲染 [n] 角标。 */
    @Transactional
    public void attachCitationsToLatestAssistantMessage(String conversationId, Long afterId, String citationsJson) {
        if (conversationId == null || conversationId.isBlank()
                || citationsJson == null || citationsJson.isBlank()) {
            return;
        }
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId).eq("role", "assistant");
        if (afterId != null) qw.gt("id", afterId);
        qw.orderByDesc("id").last("LIMIT 1");
        ChatMessage last = chatMessageMapper.selectOne(qw);
        if (last == null) {
            log.warn("引用来源未找到可挂载的助手消息：会话={}", conversationId);
            return;
        }
        chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getId, last.getId())
                .set(ChatMessage::getCitationsJson, citationsJson));
        log.debug("引用来源写入：会话={}，消息={}", conversationId, last.getId());
    }

    /**
     * 读取会话全部历史，按时间正序。<b>排序必须带 id tiebreaker</b>：{@code created_at} 是秒级 DATETIME，
     * 同一轮 user/assistant 时间相同，只按它排序时顺序取决于执行计划，改走 filesort 就会错序。
     */
    public List<ChatMessage> getHistory(String conversationId) {
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId).orderByAsc("created_at").orderByAsc("id");
        return chatMessageMapper.selectList(qw);
    }

    /**
     * 读取最近 N 条历史（时间正序）：先按 {@code created_at, id} 倒序取 N 条再反转，供记忆窗口读取，
     * 避免长会话每轮全量加载。走索引需 {@code (conversation_id, created_at)} 复合索引（schema.sql 已建）。
     *
     * @param limit &lt;= 0 时退化为全量查询
     */
    public List<ChatMessage> getRecentHistory(String conversationId, int limit) {
        if (limit <= 0) {
            return getHistory(conversationId);
        }
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId)
                .orderByDesc("created_at").orderByDesc("id")
                .last("LIMIT " + limit);   // limit 为受控 int 参数，无注入风险
        List<ChatMessage> list = chatMessageMapper.selectList(qw);
        java.util.Collections.reverse(list); // 倒序取回后恢复正序
        return list;
    }

    /**
     * 统计消息总条数（只走 count）。供 {@code MemoryMergeService} 把窗口起点换算成绝对索引：
     * 摘要侧与注入侧必须基于同一段列表，否则会出现「既不摘要也不注入」的记忆空洞。
     */
    public int countMessages(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return 0;
        Long n = chatMessageMapper.selectCount(
                new QueryWrapper<ChatMessage>().eq("conversation_id", conversationId));
        return n == null ? 0 : n.intValue();
    }

    /**
     * 取时间正序下的第 {@code [fromIndex, toIndex)} 条消息（只读「尚未摘要的那一段」）。
     * <b>依赖契约「chat_message 不做物理删除」</b>——有删除则索引区间漂移、摘要水位失准。排序同 {@link #getHistory}。
     */
    public List<ChatMessage> getMessagesRange(String conversationId, int fromIndex, int toIndex) {
        if (conversationId == null || conversationId.isBlank() || toIndex <= fromIndex || fromIndex < 0) {
            return List.of();
        }
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId)
                .orderByAsc("created_at").orderByAsc("id")
                .last("LIMIT " + fromIndex + ", " + (toIndex - fromIndex));   // 受控 int 参数，无注入风险
        return chatMessageMapper.selectList(qw);
    }

    /** 查询会话记录，不存在返回 null。 */
    public Conversation getConversation(String conversationId) {
        return conversationMapper.selectById(conversationId);
    }

    /** 会话不存在则补建（默认助手）并返回，存在则直接返回，供 chat/stream 复用本次查询结果。 */
    @Transactional
    public Conversation ensureConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return null;
        Conversation c = conversationMapper.selectById(conversationId);
        if (c == null) {
            log.info("确保会话存在：新建会话 {}", conversationId);
            c = new Conversation();
            c.setId(conversationId);
            c.setTitle("新对话");
            LocalDateTime now = LocalDateTime.now();
            c.setCreatedAt(now);
            c.setUpdatedAt(now);
            conversationMapper.insert(c);
        }
        return c;
    }

    /** 批量落库消息（DbChatMemory 在 ChatMemory.add 时调用）。 */
    @Transactional
    public void saveMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return;
        log.debug("落库消息：{} 条", messages.size());
        for (ChatMessage m : messages) {
            chatMessageMapper.insert(m);
        }
    }

    /**
     * 清空会话全部消息（保留会话本身），用于 ChatMemory.clear。
     * <b>必须与摘要水位一起归零</b>：否则 summary 指向不存在的历史，且打破 {@link #getMessagesRange} 的索引契约。
     */
    @Transactional
    public void clearMessages(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        log.info("清空会话消息并重置摘要水位：id={}", conversationId);
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId);
        chatMessageMapper.delete(qw);
        // 摘要与水位同步归零
        Conversation c = conversationMapper.selectById(conversationId);
        if (c != null) {
            c.setSummary(null);
            c.setCoreFacts(null);
            c.setSummarizedCount(0);
            conversationMapper.updateById(c);
        }
    }

    /** 更新时间戳；标题仍为「新对话」时用首条用户消息前 20 字自动命名（需先读原标题，故走 select+update）。 */
    public void touchConversation(String conversationId, String userText) {
        Conversation c = conversationMapper.selectById(conversationId);
        if (c == null) return;
        boolean autoRenamed = false;
        if ("新对话".equals(c.getTitle()) && userText != null && !userText.isBlank()) {
            c.setTitle(userText.length() > 20 ? userText.substring(0, 20) : userText);
            autoRenamed = true;
        }
        c.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(c);
        if (autoRenamed) {
            log.info("更新会话：自动命名标题为 '{}'", c.getTitle());
        }
    }

    /** 落库一次追问交互（user + assistant 两条）。 */
    @Transactional
    public void saveClarifyExchange(String conversationId, String userText, String assistantText) {
        if (conversationId == null || conversationId.isBlank()) return;
        LocalDateTime now = LocalDateTime.now();
        ChatMessage u = new ChatMessage();
        u.setConversationId(conversationId);
        u.setRole("user");
        u.setContent(userText);
        u.setCreatedAt(now);
        ChatMessage a = new ChatMessage();
        a.setConversationId(conversationId);
        a.setRole("assistant");
        a.setContent(assistantText);
        // 与 user 共用同一时间：created_at 是秒级 DATETIME，纳秒偏移会被静默截断；
        // user 早于 assistant 的顺序由自增主键 id 兜底（读取一律 ORDER BY created_at, id）。
        a.setCreatedAt(now);
        chatMessageMapper.insert(u);
        chatMessageMapper.insert(a);
    }

    /** 追问流程临时绑定智能体（来源 CLARIFY），使下一轮回答能复用同一 agent 继续补参。 */
    @Transactional
    public void bindAgent(String conversationId, Long agentId) {
        if (conversationId == null || conversationId.isBlank() || agentId == null) return;
        // 单趟 UPDATE：无需先查库（会话不存在时更新 0 行无副作用）
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .set(Conversation::getAgentId, agentId)
                .set(Conversation::getAgentBindSource, AgentBindSource.CLARIFY));  // 追问流程临时绑定：用户转向别的话题时由 ChatService 自动解绑
        log.info("绑定智能体：会话={}，agentId={}，来源=CLARIFY", conversationId, agentId);
    }

    /** 解绑追问期临时绑定的智能体（给正式回答后调用）；显式绑定不应调用。 */
    @Transactional
    public void unbindAgent(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        Conversation c = conversationMapper.selectById(conversationId);
        if (c == null || c.getAgentId() == null) return;
        // 一并清除来源标记，避免残留 EXPLICIT/CLARIFY 指向空绑定
        LambdaUpdateWrapper<Conversation> wrapper = new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .set(Conversation::getAgentId, null)
                .set(Conversation::getAgentBindSource, null);
        conversationMapper.update(null, wrapper);
        log.info("解绑智能体：会话={}", conversationId);
    }

    /** 写入长期记忆：滚动摘要 + 核心信息 + 已覆盖条数（一次 UPDATE）。 */
    @Transactional
    public void updateMemory(String conversationId, String summary, String coreFacts, int summarizedCount) {
        log.info("更新长期记忆：会话={}，已覆盖条数={}，摘要长度={}，关键事实长度={}",
                conversationId, summarizedCount,
                summary != null ? summary.length() : 0,
                coreFacts != null ? coreFacts.length() : 0);
        Conversation c = conversationMapper.selectById(conversationId);
        if (c == null) return;
        c.setSummary(summary);
        c.setCoreFacts(coreFacts);
        c.setSummarizedCount(summarizedCount);
        conversationMapper.updateById(c);
    }
}
