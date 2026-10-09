package org.luo.ai.controller;

import org.luo.ai.dto.AttachmentDto;
import org.luo.ai.dto.BranchTurnRequest;
import org.luo.ai.dto.ConversationExport;
import org.luo.ai.dto.ConversationFactDto;
import org.luo.ai.dto.ConversationFactRequest;
import org.luo.ai.dto.ConversationMemory;
import org.luo.ai.dto.ConversationSummary;
import org.luo.ai.dto.CreateConversationRequest;
import org.luo.ai.dto.CrossSessionRequest;
import org.luo.ai.dto.FeedbackRequest;
import org.luo.ai.dto.HistoryResponse;
import org.luo.ai.dto.KbCitation;
import org.luo.ai.dto.MessageDto;
import org.luo.ai.dto.MessageFeedbackDto;
import org.luo.ai.dto.MemoryExcludedRequest;
import org.luo.ai.dto.NewConversationResponse;
import org.luo.ai.dto.PlannerConfirmRequest;
import org.luo.ai.dto.PlannerEnabledRequest;
import org.luo.ai.dto.RagEnabledRequest;
import org.luo.ai.dto.RenameConversationRequest;
import org.luo.ai.dto.SwitchTurnRequest;
import org.luo.ai.dto.TurnBranch;
import org.luo.ai.dto.ReviewEnabledRequest;
import org.luo.ai.dto.UpdateMemoryRequest;
import org.luo.ai.entity.Agent;
import org.luo.ai.entity.ChatMessage;
import org.luo.ai.entity.Conversation;
import org.luo.ai.memory.MemoryViewService;
import org.luo.ai.service.AgentService;
import org.luo.ai.service.ConversationFactService;
import org.luo.ai.service.ConversationService;
import org.luo.ai.service.FeedbackService;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.system.security.AuthContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 会话管理接口（send/stream 见 ChatController）。基础：{@code /api/chat/conversation[/{id}]}、
 * {@code …/{id}/planner|planner-confirm|rag|review|cross-session}、{@code /api/chat/conversations}、{@code /api/chat/history}。
 * 扩展：{@code …/{id}/branch|turn}（消息重做，多版本并存）、{@code …/{id}/export}（回 Markdown 由前端拼 Blob ——
 * 裸链接带不上 {@code Authorization}）、{@code …/{id}/memory} 与 {@code …/{id}/facts[/{factId}]}（长期记忆查看/编辑）、
 * {@code …/message/{messageId}/memory-excluded}（单条不参与记忆：不进上下文与摘要，但<b>历史里照常可见</b>）。
 * <p>
 * 红线：四个会话级开关（RAG / 规划 / 评审 / 跨会话）都是纯布尔偏好，拨动即写回；<b>规划与评审互斥</b>（同为编排形态），
 * 开一个自动关另一个。会话按用户隔离：先取 {@link AuthContext#require()}，列表只回本人会话，操作他人会话一律 404
 * （与「不存在」不可区分）。
 */
@RestController
@RequestMapping("/api/chat")
public class ConversationController {

    private final ConversationService conversationService;
    private final AgentService agentService;
    /** 记忆窗口视图（只读）：给记忆面板列出「当前会注入哪些历史」。 */
    private final MemoryViewService memoryViewService;
    /** 长期事实条目：逐条增删改（功能 E）；会话归属由本类先校验，见各端点注释。 */
    private final ConversationFactService factService;
    /** 消息反馈：点踩落库与回显（转用例在 EvalController，那边限 ADMIN）。 */
    private final FeedbackService feedbackService;

    public ConversationController(ConversationService conversationService, AgentService agentService,
                                  MemoryViewService memoryViewService, ConversationFactService factService,
                                  FeedbackService feedbackService) {
        this.conversationService = conversationService;
        this.agentService = agentService;
        this.memoryViewService = memoryViewService;
        this.factService = factService;
        this.feedbackService = feedbackService;
    }

    /** 开启新会话并返回会话 ID；绑定智能体与规划模式互斥（绑定时以其名称作为会话初始标题）。会话归属当前登录用户。 */
    @PostMapping("/conversation")
    public NewConversationResponse createConversation(@RequestBody(required = false) CreateConversationRequest req) {
        Long userId = AuthContext.require().id();
        Boolean planner = (req == null) ? null : req.planner();
        if (Boolean.TRUE.equals(planner)) {
            // 规划模式会话：由 ChatService 交给动态规划器，运行时由 LLM 规划多智能体步骤
            Conversation c = conversationService.createPlannerConversation(userId);
            return new NewConversationResponse(c.getId(), c.getTitle(), c.getCreatedAt(), null, true,
                    c.getRagEnabled(), c.getPlannerConfirm(), false, false);
        }
        Long agentId = (req == null) ? null : req.agentId();
        String agentName = null;
        if (agentId != null) {
            Agent a = agentService.getAgent(agentId);
            agentName = a != null ? a.getName() : null;
        }
        Conversation c = conversationService.createConversation(agentId, agentName, userId);
        return new NewConversationResponse(c.getId(), c.getTitle(), c.getCreatedAt(), c.getAgentId(), false,
                c.getRagEnabled(), c.getPlannerConfirm(), false, false);
    }

    /** 重命名会话（新标题自动 trim）。仅本人会话可改。 */
    @PutMapping("/conversation/{conversationId}")
    public void renameConversation(@PathVariable String conversationId,
                                   @RequestBody RenameConversationRequest request) {
        conversationService.renameConversation(conversationId, request.title(), AuthContext.require().id());
    }

    /**
     * 更新会话 RAG 开关（输入框「📚 RAG」写回会话，刷新后保持上次选择）。开启后每轮自动检索
     * 「通用知识库 + 路由到智能体时其专属库」，无需手动选库；关闭则不使用 RAG。仅本人会话可改。
     */
    @PutMapping("/conversation/{conversationId}/rag")
    public void updateRagEnabled(@PathVariable String conversationId, @RequestBody(required = false) RagEnabledRequest request) {
        conversationService.updateRagEnabled(conversationId, (request == null) ? null : request.enabled(),
                AuthContext.require().id());
    }

    /**
     * 更新会话智能规划开关（输入框「🧭 智能规划」写回，与 RAG 开关对称）。planner 与 agentId 互斥：
     * 会话已绑定智能体时开启规划会被拒绝（前端置灰，后端防御校验）。仅本人会话可改。
     */
    @PutMapping("/conversation/{conversationId}/planner")
    public void updatePlannerEnabled(@PathVariable String conversationId, @RequestBody(required = false) PlannerEnabledRequest request) {
        conversationService.updatePlannerSwitch(conversationId, (request == null) ? null : request.enabled(),
                AuthContext.require().id());
    }

    /**
     * 更新规划模式「先看计划」开关（输入框「先看计划」，仅规划模式可用）。开启后，规划只产出计划并暂停，
     * 用户在计划卡片上点「执行计划」才真正跑步骤；关闭则规划后直接执行（默认）。仅本人会话可改。
     */
    @PutMapping("/conversation/{conversationId}/planner-confirm")
    public void updatePlannerConfirm(@PathVariable String conversationId,
                                     @RequestBody(required = false) PlannerConfirmRequest request) {
        conversationService.updatePlannerConfirm(conversationId, (request == null) ? null : request.enabled(),
                AuthContext.require().id());
    }

    /**
     * 更新会话「⚖ 评审」开关（输入框开关写回，与 RAG / 规划开关对称）。
     * <p>
     * 开启后本轮由 LLM 从智能体库里选候选并行作答、再由裁决者综合；<b>与规划模式互斥</b> ——
     * 开启评审会自动把 planner 关掉（两者都是编排形态，同时亮着会让「本轮走哪条链路」取决于读取顺序）。
     * 仅本人会话可改。
     */
    @PutMapping("/conversation/{conversationId}/review")
    public void updateReviewEnabled(@PathVariable String conversationId,
                                    @RequestBody(required = false) ReviewEnabledRequest request) {
        conversationService.updateReviewEnabled(conversationId, (request == null) ? null : request.enabled(),
                AuthContext.require().id());
    }

    /**
     * 更新会话「🔎 跨会话」开关：开启后每轮在本用户<b>其他会话</b>的历史消息里做关键词召回并注入当前上下文。
     * 只查本人会话、天然排除当前会话；召回不到内容时会在执行过程里明确播报，不是静默无事发生。仅本人会话可改。
     */
    @PutMapping("/conversation/{conversationId}/cross-session")
    public void updateCrossSession(@PathVariable String conversationId,
                                   @RequestBody(required = false) CrossSessionRequest request) {
        conversationService.updateCrossSession(conversationId, (request == null) ? null : request.enabled(),
                AuthContext.require().id());
    }

    /** 删除会话及其全部消息。仅本人会话可删。 */    @DeleteMapping("/conversation/{conversationId}")
    public void deleteConversation(@PathVariable String conversationId) {
        conversationService.deleteConversation(conversationId, AuthContext.require().id());
    }

    /** 当前用户的会话列表，按最近更新时间倒序。 */
    @GetMapping("/conversations")
    public List<ConversationSummary> listConversations() {
        return conversationService.listConversations(AuthContext.require().id()).stream()
                .map(c -> new ConversationSummary(c.getId(), c.getTitle(), c.getUpdatedAt(),
                        c.getAgentId(), c.getPlanner(), c.getRagEnabled(), c.getPlannerConfirm(),
                        c.getReviewEnabled(), c.getCrossSession(), c.getClarifyAsked(), c.getClarifyMax()))
                .toList();
    }

    /**
     * 读取某会话的历史消息（按时间正序，<b>只含当前生效的分支版本</b>）。用户消息附带本轮附件展示元数据，
     * 助手消息附带本轮 RAG 引用来源；两者都不参与记忆读取，仅供前端渲染，对 LLM 上下文与 token 零影响。
     * 每条消息另带 {@code turn}（分支组 / 版本号 / 总版本数），前端据此在提问上渲染「1/2 ‹ ›」切换器。
     * <b>仅本人会话可读</b>，他人会话 404。
     */
    @GetMapping("/history")
    public HistoryResponse history(@RequestParam String conversationId) {
        conversationService.requireOwned(conversationId, AuthContext.require().id());
        // 版本总数必须单独查全量行：历史本身已过滤掉未生效版本，只看它每组永远只有 1 版、切换器根本不出现
        Map<String, Integer> versionCounts = conversationService.turnVersionCounts(conversationId);
        List<MessageDto> messages = conversationService.getHistory(conversationId).stream()
                .map(m -> new MessageDto(m.getId(), m.getRole(), m.getContent(),
                        AttachmentDto.parse(m.getAttachmentsJson()),
                        KbCitation.parse(m.getCitationsJson()),
                        turnOf(m, versionCounts),
                        Boolean.TRUE.equals(m.getMemoryExcluded())))
                .toList();
        return new HistoryResponse(conversationId, messages);
    }

    /** 组装某条消息的分支版本信息；该轮从未分叉（无组）时返回 {@code null}，前端据此不渲染切换器。 */
    private static MessageDto.Turn turnOf(ChatMessage m, Map<String, Integer> versionCounts) {
        String groupId = m.getTurnGroupId();
        if (groupId == null || groupId.isBlank()) return null;
        int version = m.getTurnVersion() == null ? 1 : m.getTurnVersion();
        int total = versionCounts.getOrDefault(groupId, version);
        return new MessageDto.Turn(groupId, version, Math.max(total, version));
    }

    // ===== 对话分支：同一轮提问的多个版本原地并存 =====

    /**
     * 开启新版本的第一步：给目标轮分配（或复用）分支组，返回该组与<b>新版本应取的版本号</b>。
     * <p>
     * <b>刻意只做这一步、不在这里改任何已有消息的状态</b>：前端拿到结果后把新文本 / 新提问交给既有的
     * {@code /api/chat/stream}（带上 {@code branchGroupId} 与 {@code branchVersion}），由服务端在消息
     * 确实落库之后才让旧版本失效。本轮有可能根本发不出去（附件处理失败、配额超限、内容安全拒绝），
     * 那时旧版本必须原样可见 —— 提前失效会让那一轮凭空消失且没有任何报错。
     * <p>
     * 与「先看计划」「局部重规划」「模板套用」同一思路：能复用现成通路就不另起一条，这里也就不需要关心
     * 规划 / RAG / 附件的组装逻辑。
     * <p>
     * 缺请求体时<b>不猜位置</b>、直接 400 —— 「从哪一条分」没有安全的默认值：猜小了等于截掉大半会话，
     * 猜大了等于什么都没做，两种都可能被误当成操作成功。
     */
    @PostMapping("/conversation/{conversationId}/branch")
    public Map<String, Object> branch(@PathVariable String conversationId,
                                      @RequestBody(required = false) BranchTurnRequest request) {
        if (request == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "缺少分叉位置（keepCount）");
        }
        TurnBranch b = conversationService.prepareBranch(conversationId, request.keepCount(),
                AuthContext.require().id());
        return Map.of("groupId", b.groupId(), "version", b.version());
    }

    /**
     * 切换某一轮的生效版本（消息上「1/2 ‹ ›」点箭头）。
     * <p>
     * 同步接口：改完落库即可，前端重新拉一次历史渲染。切换会让长期记忆水位归零（注入模型的历史整段换了），
     * 故前端还应顺手刷新记忆面板 —— 否则面板上显示的还是按旧版本压缩出的摘要。
     */
    @PostMapping("/conversation/{conversationId}/turn")
    public Map<String, Object> switchTurn(@PathVariable String conversationId,
                                          @RequestBody(required = false) SwitchTurnRequest request) {
        if (request == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "缺少分支组（groupId）与版本号（version）");
        }
        conversationService.switchTurnVersion(conversationId, request.groupId(), request.version(),
                AuthContext.require().id());
        return Map.of("ok", true, "version", request.version());
    }


    // ===== 导出 =====

    /**
     * 导出会话为 Markdown。<b>返回文本而非文件流</b>：下载必须带 {@code Authorization}，浏览器对
     * {@code <a href>} 的导航请求带不上这个头，走 {@code /files/**} 只会 401 —— 故由前端拼 Blob 下载。
     */
    @GetMapping("/conversation/{conversationId}/export")
    public ConversationExport export(@PathVariable String conversationId) {
        Conversation c = conversationService.requireOwned(conversationId, AuthContext.require().id());
        // 归属校验只做一次：两个方法都接收已校验的实体，而不是各自再查一遍库
        return new ConversationExport(conversationService.exportFileName(c),
                conversationService.exportMarkdown(c));
    }

    // ===== 记忆参与：单条开关（不进上下文、不进摘要，但历史仍可见）=====

    /**
     * 把某条消息标记为「不参与记忆」，或取消该标记。
     * <p>
     * <b>副作用必须说清</b>：这会重置该会话的摘要<b>游标</b>（{@code summarized_count} 归零）—— 可见消息的
     * 构成刚变了，游标指向的那段历史不再是原来那段。摘要与记忆内容的<b>内容保留</b>，下次超窗时会连它们
     * 一起重新压一遍，故不丢信息，只多一次合并开销。响应里的 {@code memoryCursorReset} 就是给前端提示
     * 这件事用的（前端据此告诉用户「记忆已重新整理」，而不是让这次重置悄悄发生）。
     * <p>
     * 缺请求体（或没带 {@code excluded}）直接 400：这个开关没有安全的默认值 —— 猜 {@code true} 会把用户
     * 没想排除的消息排出上下文，猜 {@code false} 会让一次「排除」静默变成空操作，两种都会被当成操作成功。
     */
    @PutMapping("/message/{messageId}/memory-excluded")
    public Map<String, Object> updateMemoryExcluded(@PathVariable Long messageId,
                                                   @RequestBody(required = false) MemoryExcludedRequest request) {
        if (request == null || request.excluded() == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "缺少 excluded（true=不参与记忆，false=恢复参与）");
        }
        boolean changed = conversationService.setMessageMemoryExcluded(messageId, request.excluded(),
                AuthContext.require().id());
        return Map.of("excluded", request.excluded(), "memoryCursorReset", changed);
    }

    // ===== 消息反馈（点踩 → 回归用例的第一步）=====

    /**
     * 提交 / 更新对某条助手回复的反馈（👍 / 👎）。
     * <p>
     * <b>一人对一条消息一票</b>：重复提交是<b>改票</b>（原地覆盖），不追加历史 —— 否则「先踩后赞」会留下两条
     * 互相矛盾的记录，转回归用例时不知道该信哪条。首次提交时快照那一轮的用户输入，供后续转用例当输入用。
     * <p>
     * 归属按「消息所属会话」判定：他人的消息与不存在的消息统一 404，避免 {@code messageId} 变成存在性探针。
     * 只接受助手回复（给自己的提问点踩没有意义）。
     */
    @PutMapping("/message/{messageId}/feedback")
    public MessageFeedbackDto feedback(@PathVariable Long messageId,
                                       @RequestBody(required = false) FeedbackRequest request) {
        if (request == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "缺少反馈内容（rating）");
        }
        return feedbackService.submit(messageId, AuthContext.require().id(), request);
    }

    /**
     * 某会话下的全部反馈：前端按 {@code messageId} 合并到消息上，用于回显「已反馈」与预填原因 / 备注。
     * <p>
     * 刻意<b>不做进 history 响应</b>：历史是「说了什么」，反馈是「怎么看这句话」，两者变化频率与读取时机都
     * 不同 —— 合成一个响应会让每次翻历史都白拉一遍反馈，也让 {@code MessageDto} 多一个只有前端标记用途的字段。
     */
    @GetMapping("/conversation/{conversationId}/feedback")
    public List<MessageFeedbackDto> feedbackList(@PathVariable String conversationId) {
        return feedbackService.listByConversation(conversationId, AuthContext.require().id());
    }

    // ===== 长期记忆（把黑盒摊开给用户看与改）=====

    /**
     * 读取会话的长期记忆：逐条长期事实、旧版事实归档、滚动摘要、已摘要条数（游标）、消息总数，以及
     * <b>当前记忆窗口构成</b>（哪些历史会被注入）与被排除条数。这是「模型为什么突然提到某件旧事 /
     * 为什么忘了某件事」的唯一解释入口。
     * <p>
     * 窗口用的是与真实注入完全相同的窗口算法（见 {@code MemoryViewService}），故这里列出的就是模型能看到的那些条；
     * 不含本轮提问（提问在读取之后才被追加进上下文）。仅本人会话可读。
     * <p>
     * <b>事实的注入规则要照实显示</b>：条目非空时注入条目，只有一条条目都没有时才回退注入旧归档
     * （见 {@code ConversationFactService#injectableFactsText}）—— 归档与条目同时存在时归档<b>不生效</b>。
     */
    @GetMapping("/conversation/{conversationId}/memory")
    public ConversationMemory memory(@PathVariable String conversationId) {
        Conversation c = conversationService.requireOwned(conversationId, AuthContext.require().id());
        return new ConversationMemory(c.getSummary(), c.getCoreFacts(),
                c.getSummarizedCount() == null ? 0 : c.getSummarizedCount(),
                conversationService.countMessages(conversationId),
                conversationService.countExcluded(conversationId),
                memoryViewService.window(conversationId),
                factService.listDto(conversationId));
    }

    /**
     * 覆写摘要与旧版事实归档。<b>水位不在请求范围内</b>（它是执行游标，手改会让下次自动压缩从错位继续）；
     * 两个字段都传空即「清空内容、保留游标」，要连游标一起清零请走 DELETE。
     */
    @PutMapping("/conversation/{conversationId}/memory")
    public void updateMemory(@PathVariable String conversationId,
                             @RequestBody(required = false) UpdateMemoryRequest request) {
        conversationService.updateMemoryFields(conversationId,
                request == null ? null : request.summary(),
                request == null ? null : request.coreFacts(),
                AuthContext.require().id());
    }

    /** 重置长期记忆：摘要 / 旧版归档 / 水位归零，<b>并作废自动整理出来的事实条目</b>，消息保留
     *（下次超窗会从头重新摘要）。用户手加的事实条目不受影响。 */
    @DeleteMapping("/conversation/{conversationId}/memory")
    public void resetMemory(@PathVariable String conversationId) {
        conversationService.resetMemory(conversationId, AuthContext.require().id());
    }

    // ===== 长期事实条目：逐条增删改（功能 E）=====

    /**
     * 新增一条长期事实。
     * <p>
     * <b>手加的条目永远不会被自动合并覆盖或删除</b>（来源记为 USER，与模型整理出的 MERGE 分开待遇）；
     * 若内容与某条自动整理出来的条目完全重合，则把它<b>转成手动</b>而不是报重复 —— 用户再写一遍的意图
     * 就是「这条我要留着」。只有与已有手动条目重复才报 400。
     * <p>
     * 归属校验走 {@code requireOwned}（与 GET /memory 同一形状）：{@code ConversationFactService} 刻意
     * 不做校验，为的是不让它反向依赖 {@code ConversationService}（那边要用它来清理条目，依赖成环）。
     */
    @PostMapping("/conversation/{conversationId}/facts")
    public ConversationFactDto addFact(@PathVariable String conversationId,
                                       @RequestBody(required = false) ConversationFactRequest request) {
        conversationService.requireOwned(conversationId, AuthContext.require().id());
        if (request == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "缺少事实内容（fact）");
        }
        return ConversationFactDto.of(factService.add(conversationId, request.topic(), request.fact(),
                request.expiresAt()));
    }

    /**
     * 修改一条长期事实（主题与内容都可改）。
     * <p>
     * <b>改过的条目会从「自动整理」转为「手动」</b>：用户改它是因为模型记错了，那它就不该再被下一次
     * 自动整理覆盖或淘汰。这一位变化前端要在保存后立刻体现（条目标签从「自动」变「手动」）。
     */
    @PutMapping("/conversation/{conversationId}/facts/{factId}")
    public ConversationFactDto updateFact(@PathVariable String conversationId, @PathVariable Long factId,
                                          @RequestBody(required = false) ConversationFactRequest request) {
        conversationService.requireOwned(conversationId, AuthContext.require().id());
        if (request == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "缺少事实内容（fact）");
        }
        return ConversationFactDto.of(
                factService.update(factId, conversationId, request.topic(), request.fact(),
                        request.expiresAt()));
    }

    /**
     * 删除一条长期事实（自动 / 手动都可删）。
     * <p>
     * 删自动条目后它<b>不会被下一次合并自动加回来</b>，除非新对话里又提到了它 —— 合并的输入是
     * 「当前条目 + 新增溢出内容」，被删的行模型看不到。前端提示要写成「已删除；若后续对话再次提到，
     * 可能会重新整理出类似条目」，而不是含糊的「已删除」。
     */
    @DeleteMapping("/conversation/{conversationId}/facts/{factId}")
    public void deleteFact(@PathVariable String conversationId, @PathVariable Long factId) {
        conversationService.requireOwned(conversationId, AuthContext.require().id());
        factService.delete(factId, conversationId);
    }
}
