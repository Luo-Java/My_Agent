package org.luo.controller;

import org.luo.dto.GeneratePromptRequest;
import org.luo.dto.GeneratePromptResponse;
import org.luo.dto.UpsertAgentRequest;
import org.luo.entity.Agent;
import org.luo.exception.AiBusinessException;
import org.luo.exception.AiErrorCode;
import org.luo.service.AgentService;
import org.luo.service.ChatService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 智能体管理接口。
 * <p>
 * GET    /api/agent                 - 智能体列表（按最近更新倒序）
 * GET    /api/agent/{id}            - 单个智能体详情
 * POST   /api/agent                 - 创建智能体
 * DELETE /api/agent/{id}            - 删除智能体（同时解除会话绑定）
 * POST   /api/agent/generate-prompt - 用 AI 根据名称/描述生成系统提示词
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;
    private final ChatService chatService;

    public AgentController(AgentService agentService, ChatService chatService) {
        this.agentService = agentService;
        this.chatService = chatService;
    }

    @GetMapping
    public List<Agent> list() {
        return agentService.listAgents();
    }

    @GetMapping("/{id}")
    public Agent get(@PathVariable Long id) {
        return agentService.getAgent(id);
    }

    /**
     * 按智能体编码查询（多智能体协作路由入口）。
     * 注意：/by-code 为字面量路径，优先于 /{id} 匹配，二者互不冲突。
     */
    @GetMapping("/by-code/{code}")
    public Agent getByCode(@PathVariable String code) {
        Agent a = agentService.getByCode(code);
        if (a == null) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "智能体编码不存在：" + code);
        }
        return a;
    }

    @PostMapping
    public Agent create(@RequestBody UpsertAgentRequest req) {
        return agentService.createAgent(req);
    }

    @PutMapping("/{id}")
    public Agent update(@PathVariable Long id, @RequestBody UpsertAgentRequest req) {
        UpsertAgentRequest full = new UpsertAgentRequest(
                id, req.name(), req.agentCode(), req.icon(), req.description(),
                req.systemPrompt(), req.paramSchema(),
                req.model(), req.temperature(), req.avatarColor());
        return agentService.updateAgent(full);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        agentService.deleteAgent(id);
    }

    /**
     * 用 AI 生成系统提示词（不落库，仅返回文本供前端预览后随保存提交）。
     *
     * @param req 名称（必填）+ 描述（可选）
     * @return 生成的提示词
     */
    @PostMapping("/generate-prompt")
    public GeneratePromptResponse generatePrompt(@RequestBody GeneratePromptRequest req) {
        String prompt = chatService.generateAgentPrompt(req.name(), req.description());
        return new GeneratePromptResponse(prompt);
    }
}
