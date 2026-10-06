package org.luo.ai.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.advisor.ToolUsageLoggingAdvisor;
import org.luo.ai.agent.PromptService;
import org.luo.ai.entity.Agent;
import org.luo.ai.mapper.AgentMapper;
import org.luo.ai.trace.LlmUsageService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 「把任务转交给另一个智能体」工具（工具名 {@value #TOOL_NAME}）—— 让多智能体协作不再只有「规划模式」一条路。
 * <p>
 * <b>为什么是动态构造、而不是 {@link ToolProvider} 注解式</b>：智能体是<b>运行时数据</b>（agent 表里随时增删），
 * 而 {@code @Tool} 方法集合在编译期就固定、{@link ToolRegistry} 也只在<b>构造期</b>快照一次；两者对不上。
 * 因此本类不做成 Provider，而是由 {@link org.luo.ai.chat.ChatComposer} 在每轮组装请求时<b>现场构造</b>一个
 * {@link ToolCallback}（候选清单从库里现查），这样新建 / 删除智能体立刻生效、不需要重启，也不会有陈旧快照。
 * <p>
 * <b>为什么不让它出现在「全量工具」里</b>：{@code tools_json} 为 NULL/空 = 挂全量，但转交是<b>策略性能力</b>
 * 而非基础能力——翻译、闲聊类智能体凭空获得「可以把活推给别人」的选项，很容易被模型误用（明明自己就能答，
 * 却绕一圈转交），且会一次性改变所有既有智能体的行为。故本工具是<b>白名单专属</b>：必须在 {@code tools_json}
 * 里显式写出 {@code "call_agent"} 才会挂载（见 {@link ToolRegistry#dynamicToolRequested}）。
 * <p>
 * <b>只做一层，不嵌套</b>：子智能体执行时挂的是它的<b>静态工具</b>（{@link ToolRegistry#resolve}），而本工具不在
 * 静态池里，因此「A 转给 B，B 再转给 C」不会发生，天然不存在自递归与调用环。代价是子智能体不能继续向下转交，
 * 这在一层就够用的场景下是划算的取舍；若将来确实需要嵌套，必须补一套调用链追踪（深度上限 + 环路检测）才行。
 * <p>
 * <b>依赖为什么落在 Mapper 而不是 AgentService</b>：AgentService 间接依赖 {@code PlannerService → ChatComposer}，
 * 而 ChatComposer 又依赖本类，走 Service 会形成构造器循环依赖。此处直接操作数据层——与 AgentService 级联清理
 * 知识库时「避免循环依赖」的处理一致。
 * <p>
 * <b>转交对模型是「黑盒」</b>：主智能体只拿到子智能体的最终产出，看不到它的工具调用与中间过程。指令必须自包含
 * （子智能体看不到用户与原智能体的对话），这一点写进了工具描述，否则模型会写出「按上面说的做」这类无主语的指令。
 */
@Slf4j
@Service
public class SubAgentTool {

    /** 工具名：{@code agent.tools_json} 白名单按此匹配，也是前端「工具装配」里显示的名字。 */
    public static final String TOOL_NAME = "call_agent";

    /** 前端工具分组名：沿用「分组 = 来源类名」的既有约定（与 ToolProvider / MCP 一致）。 */
    public static final String GROUP = "SubAgentTool";

    /** 子智能体产出注入主智能体上下文的字符上限：超出保留前段并显式标注，避免单次转交把上下文挤爆。 */
    private static final int MAX_RESULT_CHARS = 6000;

    /**
     * 入参 schema：手写而非反射生成——参数只有两个且语义固定，写死比引一套 schema 生成器更直白。
     * <p>
     * 用 text block 承载（内部双引号无需转义）后 {@code strip()} 掉首尾空白，保证作为 JSON 解析时
     * 不含任何意外换行或缩进。
     */
    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{"agentCode":{"type":"string","description":"目标智能体的 agent_code，取值见工具描述里列出的可用智能体清单"},"instruction":{"type":"string","description":"交给该智能体的完整任务说明。它看不到你与用户的对话，因此必须自包含：写清背景、要产出什么、以什么形态产出"}},"required":["agentCode","instruction"]}
            """.strip();

    private final AgentMapper agentMapper;
    private final PromptService promptService;
    private final ToolRegistry toolRegistry;
    private final LlmUsageService llmUsageService;
    /** 无会话记忆的客户端：转交是「一次子任务调用」，不该读写宿主会话的历史。 */
    private final ChatClient subAgentClient;

    public SubAgentTool(AgentMapper agentMapper,
                        PromptService promptService,
                        ToolRegistry toolRegistry,
                        LlmUsageService llmUsageService,
                        ChatClient.Builder chatClientBuilder,
                        ToolUsageLoggingAdvisor toolUsageLoggingAdvisor) {
        this.agentMapper = agentMapper;
        this.promptService = promptService;
        this.toolRegistry = toolRegistry;
        this.llmUsageService = llmUsageService;
        // 只挂工具调用日志 Advisor；RoundTraceAdvisor 需要宿主 trace 上下文，转交过程不并入宿主追踪
        this.subAgentClient = chatClientBuilder.clone()
                .defaultAdvisors(toolUsageLoggingAdvisor)
                .build();
    }

    /**
     * 前端「工具装配」清单里的元信息（只登记名字与说明，<b>不注册实例</b>——实例必须按调用方每轮现构）。
     */
    public static ToolRegistry.ToolInfo toolInfo() {
        return new ToolRegistry.ToolInfo(TOOL_NAME,
                "把子任务转交给另一个智能体执行（需在白名单中显式声明本工具才会挂载；只支持一层转交）",
                GROUP);
    }

    /**
     * 为 {@code caller} 现场构造本轮的转交工具。
     *
     * @param caller         调用方智能体（用于排除「转交给自己」并生成候选清单）
     * @param conversationId 当前会话 id，仅用于成本流水归属（可为 null）
     */
    public ToolCallback build(Agent caller, String conversationId) {
        List<Agent> candidates = listCandidates(caller);
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description(buildDescription(candidates))
                .inputSchema(INPUT_SCHEMA)
                .build();
        return new DelegateCallback(definition, caller, conversationId);
    }

    /** 候选智能体：全部智能体里排除调用方自己（未落库、无 id 的排除在外）。 */
    private List<Agent> listCandidates(Agent caller) {
        List<Agent> all;
        try {
            all = agentMapper.selectList(new QueryWrapper<Agent>().orderByDesc("updated_at"));
        } catch (Exception e) {
            log.warn("转交工具：读取智能体清单失败，本轮按「无候选」处理：{}", e.getMessage());
            return List.of();
        }
        if (all == null) return List.of();
        return all.stream()
                .filter(a -> a.getId() != null)
                .filter(a -> caller == null || !a.getId().equals(caller.getId()))
                .toList();
    }

    /** 工具描述：固定说明 + 动态候选清单（模型据此才知道「能转给谁」，因此必须每轮现算）。 */
    private static String buildDescription(List<Agent> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("把当前用户目标中某个子任务，转交给另一个更合适的智能体执行。\n\n");
        if (candidates.isEmpty()) {
            sb.append("当前没有其他可转交的智能体，不要调用本工具，请直接回答。");
            return sb.toString();
        }
        sb.append("可用智能体：\n");
        for (Agent a : candidates) {
            sb.append("- ").append(a.getName()).append(" (").append(a.getAgentCode()).append(")");
            if (a.getDescription() != null && !a.getDescription().isBlank()) {
                sb.append("：").append(a.getDescription());
            }
            sb.append("\n");
        }
        sb.append("\n使用要点：\n")
                .append("1. 只在子任务确实需要对方专长时调用；自己就能答的不要转交。\n")
                .append("2. 一次只转交一个子任务；需要多方协作时依次多次调用，把前一次产出带进下一次的 instruction。\n")
                .append("3. instruction 必须自包含——对方看不到你与用户的对话，写完它就能独立开工。\n")
                .append("4. 转交是黑盒：你只拿到对方的最终产出，看不到它的中间过程与工具调用。\n")
                .append("5. 不要把任务转交给自己。");
        return sb.toString();
    }

    /** 解析入参 → 校验 → 调子智能体 → 返回产出文本。任何失败都返回可读的字符串（工具结果不该向模型抛异常）。 */
    String delegate(Agent caller, String conversationId, String toolInput) {
        JSONObject in;
        try {
            in = JSONUtil.parseObj(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
        } catch (Exception e) {
            return "调用失败：参数不是合法 JSON 对象，应为 {\"agentCode\":\"...\",\"instruction\":\"...\"}";
        }
        String code = in.getStr("agentCode");
        String instruction = in.getStr("instruction");
        if (code == null || code.isBlank()) return "调用失败：缺少必填参数 agentCode（目标智能体编码）。";
        if (instruction == null || instruction.isBlank()) return "调用失败：缺少必填参数 instruction（交给目标智能体的任务说明）。";

        Agent target = findByCode(code.trim());
        if (target == null) {
            return "调用失败：智能体编码不存在 —— " + code + "。可用编码："
                    + listCandidates(caller).stream().map(Agent::getAgentCode).toList();
        }
        if (caller != null && target.getId() != null && target.getId().equals(caller.getId())) {
            return "调用失败：不能把任务转交给自己（" + code + "）。请直接完成该子任务。";
        }

        log.info("智能体转交：{} → {}（会话={}）", caller == null ? "-" : caller.getAgentCode(),
                target.getAgentCode(), conversationId);
        try {
            // 子智能体挂它自己的静态工具（不含本工具 ⇒ 不会继续向下转交，见类注释「只做一层」）
            ToolCallback[] subTools = toolRegistry.resolve(target.getToolsJson());
            String system = promptService.resolveSystemPrompt(target)
                    + "\n\n[转交说明] 你正在处理另一个智能体转交来的子任务。请只完成该子任务，"
                    + "直接给出可被复用的结果，不要复述任务说明、不要寒暄。";
            ChatClient.ChatClientRequestSpec spec = subAgentClient.prompt().system(system).user(instruction);
            if (subTools.length > 0) spec = spec.tools((Object[]) subTools);
            ChatResponse response = spec.call().chatResponse();
            // 转交同样是一次真实模型往返，计入全量成本口径（此前若漏记，成本看板会少算这一块）
            llmUsageService.recordAsync("SUBAGENT", conversationId, null, response);
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return "转交执行失败：智能体「" + target.getName() + "」未返回内容。";
            }
            String out = response.getResult().getOutput().getText();
            if (out == null || out.isBlank()) {
                return "转交执行失败：智能体「" + target.getName() + "」返回空内容。";
            }
            return truncate(out);
        } catch (Exception e) {
            log.warn("智能体转交执行失败：{} → {}，错误={}", caller == null ? "-" : caller.getAgentCode(),
                    target.getAgentCode(), e.getMessage(), e);
            return "转交执行失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * 按编码查智能体（直接走 Mapper，理由见类注释的循环依赖说明）。
     */
    private Agent findByCode(String code) {
        return agentMapper.selectOne(new QueryWrapper<Agent>().eq("agent_code", code).last("LIMIT 1"));
    }

    /** 产出超长时保留前段 + 显式标注：静默截断会让「子智能体答得不全」变成查不出原因的谜。 */
    private static String truncate(String text) {
        if (text.length() <= MAX_RESULT_CHARS) return text;
        int omitted = text.length() - MAX_RESULT_CHARS;
        log.warn("智能体转交产出超上限已截断：{} → {} 字符（省略 {}）", text.length(), MAX_RESULT_CHARS, omitted);
        return text.substring(0, MAX_RESULT_CHARS)
                + "\n…（该智能体产出过长已截断，后续省略 " + omitted + " 字符）";
    }

    /** 每轮现构的回调：入参 schema 固定，而候选清单随库变化，因此 ToolDefinition 必须随实例一起重建。 */
    private final class DelegateCallback implements ToolCallback {

        private final ToolDefinition definition;
        private final Agent caller;
        private final String conversationId;

        private DelegateCallback(ToolDefinition definition, Agent caller, String conversationId) {
            this.definition = definition;
            this.caller = caller;
            this.conversationId = conversationId;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return delegate(caller, conversationId, toolInput);
        }
    }
}
