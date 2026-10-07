package org.luo.ai.controller;

import org.luo.ai.dto.AttachmentDto;
import org.luo.ai.dto.BranchTurnRequest;
import org.luo.ai.dto.ConversationExport;
import org.luo.ai.dto.ConversationMemory;
import org.luo.ai.dto.ConversationSummary;
import org.luo.ai.dto.CreateConversationRequest;
import org.luo.ai.dto.CrossSessionRequest;
import org.luo.ai.dto.HistoryResponse;
import org.luo.ai.dto.KbCitation;
import org.luo.ai.dto.MessageDto;
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
import org.luo.ai.service.AgentService;
import org.luo.ai.service.ConversationService;
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
 * 会话管理接口（与对话业务分离，send/stream 见 ChatController）。
 * <p>
 * 基础：POST/PUT/DELETE /api/chat/conversation[/{id}]、PUT …/{id}/planner、PUT …/{id}/planner-confirm、
 * PUT …/{id}/rag、PUT …/{id}/review、PUT …/{id}/cross-session、GET /api/chat/conversations、GET /api/chat/history。
 * <p>
 * <b>四个会话级开关</b>（RAG / 规划 / 评审 / 跨会话）都是「纯布尔偏好」，拨动即写回会话，刷新后保持：
 * 其中 <b>规划与评审互斥</b>（都是编排形态），开启任一方会自动关掉另一方。
 * <p>
 * 扩展三组：
 * <ul>
 *   <li><b>消息重做</b>：POST …/{id}/branch 开新版本 + POST …/{id}/turn 切换版本 —— 编辑重发 / 重新生成
 *       不再删掉旧的那一轮，而是让多个版本原地并存（消息上「1/2 ‹ ›」）。重发本身仍走
 *       {@code /api/chat/stream}，故不需要第二套执行逻辑；</li>
 *   <li><b>导出</b>：GET …/{id}/export —— 回 Markdown 文本，由前端拼 Blob 下载（裸链接带不上
 *       {@code Authorization}）；</li>
 *   <li><b>长期记忆</b>：GET/PUT/DELETE …/{id}/memory —— 把此前全黑盒的摘要 / 核心事实摊开给用户看与改。</li>
 * </ul>
 * <p>
 * <b>会话按用户隔离</b>：每个端点先经 {@link AuthContext#require()} 取当前登录用户，所有读写都带该用户；
 * 列表只回本人会话，操作他人会话一律 404（与「不存在」不可区分）。
 */
@RestController
@RequestMapping("/api/chat")
public class ConversationController {

    private final ConversationService conversationService;
    private final AgentService agentService;

    public ConversationController(ConversationService conversationService, AgentService agentService) {
        this.conversationService = conversationService;
        this.agentService = agentService;
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
                        c.getReviewEnabled(), c.getCrossSession()))
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
                .map(m -> new MessageDto(m.getRole(), m.getContent(),
                        AttachmentDto.parse(m.getAttachmentsJson()),
                        KbCitation.parse(m.getCitationsJson()),
                        turnOf(m, versionCounts)))
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

    // ===== 长期记忆（把黑盒摊开给用户看与改）=====

    /**
     * 读取会话的长期记忆：滚动摘要、核心事实、已摘要条数（游标）、消息总数。
     * 这是「模型为什么突然提到某件旧事」的唯一解释入口。仅本人会话可读。
     */
    @GetMapping("/conversation/{conversationId}/memory")
    public ConversationMemory memory(@PathVariable String conversationId) {
        Conversation c = conversationService.requireOwned(conversationId, AuthContext.require().id());
        return new ConversationMemory(c.getSummary(), c.getCoreFacts(),
                c.getSummarizedCount() == null ? 0 : c.getSummarizedCount(),
                conversationService.countMessages(conversationId));
    }

    /**
     * 覆写摘要与核心事实。<b>水位不在请求范围内</b>（它是执行游标，手改会让下次自动压缩从错位继续）；
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

    /** 重置长期记忆：摘要 / 核心事实 / 水位三列归零，<b>消息保留</b>（下次超窗会从头重新摘要）。 */
    @DeleteMapping("/conversation/{conversationId}/memory")
    public void resetMemory(@PathVariable String conversationId) {
        conversationService.resetMemory(conversationId, AuthContext.require().id());
    }
}
