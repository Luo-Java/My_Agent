package org.luo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.entity.ChatMessage;
import org.luo.entity.Conversation;
import org.luo.mapper.ChatMessageMapper;
import org.luo.mapper.ConversationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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

    /**
     * 创建新会话，可绑定某个智能体（其名称作为会话初始标题）。
     *
     * @param agentId   绑定的智能体 ID；null 表示使用默认助手
     * @param agentName 智能体名称，用于设置会话初始标题
     * @return 新建的会话记录
     */
    @Transactional
    public Conversation createConversation(Long agentId, String agentName) {
        log.info("创建会话：agentId={}", agentId);
        Conversation c = new Conversation();
        c.setId(UUID.randomUUID().toString());
        if (agentId != null) {
            c.setAgentId(agentId);
            c.setAgentBindSource("EXPLICIT");   // 用户显式绑定：保持粘住，不因话题切换解绑
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

    /**
     * 创建新会话（默认助手，不绑定智能体）。
     *
     * @return 新建的会话记录
     */
    public Conversation createConversation() {
        return createConversation(null, null);
    }

    /**
     * 重命名会话（手动修改标题）。
     *
     * @param conversationId 会话 ID
     * @param title          新标题（自动 trim）
     */
    @Transactional
    public void renameConversation(String conversationId, String title) {
        if (conversationId == null || conversationId.isBlank()) return;
        Conversation c = conversationMapper.selectById(conversationId);
        if (c == null) return;
        log.info("重命名会话：id={}，新标题={}", conversationId, title != null ? title.trim() : "(null)");
        c.setTitle(title == null ? "" : title.trim());
        conversationMapper.updateById(c);
    }

    /**
     * 删除会话及其全部消息（级联删除 chat_message）。
     *
     * @param conversationId 要删除的会话 ID
     */
    @Transactional
    public void deleteConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        log.info("删除会话：id={}", conversationId);
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId);
        chatMessageMapper.delete(qw);
        conversationMapper.deleteById(conversationId);
    }

    /**
     * 列出会话，按最近更新时间倒序。
     *
     * @return 会话列表
     */
    public List<Conversation> listConversations() {
        QueryWrapper<Conversation> qw = new QueryWrapper<>();
        qw.orderByDesc("updated_at");
        return conversationMapper.selectList(qw);
    }

    /**
     * 读取某会话的历史消息，按时间正序。
     *
     * @param conversationId 会话 ID
     * @return 该会话的全部历史消息（从第一条到最后一条）
     */
    public List<ChatMessage> getHistory(String conversationId) {
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId).orderByAsc("created_at");
        return chatMessageMapper.selectList(qw);
    }

    /**
     * 查询指定会话的记录。
     *
     * @param conversationId 会话 ID
     * @return 会话对象，不存在则返回 null
     */
    public Conversation getConversation(String conversationId) {
        return conversationMapper.selectById(conversationId);
    }

    /**
     * 若该会话尚不存在则补建一条记录（默认助手，无智能体绑定），并返回会话对象。
     * 已存在则直接返回，避免调用方再查一次库。
     * 用于确保每次 chat/stream 调用前会话已就绪，并复用本次查询结果。
     *
     * @param conversationId 会话 ID
     * @return 会话对象；conversationId 为空时返回 null
     */
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

    /**
     * 批量落库消息（由 DbChatMemory 在 ChatMemory.add 时调用）。
     * 同一批内的 created_at 依次递增（由调用方填充），保证顺序稳定。
     *
     * @param messages 待落库的消息列表（已含 conversationId / role / content）
     */
    @Transactional
    public void saveMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return;
        log.debug("落库消息：{} 条", messages.size());
        for (ChatMessage m : messages) {
            chatMessageMapper.insert(m);
        }
    }

    /**
     * 清空指定会话的全部消息（保留会话本身），用于 ChatMemory.clear。
     *
     * @param conversationId 会话 ID
     */
    @Transactional
    public void clearMessages(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        log.info("清空会话消息：id={}", conversationId);
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId);
        chatMessageMapper.delete(qw);
    }

    /**
     * 更新会话的更新时间；未手动命名的会话用首条用户消息作为会话标题。
     *
     * @param conversationId 会话 ID
     * @param userText       当前用户输入，首次时截取前 20 字作标题
     */
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

    /**
     * 落库一次「追问交互」：用户本轮输入 + 生成的追问文本（user + assistant 两条）。
     * 由 ChatService 在确认进入追问分支、且非话题切换时调用，确保每轮只落库一次。
     *
     * @param conversationId 会话 ID
     * @param userText       用户本轮输入
     * @param assistantText  生成的追问文本（带 CLARIFY_PREFIX 前缀）
     */
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
        a.setCreatedAt(now.plusNanos(1000));   // 保证 user 早于 assistant 的顺序
        chatMessageMapper.insert(u);
        chatMessageMapper.insert(a);
    }

    /**
     * 中途绑定智能体到会话（用于追问流程：路由命中某带 paramSchema 的 agent 并进入追问时，
     * 把该 agent 记到会话上，使下一轮用户的追问回答能复用同一 agent 继续补全参数，而不被当作
     * 全新问题重新路由导致 agent 丢失）。
     *
     * @param conversationId 会话 ID
     * @param agentId        要绑定的智能体 ID（null 时忽略）
     */
    @Transactional
    public void bindAgent(String conversationId, Long agentId) {
        if (conversationId == null || conversationId.isBlank() || agentId == null) return;
        Conversation c = conversationMapper.selectById(conversationId);
        if (c == null) return;
        c.setAgentId(agentId);
        c.setAgentBindSource("CLARIFY");   // 追问流程临时绑定：用户转向别的话题时由 ChatService 自动解绑
        conversationMapper.updateById(c);
        log.info("绑定智能体：会话={}，agentId={}，来源=CLARIFY", conversationId, agentId);
    }

    /**
     * 解绑会话上的智能体（参数齐全、给出正式回答后调用，使后续轮次恢复正常智能路由，
     * 不被追问期间临时绑定的 agent 长期粘住）。显式绑定（用户主动选 agent）的不应调用此方法。
     *
     * @param conversationId 会话 ID
     */
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

    /**
     * 写入会话的长期记忆：滚动摘要 + 用户核心信息 + 已覆盖条数（一次 UPDATE）。
     * 当历史超出窗口时，ChatService 会批量调用此方法持久化。
     *
     * @param conversationId  会话 ID
     * @param summary         滚动摘要文本
     * @param coreFacts       用户核心信息（关键事实清单）；无则传 null
     * @param summarizedCount 已被摘要覆盖的最旧消息条数（按时间正序索引）
     */
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
