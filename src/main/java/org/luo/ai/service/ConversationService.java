package org.luo.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.constant.AgentBindSource;
import org.luo.ai.dto.AttachmentDto;
import org.luo.ai.dto.ClarifyState;
import org.luo.ai.dto.KbCitation;
import org.luo.ai.dto.TurnBranch;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    /**
     * 长期事实条目：本类只在「作废压缩产物」（重置记忆 / 清空消息 / 切分支）与「删会话级联」时用到它，
     * 逐条增删改的主战场在 {@link ConversationFactService}。
     * <p>
     * 依赖方向是单向的 {@code ConversationService → ConversationFactService}（后者只依赖 mapper，
     * 归属校验放在 Controller），故不会成环。
     */
    private final ConversationFactService factService;

    public ConversationService(ConversationMapper conversationMapper, ChatMessageMapper chatMessageMapper,
                               ConversationFactService factService) {
        this.conversationMapper = conversationMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.factService = factService;
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

    /** 删除会话及其全部消息、长期事实条目。仅本人会话可删，非本人/不存在抛 404。 */
    @Transactional
    public void deleteConversation(String conversationId, Long userId) {
        if (conversationId == null || conversationId.isBlank()) return;
        requireOwned(conversationId, userId);
        log.info("删除会话：id={}，userId={}", conversationId, userId);
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId);
        chatMessageMapper.delete(qw);
        // 事实条目没有外键级联（DDL 刻意不建外键，与本项目其他表一致），必须显式清理，否则留下孤儿行
        factService.deleteAll(conversationId);
        conversationMapper.deleteById(conversationId);
    }

    /** 列出该用户的会话，按最近更新时间倒序（他人会话不出现在列表中）。 */
    public List<Conversation> listConversations(Long userId) {
        QueryWrapper<Conversation> qw = new QueryWrapper<>();
        qw.eq("user_id", userId).orderByDesc("updated_at");
        List<Conversation> list = conversationMapper.selectList(qw);
        for (Conversation c : list) decorateClarify(c);
        return list;
    }

    /**
     * 把内部的澄清状态 JSON 解析成两个对外展示字段（{@code clarifyAsked} / {@code clarifyMax}）。
     * <p>
     * 解析放在服务端而不是把原始 JSON 丢给前端：前者让「状态结构」只活在服务端，改结构不必同时改前端；
     * 后者等于把内部 JSON 变成对外契约。无追问 / 坏数据时两个字段留 null，前端 {@code v-if} 自然不渲染。
     */
    private static void decorateClarify(Conversation c) {
        ClarifyState st = ClarifyState.parse(c.getClarifyState());
        if (st != null && st.asked() > 0) {
            c.setClarifyAsked(st.asked());
            c.setClarifyMax(ClarifyState.MAX_ASKED);
        }
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
     * 只保留「当前生效版本」的过滤条件：未分叉的行（{@code turn_group_id} 为 NULL）恒可见，
     * 已分叉的轮只取 {@code turn_active = 1} 的那一版。
     * <p>
     * <b>四个读取口径必须共用它</b>——{@link #getHistory}（喂 prompt / 导出）、{@link #getRecentHistory}
     * （记忆窗口）、{@link #countMessages} 与 {@link #getMessagesRange}（摘要水位）。它们描述的是同一段
     * 历史：任一处漏掉，摘要水位就会按「含隐藏版本的物理行数」推进、而注入侧按「生效版本」取，两边错位
     * 的结果是中间一段「既不摘要也不注入」的记忆空洞。
     */
    private static void activeOnly(QueryWrapper<ChatMessage> qw) {
        qw.and(w -> w.isNull("turn_group_id").or().eq("turn_active", 1));
    }

    /**
     * <b>记忆口径</b>过滤：在 {@link #activeOnly} 之上再排除用户标记为「不参与记忆」的消息。
     * <p>
     * <b>只用于记忆侧三处</b>——{@link #getRecentHistory}（记忆窗口）、{@link #countMessages} 与
     * {@link #getMessagesRange}（摘要水位）。展示侧（{@link #getHistory}）刻意<b>不</b>加这一条：
     * 「不进记忆」不等于「删掉」，历史与导出仍应完整可见。
     * <p>
     * 三处必须同口径的理由与 {@code turn_active} 完全相同：它们描述的是<b>同一个「可见消息序列」</b>，
     * 一个多滤掉一条就会让摘要水位与注入区间错位，裂出「既不摘要也不注入」的记忆空洞。
     */
    private static void memoryVisible(QueryWrapper<ChatMessage> qw) {
        activeOnly(qw);
        qw.eq("memory_excluded", 0);
    }

    /**
     * 读取会话全部历史（<b>只含当前生效的分支版本</b>），按时间正序。<b>排序必须带 id tiebreaker</b>：
     * {@code created_at} 是秒级 DATETIME，同一轮 user/assistant 时间相同，只按它排序时顺序取决于执行计划，
     * 改走 filesort 就会错序。
     */
    public List<ChatMessage> getHistory(String conversationId) {
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId);
        activeOnly(qw);
        qw.orderByAsc("created_at").orderByAsc("id");
        return chatMessageMapper.selectList(qw);
    }

    /**
     * 读取最近 N 条历史（时间正序，只含<b>生效版本且未标记「不参与记忆」</b>的消息）：先按
     * {@code created_at, id} 倒序取 N 条再反转，供记忆窗口读取，避免长会话每轮全量加载。走索引需
     * {@code (conversation_id, created_at)} 复合索引（schema.sql 已建）。
     * <p>
     * 这是<b>记忆口径</b>（见 {@link #memoryVisible}）：被用户排除的消息在此不出现，因而既不会进
     * prompt 上下文、也不会被 {@code MemoryMergeService} 纳入摘要。历史展示请用 {@link #getHistory}。
     *
     * @param limit &lt;= 0 时退化为全量查询（同样走记忆口径）
     */
    public List<ChatMessage> getRecentHistory(String conversationId, int limit) {
        if (limit <= 0) {
            QueryWrapper<ChatMessage> all = new QueryWrapper<>();
            all.eq("conversation_id", conversationId);
            memoryVisible(all);
            all.orderByAsc("created_at").orderByAsc("id");
            return chatMessageMapper.selectList(all);
        }
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId);
        memoryVisible(qw);
        qw.orderByDesc("created_at").orderByDesc("id")
                .last("LIMIT " + limit);   // limit 为受控 int 参数，无注入风险
        List<ChatMessage> list = chatMessageMapper.selectList(qw);
        java.util.Collections.reverse(list); // 倒序取回后恢复正序
        return list;
    }

    /**
     * 统计消息总条数（只走 count，<b>记忆口径</b>：只算生效版本、且排除「不参与记忆」的消息）。
     * 供 {@code MemoryMergeService} 把窗口起点换算成绝对索引：摘要侧与注入侧必须基于同一段列表，
     * 否则会出现「既不摘要也不注入」的记忆空洞。
     */
    public int countMessages(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return 0;
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId);
        memoryVisible(qw);
        Long n = chatMessageMapper.selectCount(qw);
        return n == null ? 0 : n.intValue();
    }

    /**
     * 取时间正序下的第 {@code [fromIndex, toIndex)} 条消息（只读「尚未摘要的那一段」，<b>同样走记忆口径</b>）。
     * <p>
     * <b>索引口径与 {@link #countMessages} 必须一致</b>：两者一个给总数、一个给切片，用不同的过滤条件会让
     * 水位指向错误的位置。过滤条件下 {@code LIMIT offset} 作用在过滤之后的结果集上，语义仍然成立。
     * <p>
     * <b>依赖契约「chat_message 不做物理删除」</b>——有删除则索引区间漂移、摘要水位失准。排序同 {@link #getHistory}。
     */
    public List<ChatMessage> getMessagesRange(String conversationId, int fromIndex, int toIndex) {
        if (conversationId == null || conversationId.isBlank() || toIndex <= fromIndex || fromIndex < 0) {
            return List.of();
        }
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId);
        memoryVisible(qw);
        qw.orderByAsc("created_at").orderByAsc("id")
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
     * <p>
     * <b>必须与摘要水位一起归零</b>：否则 {@code summary} 指向不存在的历史，且打破
     * {@link #getMessagesRange} 的索引契约。两步刻意写在一起——分开写迟早会漏掉一边。自动整理出来的
     * 事实条目同理一并作废（它们是从这段已消失的历史里抽出来的）。
     * <p>
     * 分支版本一并物理删除：消息都没了，留下「没有生效版本」的孤儿版本组只会让切换器指向不存在的内容。
     */
    @Transactional
    public void clearMessages(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        log.info("清空会话消息并重置摘要水位：id={}", conversationId);
        chatMessageMapper.delete(new QueryWrapper<ChatMessage>().eq("conversation_id", conversationId));
        resetMemoryWatermark(conversationId);
        // 消息全没了 ⇒ 进行中的追问也失去了对象（它的「原始请求」就是其中一条消息）。
        // 这与「重置记忆」不同：那边消息仍在、追问仍可见可答，故刻意不清。
        saveClarifyState(conversationId, null);
    }

    // ===== 对话分支：同一轮提问的多个版本原地并存 =====

    /**
     * 「从第 {@code keepCount} 条消息处开一个新版本」的第一步（<b>无破坏性</b>）：给被改写的那一轮
     * 分配或复用分支组，返回该组与<b>新版本应取的版本号</b>。
     * <p>
     * 调用时机在前端把新文本交给 {@code /api/chat/stream} <b>之前</b>，所以这里只做不会改变现状的两件事：
     * 目标轮尚未分组时补上 {@code turn_group_id}（仍旧 {@code turn_active = 1}），并算出下一个版本号。
     * <b>绝不在这里把旧版本置为 inactive</b> —— 本轮有可能根本发不出去（附件处理失败、配额超限、
     * 内容安全拒绝），那时旧版本必须原样可见。「旧版本失效」只在新版本确实落库之后才做，
     * 见 {@link #markRoundBranch}。
     * <p>
     * 一轮 = 目标用户提问 + 其后连续的助手回复（直到下一条用户提问）。切换器挂在提问上、切换时提问与
     * 回答一起换，故两者同属一组。
     *
     * @param keepCount 「正序保留前 N 条」，第 N 条即分叉点，<b>必须是用户提问</b>
     * @return 分支组 ID 与新版本应取的版本号
     */
    @Transactional
    public TurnBranch prepareBranch(String conversationId, int keepCount, Long userId) {
        requireOwned(conversationId, userId);
        List<ChatMessage> ordered = getHistory(conversationId);
        if (keepCount <= 0 || keepCount > ordered.size()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "分叉位置超出会话范围");
        }
        ChatMessage target = ordered.get(keepCount - 1);
        if (!"user".equals(target.getRole())) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "只能从用户提问处开启新版本");
        }
        // 该轮的行区间：目标提问 + 其后连续的助手回复（遇到下一条提问即止）
        List<Long> turnIds = new ArrayList<>();
        for (int i = keepCount - 1; i < ordered.size(); i++) {
            ChatMessage m = ordered.get(i);
            if (i > keepCount - 1 && "user".equals(m.getRole())) break;
            turnIds.add(m.getId());
        }
        String groupId = target.getTurnGroupId();
        if (groupId == null || groupId.isBlank()) {
            groupId = UUID.randomUUID().toString();
            // 旧轮记为第 1 版并保持生效：此刻新版本还不存在，它仍是唯一可见的那一版
            chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                    .in(ChatMessage::getId, turnIds)
                    .set(ChatMessage::getTurnGroupId, groupId)
                    .set(ChatMessage::getTurnVersion, 1)
                    .set(ChatMessage::getTurnActive, true));
            log.info("开启分支组：会话={}，组={}，分叉点=第 {} 条，旧轮 {} 条消息记为第 1 版",
                    conversationId, groupId, keepCount, turnIds.size());
            return new TurnBranch(groupId, FIRST_BRANCH_VERSION);
        }
        int max = 0;
        for (ChatMessage m : chatMessageMapper.selectList(new QueryWrapper<ChatMessage>()
                .eq("conversation_id", conversationId).eq("turn_group_id", groupId))) {
            if (m.getTurnVersion() != null) {
                max = Math.max(max, m.getTurnVersion());
            }
        }
        int next = max + 1;
        log.info("复用分支组：会话={}，组={}，下一版本={}", conversationId, groupId, next);
        return new TurnBranch(groupId, next);
    }

    /**
     * 「开新版本」的第二步：把本轮<b>新落库</b>的消息（{@code id > afterId}）打上分支标记并置为生效，
     * 同组旧版本随之失效。必须在对话跑完、消息确实落库之后调用。
     * <p>
     * <b>先打标、再失效</b>，并以「打标是否命中」为闸门：本轮一条新消息都没落库（模型调用失败、
     * 用户中途停止且未产生内容）时直接返回，<b>旧版本保持可见</b> —— 否则用户会看到那一轮凭空消失、
     * 且没有任何报错。这正是 {@link #prepareBranch} 不提前失效的原因，两步合起来才是一次完整的分支。
     * <p>
     * <b>时间戳要锚回旧版本</b>：新版本行天然带更晚的 {@code created_at}，直接入库会排到后面几轮之后
     * （读取一律 {@code ORDER BY created_at, id}）。故统一改成该组首条消息的时间，让它占回原来的位置。
     * <p>
     * 记忆水位一并归零：生效的历史整段换了一条，指向旧版本的摘要已失效。
     *
     * @return 实际打标的消息条数；0 表示本轮无消息落库、旧版本未被替换
     */
    @Transactional
    public int markRoundBranch(String conversationId, Long afterId, String groupId, int version) {
        if (conversationId == null || conversationId.isBlank() || groupId == null || groupId.isBlank()) {
            return 0;
        }
        ChatMessage anchorRow = chatMessageMapper.selectOne(new QueryWrapper<ChatMessage>()
                .eq("conversation_id", conversationId).eq("turn_group_id", groupId)
                .orderByAsc("id").last("LIMIT 1"));
        LocalDateTime anchor = (anchorRow == null || anchorRow.getCreatedAt() == null)
                ? LocalDateTime.now() : anchorRow.getCreatedAt();

        LambdaUpdateWrapper<ChatMessage> uw = new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .isNull(ChatMessage::getTurnGroupId)      // 只打本轮新落、尚未归组的行
                .set(ChatMessage::getTurnGroupId, groupId)
                .set(ChatMessage::getTurnVersion, version)
                .set(ChatMessage::getTurnActive, true)
                .set(ChatMessage::getCreatedAt, anchor);
        if (afterId != null) {
            uw.gt(ChatMessage::getId, afterId);
        }
        int marked = chatMessageMapper.update(null, uw);
        if (marked == 0) {
            log.warn("分支新版本无消息落库，旧版本保持生效：会话={}，组={}，版本={}", conversationId, groupId, version);
            return 0;
        }
        chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .eq(ChatMessage::getTurnGroupId, groupId)
                .ne(ChatMessage::getTurnVersion, version)
                .set(ChatMessage::getTurnActive, false));
        resetMemoryWatermark(conversationId);
        log.info("分支新版本生效：会话={}，组={}，版本={}，标记 {} 条消息", conversationId, groupId, version, marked);
        return marked;
    }

    /**
     * 切换某一轮的生效版本（消息上「1/2 ‹ ›」点箭头）。
     * <p>
     * 两趟 UPDATE（先全灭、再点亮）而不用一条 {@code SET turn_active = (turn_version = ?)}：后者要靠字符串
     * 拼 SQL，可读性换来的收益为零；同事务内外部看不到中间态。
     * <p>
     * 记忆水位一并归零：翻到另一版意味着注入模型的历史整段换了，此前按旧版本压缩出的摘要已不适用
     * （现象上只表现为「模型记性变怪」，极难回溯）。
     *
     * @param version 目标版本号；该组内不存在此版本时抛 404
     */
    @Transactional
    public void switchTurnVersion(String conversationId, String groupId, int version, Long userId) {
        requireOwned(conversationId, userId);
        if (groupId == null || groupId.isBlank() || version <= 0) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "缺少分支组或版本号");
        }
        Long exists = chatMessageMapper.selectCount(new QueryWrapper<ChatMessage>()
                .eq("conversation_id", conversationId).eq("turn_group_id", groupId)
                .eq("turn_version", version));
        if (exists == null || exists == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "该版本不存在");
        }
        chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .eq(ChatMessage::getTurnGroupId, groupId)
                .set(ChatMessage::getTurnActive, false));
        chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .eq(ChatMessage::getTurnGroupId, groupId)
                .eq(ChatMessage::getTurnVersion, version)
                .set(ChatMessage::getTurnActive, true));
        resetMemoryWatermark(conversationId);
        log.info("切换分支版本：会话={}，组={}，版本={}", conversationId, groupId, version);
    }

    /**
     * 会话内每个分支组的<b>版本总数</b>（key = {@code turn_group_id}，value = 最大版本号）。
     * <p>
     * 写入侧保证版本号从 1 连续递增，故「最大版本号」即版本总数，不必再 {@code COUNT(DISTINCT)}。
     * <b>必须查全量行</b>（含 inactive）：只统计可见版本的话每组永远只有 1 版，切换器根本不会出现。
     */
    public Map<String, Integer> turnVersionCounts(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return Map.of();
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId).isNotNull("turn_group_id")
                .select("turn_group_id", "turn_version");
        Map<String, Integer> counts = new HashMap<>();
        for (ChatMessage m : chatMessageMapper.selectList(qw)) {
            if (m.getTurnGroupId() == null || m.getTurnVersion() == null) continue;
            counts.merge(m.getTurnGroupId(), m.getTurnVersion(), Math::max);
        }
        return counts;
    }

    /** 首次分叉时旧轮记为第 1 版，新版本顺延为第 2 版。 */
    private static final int FIRST_BRANCH_VERSION = 2;

    /**
     * 摘要 / 旧版事实归档 / 已摘要条数三列一齐清零（消息不动），<b>并作废自动整理出来的长期事实条目</b>。
     * <p>
     * 「删消息」与「重置记忆」共用这一步。事实条目一并清理的理由：它们与此前那三段是同一份压缩产物 ——
     * 摘要清零了、归档清零了，却把从同一段历史里抽出来的条目留着，模型下一轮照样能把这些「已经作废的
     * 记忆」背出来，现象上就是「重置记忆没生效」。<b>用户手加的条目保留</b>：它不是从历史里压出来的，
     * 历史换了它依然成立（见 {@code ConversationFactService#deleteGenerated}）。
     */
    private void resetMemoryWatermark(String conversationId) {
        Conversation c = conversationMapper.selectById(conversationId);
        if (c != null) {
            c.setSummary(null);
            c.setCoreFacts(null);
            c.setSummarizedCount(0);
            conversationMapper.updateById(c);
        }
        factService.deleteGenerated(conversationId);
    }

    /**
     * 覆写长期记忆的「摘要」与「旧版事实归档」两列，<b>刻意不动水位 {@code summarized_count}</b>。
     * <p>
     * 水位是「已压缩到正序第几条」的执行游标，不是展示字段：用户手改它会让下次自动合并从错误位置继续
     * （重复摘要或整段漏摘要），且这种错位在现象上只表现为「模型记性变怪」，很难回溯。要重置游标请走
     * {@link #resetMemory}（它把三列一起归零，语义自洽）。
     * <p>
     * <b>{@code coreFacts} 现在承担的是「旧版文本归档」</b>：逐条事实已迁到 {@code conversation_fact}
     * （见 {@code ConversationFactService}），自动流程不再写这一列。这里保留写入口，是为了让用户在面板上
     * 能自行清空 / 修正这份归档 —— 传空串即清空归档而摘要不变。
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
     * 重置长期记忆：摘要 / 旧版事实归档 / 水位三列归零，<b>并作废自动整理出来的事实条目</b>，但不删消息。
     * <p>
     * 效果是「忘掉此前压缩出的一切，下次超窗时从最早的未摘要消息重新摘要」—— 与 {@link #truncateAfter}
     * 的区别只在「消息保不保留」：这里是「记忆错了、消息没错」，那边是「消息本身就不该在」。
     * <p>
     * <b>用户手加的事实条目不在作废范围内</b>（见 {@link #resetMemoryWatermark}）：那是用户写下的，
     * 不是从历史里压出来的。
     */
    @Transactional
    public void resetMemory(String conversationId, Long userId) {
        requireOwned(conversationId, userId);
        resetMemoryWatermark(conversationId);
        log.info("重置长期记忆（三列归零，消息保留）：会话={}", conversationId);
    }

    /**
     * 把某条消息标记为「不参与记忆」（或取消标记）。只改该条，<b>不删任何东西</b> —— 历史与导出照常可见。
     * <p>
     * <b>为什么改这一位要顺带清摘要游标</b>：{@code summarized_count} 是「可见消息序列的前几条已被摘要
     * 覆盖」的执行游标，而「可见」的构成刚被这次操作改变了 —— 游标不动的话它指向的就不再是原来那段历史
     * （少摘要一条，或把一段已排出的内容永远留在摘要里）。故一律归零，让下次超窗时按新的可见序列重算。
     * <p>
     * <b>但摘要内容不清</b>：重新合并会把已有摘要作为输入一起压缩（见 {@code MemoryMergeService#summarize}），
     * 信息不丢，只是多花一次合并调用。反过来若因为「排掉一条日志」就把 {@code summary}/{@code core_facts}
     * 也清掉，用户会莫名丢掉「我是谁 / 我的偏好」这类长期事实 —— 那不是这个开关该有的语义。
     *
     * @return 该条的记忆参与状态是否真的发生了变化（幂等：重复设同一个值返回 false，且不动游标）
     */
    @Transactional
    public boolean setMessageMemoryExcluded(Long messageId, boolean excluded, Long userId) {
        ChatMessage m = messageId == null ? null : chatMessageMapper.selectById(messageId);
        if (m == null) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "消息不存在");
        }
        // 归属校验：不是本人的消息，与「消息不存在」返回同一个 404 —— 文案有差异就等于告诉对方
        // 「这条消息存在，只是不归你」，那就把 messageId 变成了一次存在性探测。
        String conversationId = m.getConversationId();
        Conversation owner = conversationId == null ? null : conversationMapper.selectById(conversationId);
        if (owner == null || !Objects.equals(owner.getUserId(), userId)) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "消息不存在");
        }
        if (Boolean.TRUE.equals(m.getMemoryExcluded()) == excluded) {
            return false;
        }
        chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getId, messageId)
                .set(ChatMessage::getMemoryExcluded, excluded));
        resetSummaryCursor(conversationId);
        log.info("消息记忆参与状态变更：会话={}，消息={}，excluded={}（摘要游标已归零，摘要内容保留）",
                conversationId, messageId, excluded);
        return true;
    }

    /** 被标记为「不参与记忆」的<b>有效</b>消息条数（供记忆面板显示「这些没进上下文」）。 */
    public int countExcluded(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return 0;
        QueryWrapper<ChatMessage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId);
        activeOnly(qw);
        qw.eq("memory_excluded", 1);
        Long n = chatMessageMapper.selectCount(qw);
        return n == null ? 0 : n.intValue();
    }

    /**
     * 只把摘要<b>执行游标</b>归零，{@code summary} / {@code core_facts} 内容保留。
     * 与 {@link #resetMemoryWatermark}（三列全清）的区别：这里表达「覆盖范围要重算」，而不是「忘掉一切」。
     */
    private void resetSummaryCursor(String conversationId) {
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .set(Conversation::getSummarizedCount, 0));
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
        // 一并清除来源标记与澄清状态：来源标记留着会出现「EXPLICIT/CLARIFY 指向空绑定」的残留，
        // 澄清状态留着会出现「已解绑却还停在追问第 2 轮」——两者都是解绑这一刻就该消失的东西。
        LambdaUpdateWrapper<Conversation> wrapper = new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .set(Conversation::getAgentId, null)
                .set(Conversation::getAgentBindSource, null)
                .set(Conversation::getClarifyState, null);
        conversationMapper.update(null, wrapper);
        log.info("解绑智能体：会话={}", conversationId);
    }

    // ===== 澄清（参数补全）状态：让「问到第几次 / 原请求是什么 / 已确认哪些参数」跨轮稳定 =====

    /**
     * 读取澄清状态。无状态（列为空）或数据损坏都返回 {@code null}，调用侧据此重新开始一段追问
     * （见 {@code ClarifyState#parse}：坏数据只 WARN 不抛，一条坏 JSON 不该让整轮对话 500）。
     */
    public ClarifyState getClarifyState(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return null;
        Conversation c = conversationMapper.selectById(conversationId);
        return c == null ? null : ClarifyState.parse(c.getClarifyState());
    }

    /**
     * 覆写澄清状态；{@code null} = 清空。
     * <p>
     * 两个调用点（都在 {@code AgentRoundHandler}）：① 「确认进入追问」时写入，与追问消息<b>同一次落库</b> ——
     * 只落消息不落状态，下一轮就读不到原始请求锚点，本功能等于没做；只落状态不落消息，用户看不到那一问。
     * 两者必须同生同死。② 「本轮不追问」（参数已齐 / 无需参数）时以 {@code null} 清空，作废上一段追问。
     * 第二步不能省：不清会让输入区提示常驻，且下一轮把上一轮的原始请求与已确认参数当成本轮上下文。
     */
    public void saveClarifyState(String conversationId, ClarifyState state) {
        if (conversationId == null || conversationId.isBlank()) return;
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .set(Conversation::getClarifyState, state == null ? null : state.toJson()));
        // 清除是常规动作（「参数已齐」的每一轮都会走到，见 AgentRoundHandler），不记 INFO，免得刷屏；
        // 真正「写入一段进行中的追问」才值得留痕。
        if (state == null) {
            log.debug("清除澄清状态：会话={}", conversationId);
        } else {
            log.info("写入澄清状态：会话={}，已问次数={}，已确认参数={}", conversationId,
                    state.asked(), state.params().size());
        }
    }

    /**
     * 写入自动压缩的产物：滚动摘要 + 已覆盖条数（一次 UPDATE）。
     * <p>
     * <b>刻意不再写 {@code core_facts}</b>：自功能 E 起，长期事实改由 {@code conversation_fact} 逐条承载
     * （见 {@code ConversationFactService}），那一列退化为「旧版文本归档」—— 内容只在用户手动编辑时变化，
     * 自动合并不再覆写。这样做的意义是「信息不丢」：迁移期它仍是被拆条目的输入（表为空时注入侧也回退读它），
     * 但一旦条目建立，它就不再被任何自动流程改写，用户可以放心地把它当历史留档或删掉。
     * <p>
     * 若哪天又在这里写回 {@code core_facts}，注入侧的「条目优先、归档兜底」就会变成两处内容打架：
     * 面板上归档看着是新拆出来的条目，模型看到的却是两段措辞不同的同一批事实。
     */
    @Transactional
    public void updateSummary(String conversationId, String summary, int summarizedCount) {
        log.info("更新滚动摘要：会话={}，已覆盖条数={}，摘要长度={}",
                conversationId, summarizedCount, summary != null ? summary.length() : 0);
        Conversation c = conversationMapper.selectById(conversationId);
        if (c == null) return;
        c.setSummary(summary);
        c.setSummarizedCount(summarizedCount);
        conversationMapper.updateById(c);
    }
}
