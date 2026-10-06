package org.luo.ai.controller;

import org.luo.ai.dto.AgentImportRequest;
import org.luo.ai.dto.AgentImportResult;
import org.luo.ai.dto.AgentPortable;
import org.luo.ai.dto.GeneratePromptRequest;
import org.luo.ai.dto.GeneratePromptResponse;
import org.luo.ai.dto.UpsertAgentRequest;
import org.luo.ai.entity.Agent;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.ai.service.AgentService;
import org.luo.ai.service.ChatService;
import org.luo.ai.agent.PromptService;
import org.luo.ai.tool.ToolRegistry;
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

    // ===== 导入 / 导出：把智能体当「资产」搬进搬出 =====

    /**
     * 导出全部智能体为可移植 JSON（剥掉本地自增 id 与时间戳）。
     * <p>
     * 包内<b>不含</b>智能体的专属知识库及其文件——那属于知识库模块、体积也可能很大，换环境时需另行重建。
     * 这里保证人设、参数清单、工具装配、模型参数完整带走。
     * <p>
     * 路径 {@code /export} 是字面量，优先于 {@code /{id}} 匹配（同 {@code /tools}、{@code /by-code}），二者不冲突。
     */
    @GetMapping("/export")
    public List<AgentPortable> export() {
        return agentService.exportPortable();
    }

    /**
     * 导入智能体（按 {@code agentCode} 匹配既有记录；{@code onConflict} = {@code skip} 跳过 / {@code overwrite} 覆盖）。
     * 逐条独立处理：单条数据不合法只计入返回结果的 {@code errors}，不影响其余条目。
     */
    @PostMapping("/import")
    public AgentImportResult importAgents(@RequestBody(required = false) AgentImportRequest req) {
        return agentService.importPortable(req == null ? null : req.agents(),
                req == null ? null : req.onConflict());
    }
}
