package org.luo.controller;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.luo.dto.AttachmentDto;
import org.luo.dto.ConversationSummary;
import org.luo.dto.CreateConversationRequest;
import org.luo.dto.HistoryResponse;
import org.luo.dto.KbCitation;
import org.luo.dto.MessageDto;
import org.luo.dto.NewConversationResponse;
import org.luo.dto.PlannerEnabledRequest;
import org.luo.dto.RagEnabledRequest;
import org.luo.dto.RenameConversationRequest;
import org.luo.entity.Agent;
import org.luo.entity.Conversation;
import org.luo.infrastructure.attachment.AttachmentStorageService;
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

import java.util.ArrayList;
import java.util.List;
import org.luo.service.ChatService;

/**
 * 会话管理接口（与对话业务分离，send/stream 见 ChatController）。
 * <p>
 * POST/PUT/DELETE /api/chat/conversation[/{id}]、PUT …/{id}/planner、PUT …/{id}/rag、
 * GET /api/chat/conversations、GET /api/chat/history。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ConversationController {

    private final ConversationService conversationService;
    private final AgentService agentService;

    public ConversationController(ConversationService conversationService, AgentService agentService) {
        this.conversationService = conversationService;
        this.agentService = agentService;
    }

    /** 开启新会话并返回会话 ID；绑定智能体与规划模式互斥（绑定时以其名称作为会话初始标题）。 */
    @PostMapping("/conversation")
    public NewConversationResponse createConversation(@RequestBody(required = false) CreateConversationRequest req) {
        Boolean planner = (req == null) ? null : req.planner();
        if (Boolean.TRUE.equals(planner)) {
            // 规划模式会话：由 ChatService 交给动态规划器，运行时由 LLM 规划多智能体步骤
            Conversation c = conversationService.createPlannerConversation();
            return new NewConversationResponse(c.getId(), c.getTitle(), c.getCreatedAt(), null, true, c.getRagEnabled());
        }
        Long agentId = (req == null) ? null : req.agentId();
        String agentName = null;
        if (agentId != null) {
            Agent a = agentService.getAgent(agentId);
            agentName = a != null ? a.getName() : null;
        }
        Conversation c = conversationService.createConversation(agentId, agentName);
        return new NewConversationResponse(c.getId(), c.getTitle(), c.getCreatedAt(), c.getAgentId(), false, c.getRagEnabled());
    }

    /** 重命名会话（新标题自动 trim）。 */
    @PutMapping("/conversation/{conversationId}")
    public void renameConversation(@PathVariable String conversationId,
                                   @RequestBody RenameConversationRequest request) {
        conversationService.renameConversation(conversationId, request.title());
    }

    /**
     * 更新会话 RAG 开关（输入框「📚 RAG」写回会话，刷新后保持上次选择）。开启后每轮自动检索
     * 「通用知识库 + 路由到智能体时其专属库」，无需手动选库；关闭则不使用 RAG。
     */
    @PutMapping("/conversation/{conversationId}/rag")
    public void updateRagEnabled(@PathVariable String conversationId, @RequestBody(required = false) RagEnabledRequest request) {
        conversationService.updateRagEnabled(conversationId, (request == null) ? null : request.enabled());
    }

    /**
     * 更新会话智能规划开关（输入框「🧭 智能规划」写回，与 RAG 开关对称）。planner 与 agentId 互斥：
     * 会话已绑定智能体时开启规划会被拒绝（前端置灰，后端防御校验）。
     */
    @PutMapping("/conversation/{conversationId}/planner")
    public void updatePlannerEnabled(@PathVariable String conversationId, @RequestBody(required = false) PlannerEnabledRequest request) {
        conversationService.updatePlannerSwitch(conversationId, (request == null) ? null : request.enabled());
    }

    /** 删除会话及其全部消息。 */
    @DeleteMapping("/conversation/{conversationId}")
    public void deleteConversation(@PathVariable String conversationId) {
        conversationService.deleteConversation(conversationId);
    }

    /** 会话列表，按最近更新时间倒序。 */
    @GetMapping("/conversations")
    public List<ConversationSummary> listConversations() {
        return conversationService.listConversations().stream()
                .map(c -> new ConversationSummary(c.getId(), c.getTitle(), c.getUpdatedAt(),
                        c.getAgentId(), c.getPlanner(), c.getRagEnabled()))
                .toList();
    }

    /**
     * 读取某会话的历史消息（按时间正序）。用户消息附带本轮附件展示元数据，助手消息附带本轮 RAG 引用来源；
     * 两者都不参与记忆读取，仅供前端渲染，对 LLM 上下文与 token 零影响。
     */
    @GetMapping("/history")
    public HistoryResponse history(@RequestParam String conversationId) {
        List<MessageDto> messages = conversationService.getHistory(conversationId).stream()
                .map(m -> new MessageDto(m.getRole(), m.getContent(),
                        parseAttachments(m.getAttachmentsJson()),
                        KbCitation.parse(m.getCitationsJson())))
                .toList();
        return new HistoryResponse(conversationId, messages);
    }

    /** 解析消息的附件元数据 JSON；空 / 异常返回空列表（不影响历史读取）。 */
    private static List<AttachmentDto> parseAttachments(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JSONArray arr = JSONUtil.parseArray(json);
            List<AttachmentDto> list = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                JSONObject j = arr.getJSONObject(i);
                String storedName = j.getStr("storedName");
                list.add(new AttachmentDto(
                        j.getStr("type"),
                        j.getStr("filename"),
                        storedName,
                        AttachmentStorageService.url(storedName),
                        j.getLong("size")));
            }
            return list;
        } catch (Exception e) {
            log.warn("解析附件元数据失败：{}", json, e);
            return List.of();
        }
    }
}
