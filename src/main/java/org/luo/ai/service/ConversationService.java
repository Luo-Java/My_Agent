package org.luo.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.constant.AgentBindSource;
import org.luo.ai.dto.AttachmentDto;
import org.luo.ai.dto.KbCitation;
import org.luo.ai.entity.ChatMessage;
import org.luo.ai.entity.Conversation;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.ai.mapper.ChatMessageMapper;
import org.luo.ai.mapper.ConversationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 会话与消息业务服务：负责 conversation 与 chat_message 两张表的持久化，外加两项基于这两张表的派生输出——
 * 会话导出（Markdown 投影）与长期记忆的读写。
 * <p>
 * <b>会话按用户隔离</b>：所有对外方法都带 {@code userId} 并落到 SQL 条件上（列表过滤、改名/开关/删除带
 * {@code user_id} 条件、读取先校验归属），非本人会话一律按 404 处理 —— 不区分「不存在」与「不属于你」，
 * 避免他人用会话 ID 探测是否存在。用户身份由 Controller 在 HTTP 线程取出后传参进来，
 * <b>本类内不读 {@code AuthContext}</b>：流式对话的执行体跑在弹性线程上，ThreadLocal 在那里已失效。
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

    /** 创建新会话：可绑定智能体或标记为规划模式（planner 优先，与 agentId 互斥）。会话归属 {@code userId}。 */
    @Transactional
    public Conversation createConversation(Long agentId, String agentName, boolean planner, Long userId) {
        log.info("创建会话：agentId={}，planner={}，userId={}", agentId, planner, userId);
        Conversation c = new Conversation();
        c.setId(UUID.randomUUID().toString());
        c.setUserId(userId);
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

    /**
     * 创建新会话（普通/智能体会话），会话归属 {@code userId}。
     * <p>
     * <b>事务注解必须打在这一层</b>：它内部调用的四参重载虽然也标了 {@code @Transactional}，
     * 但那是<b>自调用</b>（走 this 而非 Spring 代理），注解根本不生效 —— 不在本方法上开事务，
     * 这条最常用的创建路径就是无事务的。将来若在此追加第二步写入（初始消息、审计等）会失去原子性。
     */
    @Transactional
    public Conversation createConversation(Long agentId, String agentName, Long userId) {
        return createConversation(agentId, agentName, false, userId);
    }

    /** 创建规划模式会话，会话归属 {@code userId}。 */
    @Transactional
    public Conversation createPlannerConversation(Long userId) {
        return createConversation(null, null, true, userId);
    }

    /**
     * 载入归属该用户的会话；不存在<b>或不属于该用户</b>时一律抛 404。
     * 两种情形共用同一提示，避免他人拿会话 ID 判断存在性（探测）。
     */
    public Conversation requireOwned(String conversationId, Long userId) {
        Conversation c = conversationId == null ? null : conversationMapper.selectById(conversationId);
        if (c == null || !Objects.equals(c.getUserId(), userId)) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "会话不存在");
        }
        return c;
    }

    /**
     * 校验访问权（不存在<b>不</b>报错，留给 {@link #ensureConversation} 创建）：仅当会话已存在且
     * 属于他人时抛 404。供对话入口在 HTTP 线程「前置挡掉越权」用 —— 流式接口一旦进入异步再抛异常，
     * 就只能退化成 SSE 里的一条 error 事件，拿不到真正的 404 状态码。
     */
    public void checkAccess(String conversationId, Long userId) {
        if (conversationId == null || conversationId.isBlank()) return;
        Conversation c = conversationMapper.selectById(conversationId);
        if (c != null && !Objects.equals(c.getUserId(), userId)) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "会话不存在");
        }
    }

    /** 重命名会话（标题自动 trim）。仅本人会话可改；不存在或非本人抛 404（一趟 UPDATE 影响 0 行即视为 404）。 */
    @Transactional
    public void renameConversation(String conversationId, String title, Long userId) {
        if (conversationId == null || conversationId.isBlank()) return;
        log.info("重命名会话：id={}，userId={}，新标题={}", conversationId, userId,
                title != null ? title.trim() : "(null)");
        int updated = conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)
                .set(Conversation::getTitle, title == null ? "" : title.trim()));
        if (updated == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "会话不存在");
        }
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
     * 开启规划且会话已绑定智能体时抛 {@link AiBusinessException}。仅本人会话可改。
     * <p>
     * <b>与「⚖ 评审」也互斥</b>（两者都是编排形态）：开启规划时顺手关掉评审，与
     * {@link #updateReviewEnabled} 对称 —— 否则用户会看到两个开关同时亮着，却只有一条链路真正生效。
     */
    @Transactional
    public void updatePlannerSwitch(String conversationId, Boolean enabled, Long userId) {
        if (conversationId == null || conversationId.isBlank()) return;
        boolean on = Boolean.TRUE.equals(enabled);
        Conversation c = requireOwned(conversationId, userId);
        if (on && c.getAgentId() != null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "绑定智能体的会话不支持规划模式（planner 与 agentId 互斥）");
        }
        LambdaUpdateWrapper<Conversation> uw = new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)
                .set(Conversation::getPlanner, on);
        if (on) {
            uw.set(Conversation::getReviewEnabled, false);   // 互斥：开规划则关评审
        }
        conversationMapper.update(null, uw);
        log.debug("更新会话规划开关：id={}，enabled={}", conversationId, on);
    }

    /** 前端「📚 RAG」开关写回（仅 rag_enabled 单列）。仅本人会话可改，非本人/不存在抛 404。不校验库是否存在：库被删则检索侧自动降级为不带资料。 */
    public void updateRagEnabled(String conversationId, Boolean enabled, Long userId) {
        if (conversationId == null || conversationId.isBlank()) return;
        int updated = conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)
                .set(Conversation::getRagEnabled, Boolean.TRUE.equals(enabled)));
        if (updated == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "会话不存在");
        }
        log.debug("更新会话 RAG 开关：id={}，enabled={}", conversationId, Boolean.TRUE.equals(enabled));
    }

    /**
     * 前端「先看计划」开关写回（仅 planner_confirm 单列）。仅本人会话可改，非本人/不存在抛 404。
     * <p>
     * 只对规划模式生效，但不在此校验 {@code planner} 当前是否为 true：开关本身是「偏好」而非「状态」，
     * 用户可以先把偏好打开、再开规划；真正生效点在 {@code PlannerRoundHandler}（读的是本列）。
     */
    public void updatePlannerConfirm(String conversationId, Boolean enabled, Long userId) {
        if (conversationId == null || conversationId.isBlank()) return;
        boolean on = Boolean.TRUE.equals(enabled);
        int updated = conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)
                .set(Conversation::getPlannerConfirm, on));
        if (updated == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "会话不存在");
        }
        log.debug("更新会话「先看计划」开关：id={}，enabled={}", conversationId, on);
    }

    /**
     * 前端「⚖ 评审」开关写回（仅 {@code review_enabled} 单列）。仅本人会话可改，非本人/不存在抛 404。
     * <p>
     * <b>与规划强制互斥</b>：两者都是「编排形态」（一个决定怎么拆、一个决定谁来答），同时开启会让
     * 「本轮到底走哪条链路」取决于读取顺序，行为不可预期。故开启评审时<b>顺手把 planner 关掉</b>，
     * 而不是报错让用户自己去关 —— 用户点「评审」的意图明确，替他做掉这一步比让他来回点两次更合理；
     * 反过来开规划时同样会关掉评审（见 {@link #updatePlannerSwitch}）。
     */
    @Transactional
    public void updateReviewEnabled(String conversationId, Boolean enabled, Long userId) {
        if (conversationId == null || conversationId.isBlank()) return;
        boolean on = Boolean.TRUE.equals(enabled);
        requireOwned(conversationId, userId);
        LambdaUpdateWrapper<Conversation> uw = new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)
                .set(Conversation::getReviewEnabled, on);
        if (on) {
            uw.set(Conversation::getPlanner, false);   // 互斥：开评审则关规划
        }
        conversationMapper.update(null, uw);
        log.debug("更新会话评审开关：id={}，enabled={}", conversationId, on);
    }

    /**
     * 前端「🔎 跨会话」开关写回（仅 {@code cross_session} 单列）。仅本人会话可改，非本人/不存在抛 404。
     * <p>
     * 与 RAG 开关同性质：只是「要不要检索」的偏好，不与任何其他开关互斥（跨会话召回与 RAG 资料
     * 是两类不同素材，可以并存）。召回不到内容时会明确播报「未回忆到相关历史」，不是静默无事发生。
     */
    public void updateCrossSession(String conversationId, Boolean enabled, Long userId) {
        if (conversationId == null || conversationId.isBlank()) return;
        boolean on = Boolean.TRUE.equals(enabled);
        int updated = conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)
                .set(Conversation::getCrossSession, on));
        if (updated == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "会话不存在");
        }
        log.debug("更新会话跨会话搜索开关：id={}，enabled={}", conversationId, on);
    }

    /** 删除会话及其全部消息。仅本人会话可删，非本人/不存在抛 404。 */
    @Transactional
    public void deleteConversation(String conversationId, Long userId) {
        if (conversationId == null || conversationId.isBlank()) return;
        requireOwned(conversationId, userId);
        log.info("删除会话：id={}，userId={}", conversationId, userId);
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId);
        chatMessageMapper.delete(qw);
        conversationMapper.deleteById(conversationId);
    }

    /** 列出该用户的会话，按最近更新时间倒序（他人会话不出现在列表中）。 */
    public List<Conversation> listConversations(Long userId) {
        QueryWrapper<Conversation> qw = new QueryWrapper<>();
        qw.eq("user_id", userId).orderByDesc("updated_at");
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

    /** 会话不存在则补建（默认助手，归属 {@code userId}）并返回，存在则校验归属后返回；他人会话抛 404，供 chat/stream 复用本次查询结果。 */
    @Transactional
    public Conversation ensureConversation(String conversationId, Long userId) {
        if (conversationId == null || conversationId.isBlank()) return null;
        Conversation c = conversationMapper.selectById(conversationId);
        if (c == null) {
            log.info("确保会话存在：新建会话 {}，userId={}", conversationId, userId);
            c = new Conversation();
            c.setId(conversationId);
            c.setUserId(userId);
            c.setTitle("新对话");
            LocalDateTime now = LocalDateTime.now();
            c.setCreatedAt(now);
            c.setUpdatedAt(now);
            conversationMapper.insert(c);
        } else if (!Objects.equals(c.getUserId(), userId)) {
            // 会话 ID 已存在但归属他人：按不存在处理，堵死「猜/拿别人的 ID 接手会话」这条路
            log.warn("拒绝接管他人会话：id={}，请求者={}，归属者={}", conversationId, userId, c.getUserId());
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "会话不存在");
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
        deleteMessagesAfter(conversationId, null);
    }

    /**
     * 截断会话历史：<b>只保留正序前 {@code keepCount} 条消息</b>，其余删除，并把长期记忆水位一起归零。
     * 服务前端「重新生成 / 编辑重发」——先截断到目标消息，再用既有 {@code /api/chat/stream} 通路重发，
     * 因此不需要第二套执行逻辑。
     * <p>
     * 用「保留条数」而非消息 id 作为定位：截断点由前端决定，而<b>刚发出去那一轮的消息前端手里没有 id</b>
     * （SSE 只回内容、不回主键），却天然知道它是第几条；id 单调递增保证两种表达完全等价，取更好拿的那个。
     * <p>
     * <b>水位必须同步归零</b>（见 {@link #deleteMessagesAfter}）：{@code summary} / {@code core_facts} /
     * {@code summarized_count} 指的是「按时间正序的前 N 条消息已压缩」，消息被物理删除后该索引立即失效。
     * 代价是下次记忆合并会从头重新摘要 —— 这正是截断后应有的语义（历史都变了，旧摘要在描述不存在的内容）。
     * <p>
     * 归属校验先做（不存在或非本人抛 404），与其余会话操作同一口径。
     *
     * @param keepCount 保留的消息条数；{@code <=0} = 全部删除，{@code >=} 实际条数 = 什么都不做
     * @return 实际删除的消息条数
     */
    @Transactional
    public long truncateTo(String conversationId, int keepCount, Long userId) {
        if (conversationId == null || conversationId.isBlank()) return 0L;
        requireOwned(conversationId, userId);
        List<ChatMessage> ordered = getHistory(conversationId);
        if (keepCount >= ordered.size()) return 0L;   // 没有要删的：连水位也不动（历史没变，摘要依然有效）
        Long keepUpToId = keepCount <= 0 ? null : ordered.get(keepCount - 1).getId();
        return deleteMessagesAfter(conversationId, keepUpToId);
    }

    /**
     * 删除 id 大于 {@code keepUpToId} 的消息并重置记忆水位（{@code keepUpToId} 为 null 时删全部）。
     * 这是「删消息」的唯一实现：{@link #clearMessages} 与 {@link #truncateTo} 都走它，
     * 保证「删消息」与「水位归零」永远成对发生 —— 分开写迟早会漏掉一边。
     */
    private long deleteMessagesAfter(String conversationId, Long keepUpToId) {
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId);
        if (keepUpToId != null) qw.gt("id", keepUpToId);
        Long removed = chatMessageMapper.selectCount(qw);
        long n = removed == null ? 0L : removed;
        if (n > 0) {
            chatMessageMapper.delete(qw);
            log.info("删除会话消息：id={}，保留至消息 {}，共删除 {} 条", conversationId, keepUpToId, n);
        }
        resetMemoryWatermark(conversationId);
        return n;
    }

    /** 摘要 / 核心事实 / 已摘要条数三列一齐清零（消息不动）。「删消息」与「重置记忆」共用的一步。 */
    private void resetMemoryWatermark(String conversationId) {
        Conversation c = conversationMapper.selectById(conversationId);
        if (c == null) return;
        c.setSummary(null);
        c.setCoreFacts(null);
        c.setSummarizedCount(0);
        conversationMapper.updateById(c);
    }

    /**
     * 覆写长期记忆的「摘要」与「核心事实」两列，<b>刻意不动水位 {@code summarized_count}</b>。
     * <p>
     * 水位是「已压缩到正序第几条」的执行游标，不是展示字段：用户手改它会让下次自动合并从错误位置继续
     * （重复摘要或整段漏摘要），且这种错位在现象上只表现为「模型记性变怪」，很难回溯。要重置游标请走
     * {@link #resetMemory}（它把三列一起归零，语义自洽）。
     * <p>
     * 空串 / 纯空白一律归一为 {@code null}：否则 {@code "  "} 会被当作「有摘要」注入 prompt，占用 token
     * 却不含信息。<b>两个字段都传 null 即等于「清空记忆内容但保留游标」。</b>
     */
    @Transactional
    public void updateMemoryFields(String conversationId, String summary, String coreFacts, Long userId) {
        requireOwned(conversationId, userId);
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)
                .set(Conversation::getSummary, blankToNull(summary))
                .set(Conversation::getCoreFacts, blankToNull(coreFacts)));
        log.info("手动更新长期记忆：会话={}，摘要长度={}，关键事实长度={}",
                conversationId, lengthOf(summary), lengthOf(coreFacts));
    }

    /**
     * 重置长期记忆：摘要 / 核心事实 / 水位三列一齐归零，<b>但不删消息</b>。
     * <p>
     * 效果是「忘掉此前压缩出的一切，下次超窗时从最早的未摘要消息重新摘要」—— 与 {@link #truncateAfter}
     * 的区别只在「消息保不保留」：这里是「记忆错了、消息没错」，那边是「消息本身就不该在」。
     */
    @Transactional
    public void resetMemory(String conversationId, Long userId) {
        requireOwned(conversationId, userId);
        resetMemoryWatermark(conversationId);
        log.info("重置长期记忆（三列归零，消息保留）：会话={}", conversationId);
    }

    /** 空白串归一为 null（区分「没填」与「填了空」——两者对 prompt 注入是同一件事）。 */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }

    private static int lengthOf(String s) {
        return s == null ? 0 : s.length();
    }

    /**
     * 把会话历史导出为 Markdown 文本（供页面「导出」下载为 .md）。
     * <p>
     * 刻意只列附件<b>文件名</b>而不落它们的 URL：导出的文件应当自包含，指向本机 {@code /files/**} 的链接
     * 换台机器就失效，留下反而是误导。RAG 引用则保留库名 / 文件名 / 相关度 —— 那是判断「这句话是文档里
     * 写的还是模型编的」的唯一依据，不能省。
     * <p>
     * 入参是<b>已通过归属校验</b>的会话实体（由 Controller 调 {@link #requireOwned} 后传入）：
     * 导出要取标题，若这里再按 id 查一次就是同一份数据查两遍。
     */
    public String exportMarkdown(Conversation c) {
        String conversationId = c.getId();
        List<ChatMessage> history = getHistory(conversationId);
        StringBuilder sb = new StringBuilder(1024);
        String title = (c.getTitle() == null || c.getTitle().isBlank()) ? "对话" : c.getTitle();
        sb.append("# ").append(title).append("\n\n");
        sb.append("> 会话 ID：").append(conversationId).append("  \n");
        sb.append("> 消息数：").append(history.size()).append("  \n");
        sb.append("> 导出时间：").append(EXPORT_TIME_FORMAT.format(LocalDateTime.now())).append("\n\n");
        for (ChatMessage m : history) {
            boolean isUser = "user".equals(m.getRole());
            sb.append("---\n\n## ").append(isUser ? "用户" : "助手").append("\n\n");
            String content = m.getContent();
            sb.append(content == null || content.isBlank() ? "（空）" : content).append("\n\n");
            for (AttachmentDto a : AttachmentDto.parse(m.getAttachmentsJson())) {
                sb.append("- 附件：").append(a.filename() == null || a.filename().isBlank() ? "（未命名）" : a.filename());
                if (a.size() != null) sb.append("（").append(a.size()).append(" 字节）");
                sb.append("\n");
            }
            List<KbCitation> cites = KbCitation.parse(m.getCitationsJson());
            if (!cites.isEmpty()) {
                if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') sb.append("\n");
                sb.append("\n**引用来源**\n\n");
                for (KbCitation ct : cites) {
                    sb.append(ct.index()).append(". ");
                    if (ct.kbName() != null && !ct.kbName().isBlank()) sb.append("[").append(ct.kbName()).append("] ");
                    sb.append(ct.source() == null || ct.source().isBlank() ? "（无来源标注）" : ct.source());
                    sb.append("（相关度 ").append(String.format("%.3f", ct.score())).append("）\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 导出时间格式：既用于正文头部元信息，也用于生成文件名里的日期段。 */
    private static final DateTimeFormatter EXPORT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 导出文件名（{@code 标题-YYYYMMDD.md}）。标题来自用户，可能含 {@code \ / : * ? " < > |} 与换行——
     * 这些字符在 Windows 上是非法文件名，或被浏览器当成路径分隔符，一律归一为下划线。
     */
    public String exportFileName(Conversation c) {
        String title = (c.getTitle() == null || c.getTitle().isBlank()) ? "对话" : c.getTitle();
        String safe = title.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").strip();
        if (safe.length() > 60) safe = safe.substring(0, 60);
        return safe + "-"
                + EXPORT_TIME_FORMAT.format(LocalDateTime.now()).substring(0, 10).replace("-", "")
                + ".md";
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
