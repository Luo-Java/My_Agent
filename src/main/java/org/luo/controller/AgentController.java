package org.luo.controller;

import org.luo.dto.GeneratePromptRequest;
import org.luo.dto.GeneratePromptResponse;
import org.luo.dto.UpsertAgentRequest;
import org.luo.entity.Agent;
import org.luo.exception.AiBusinessException;
import org.luo.exception.AiErrorCode;
import org.luo.service.AgentService;
import org.luo.service.ChatService;
import org.luo.agent.PromptService;
import org.luo.tool.ToolRegistry;
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
 * GET /api/agent、GET /api/agent/{id}、GET /api/agent/{by-code}/{code}、GET /api/agent/tools、
 * POST /api/agent/generate-prompt（AI 生成提示词）、POST /api/agent、PUT/DELETE /api/agent/{id}。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;
    private final PromptService promptService;
    private final ToolRegistry toolRegistry;

    public AgentController(AgentService agentService, PromptService promptService, ToolRegistry toolRegistry) {
        this.agentService = agentService;
        this.promptService = promptService;
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    public List<Agent> list() {
        return agentService.listAgents();
    }

    @GetMapping("/{id}")
    public Agent get(@PathVariable Long id) {
        return agentService.getAgent(id);
    }

    /** 按智能体编码查询（多智能体协作路由入口）。注意：/by-code 为字面量路径，优先于 /{id} 匹配，二者不冲突。 */
    @GetMapping("/by-code/{code}")
    public Agent getByCode(@PathVariable String code) {
        Agent a = agentService.getByCode(code);
        if (a == null) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "智能体编码不存在：" + code);
        }
        return a;
    }

    /** 可用工具清单（供智能体编辑弹窗的「工具装配」展示，前端按 group 分组）。/tools 为字面量路径，不会被当作 ID。 */
    @GetMapping("/tools")
    public List<ToolRegistry.ToolInfo> tools() {
        return toolRegistry.getAvailableTools();
    }

    @PostMapping
    public Agent create(@RequestBody UpsertAgentRequest req) {
        return agentService.createAgent(req);
    }

    @PutMapping("/{id}")
    public Agent update(@PathVariable Long id, @RequestBody UpsertAgentRequest req) {
        // 直接透传请求体（updateAgent 内部有 id 非空校验与字段映射）；仅当请求体未携带 id 时
        // 用路径 id 补全，避免前端漏传导致 400。字段映射集中在校验后由 service 完成，不再手抄。
        if (req.id() == null) {
            req = new UpsertAgentRequest(
                    id, req.name(), req.agentCode(), req.icon(), req.description(),
                    req.systemPrompt(), req.paramSchema(), req.toolsJson(),
                    req.model(), req.temperature(), req.avatarColor());
        }
        return agentService.updateAgent(req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        agentService.deleteAgent(id);
    }

    /** 用 AI 生成系统提示词（不落库，仅返回文本供前端预览后随保存提交）。名称必填、描述可选。 */
    @PostMapping("/generate-prompt")
    public GeneratePromptResponse generatePrompt(@RequestBody GeneratePromptRequest req) {
        String prompt = promptService.generateAgentPrompt(req.name(), req.description());
        return new GeneratePromptResponse(prompt);
    }
}
