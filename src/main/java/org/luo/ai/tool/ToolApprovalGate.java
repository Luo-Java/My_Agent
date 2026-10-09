package org.luo.ai.tool;

import lombok.extern.slf4j.Slf4j;
import org.luo.ai.entity.Agent;
import org.luo.ai.properties.ToolApprovalProperties;
import org.luo.ai.service.ToolApprovalService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

/**
 * 工具审批闸门：把「声明要审批的工具」包一层，执行前先问过用户。
 * <p>
 * <b>为什么是装饰器而不是改各工具</b>：工具是 Spring AI 的 {@code ToolCallback}，一批来自 {@code @Tool} 反射、
 * 一批来自 MCP 远端（构造期才拿到实例）。要逐个改就得每个工具都记住「先查审批」，而<b>漏一个就是静默敞口</b>。
 * 统一在装配点（{@code ChatComposer} 挂工具那一刻）包一层，覆盖注解式、动态式与将来新增的一切工具，
 * 且被包的工具自己完全不知道闸门存在。
 * <p>
 * <b>形态是「拦截 + 落记录」，不是「挂起」</b>：工具调用跑在模型内部，没有中途等人点按钮的位置（详见
 * {@link ToolApprovalService} 类注释）。命中拦截时给模型一句可读的话让它收口，用户批准后重跑同一轮即可。
 * <p>
 * <b>两个 call 都要覆写</b>：{@code ToolCallback} 的 {@code call(input, context)} 默认只是转发到
 * {@code call(input)}，但 {@code @Tool} 反射出的实现会覆写它（逻辑在带 context 那一支）。只覆写单参版本
 * 会让闸门被绕过 —— 这是个不报错、只失效的坑。
 */
@Slf4j
@Component
public class ToolApprovalGate {

    private final ToolApprovalProperties props;
    private final ToolApprovalService service;

    public ToolApprovalGate(ToolApprovalProperties props, ToolApprovalService service) {
        this.props = props;
        this.service = service;
    }

    /**
     * 一次装配的闸门上下文：工具回调只拿得到这些（拿不到 {@code AuthContext} —— 它跑在模型调用链上，
     * 且规划步骤还可能并行跑在其他线程上）。归属不靠它判定，靠 {@code JOIN conversation}。
     *
     * @param conversationId 当前会话（落记录与重跑都靠它）
     * @param agentId        发起调用的智能体（可空）
     * @param userMessage    触发本轮的<b>用户原话</b>：批准后照它重跑同一轮，不必让用户再打一遍
     */
    public record GateContext(String conversationId, Long agentId, String userMessage) {
    }

    /** 闸门是否生效（配置关闭时连上下文都不建，调用方据此完全短路）。 */
    public boolean enabled() {
        return props.on();
    }

    /**
     * 造一次装配的闸门上下文。闸门关闭或会话缺失时返回 {@code null}（=不装闸门）。
     */
    public GateContext context(String conversationId, Agent agent, String message) {
        if (!enabled() || conversationId == null || conversationId.isBlank()) return null;
        return new GateContext(conversationId, agent == null ? null : agent.getId(), message);
    }

    /**
     * 给一批工具装闸门：清单内的包一层，其余原样返回；一个都没命中时返回原数组（不制造无谓的包装层）。
     */
    public ToolCallback[] wrap(ToolCallback[] tools, GateContext ctx) {
        if (ctx == null || tools == null || tools.length == 0 || !enabled()) return tools;
        ToolCallback[] out = new ToolCallback[tools.length];
        boolean any = false;
        for (int i = 0; i < tools.length; i++) {
            ToolCallback cb = tools[i];
            ToolDefinition def = cb.getToolDefinition();
            String name = def == null ? null : def.name();
            if (props.gated(name)) {
                out[i] = new GatedCallback(cb, name, ctx);
                any = true;
            } else {
                out[i] = cb;
            }
        }
        return any ? out : tools;
    }

    /**
     * 装闸门的工具回调：委托给原回调，但执行前先过闸门。
     * <p>
     * 除两个 {@code call} 外还转发 {@code getToolDefinition} 与 {@code getToolMetadata}：后者承载
     * 「返回值是否直接回用户」这类语义，漏转发会静默改变工具行为。
     */
    private final class GatedCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final String toolName;
        private final GateContext ctx;

        private GatedCallback(ToolCallback delegate, String toolName, GateContext ctx) {
            this.delegate = delegate;
            this.toolName = toolName;
            this.ctx = ctx;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            String blocked = service.gate(ctx.conversationId(), ctx.agentId(), toolName, toolInput, ctx.userMessage());
            return blocked != null ? blocked : delegate.call(toolInput);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            String blocked = service.gate(ctx.conversationId(), ctx.agentId(), toolName, toolInput, ctx.userMessage());
            return blocked != null ? blocked : delegate.call(toolInput, toolContext);
        }
    }
}
