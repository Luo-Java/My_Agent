package org.luo.controller;

import org.luo.dto.ConversationSummary;
import org.luo.dto.CreateConversationRequest;
import org.luo.dto.HistoryResponse;
import org.luo.dto.MessageDto;
import org.luo.dto.NewConversationResponse;
import org.luo.dto.RenameConversationRequest;
import org.luo.entity.Agent;
import org.luo.entity.Conversation;
import org.luo.service.AgentService;
import org.luo.service.ConversationService;
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

/**
 * 会话管理接口（与对话业务分离）。
 * <p>
 * 只负责会话本身的增删改查与历史读取；实际对话（send/stream）见 ChatController。
 * 所有会话持久化委托给 ConversationService，智能体名称查询委托给 AgentService。
 * <p>
 * POST   /api/chat/conversation      - 开启新会话，返回会话 ID
 * PUT    /api/chat/conversation/{id} - 重命名会话
 * DELETE /api/chat/conversation/{id} - 删除会话及其全部消息
 * GET    /api/chat/conversations     - 会话列表（按最近更新倒序）
 * GET    /api/chat/history           - 读取某会话的历史消息
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

    /**
     * 开启新会话，返回会话 ID。
     * 可绑定某个智能体或标记为规划模式（二者互斥）：绑定时以其名称作为会话初始标题。
     *
     * @param req 可选：{@code agentId} 绑定的智能体 ID；{@code planner} 规划模式
     * @return 新会话信息（id / title / createdAt / agentId / planner）
     */
    @PostMapping("/conversation")
    public NewConversationResponse createConversation(@RequestBody(required = false) CreateConversationRequest req) {
        Boolean planner = (req == null) ? null : req.planner();
        if (Boolean.TRUE.equals(planner)) {
            // 规划模式会话：由 ChatService 交给动态规划器，运行时由 LLM 规划多智能体步骤
            Conversation c = conversationService.createPlannerConversation();
            return new NewConversationResponse(c.getId(), c.getTitle(), c.getCreatedAt(), null, true);
        }
        Long agentId = (req == null) ? null : req.agentId();
        String agentName = null;
        if (agentId != null) {
            Agent a = agentService.getAgent(agentId);
            agentName = a != null ? a.getName() : null;
        }
        Conversation c = conversationService.createConversation(agentId, agentName);
        return new NewConversationResponse(c.getId(), c.getTitle(), c.getCreatedAt(), c.getAgentId(), false);
    }

    /**
     * 重命名会话。
     *
     * @param conversationId 会话 ID
     * @param request        新标题（自动 trim）
     */
    @PutMapping("/conversation/{conversationId}")
    public void renameConversation(@PathVariable String conversationId,
                                   @RequestBody RenameConversationRequest request) {
        conversationService.renameConversation(conversationId, request.title());
    }

    /**
     * 删除会话及其全部消息。
     *
     * @param conversationId 要删除的会话 ID
     */
    @DeleteMapping("/conversation/{conversationId}")
    public void deleteConversation(@PathVariable String conversationId) {
        conversationService.deleteConversation(conversationId);
    }

    /**
     * 会话列表，按最近更新时间倒序。
     *
     * @return 会话摘要列表（id / title / updatedAt / agentId / planner）
     */
    @GetMapping("/conversations")
    public List<ConversationSummary> listConversations() {
        return conversationService.listConversations().stream()
                .map(c -> new ConversationSummary(c.getId(), c.getTitle(), c.getUpdatedAt(),
                        c.getAgentId(), c.getPlanner()))
                .toList();
    }

    /**
     * 读取某会话的历史消息（按时间正序）。
     *
     * @param conversationId 会话 ID
     * @return 会话 ID + 消息列表
     */
    @GetMapping("/history")
    public HistoryResponse history(@RequestParam String conversationId) {
        List<MessageDto> messages = conversationService.getHistory(conversationId).stream()
                .map(m -> new MessageDto(m.getRole(), m.getContent()))
                .toList();
        return new HistoryResponse(conversationId, messages);
    }
}
