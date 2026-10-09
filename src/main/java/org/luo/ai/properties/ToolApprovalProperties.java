package org.luo.ai.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 工具审批闸门配置（{@code agent.tool-approval.*}）：声明「哪些工具执行前要用户点头」。
 * <p>
 * <b>默认关闭</b>：{@code enabled=false} 或 {@code tools} 为空时，{@code ChatComposer} 不做任何包装，
 * 行为与不装这套机制完全一致（连一次额外查询都没有）。
 * <p>
 * <b>为什么由配置声明、而不是给 agent 加一列</b>：会伤人的工具（查库、外呼、转交）是<b>工具本身的属性</b>，
 * 不是某个智能体的属性 —— 同一个 {@code query} 挂在哪个智能体上都一样危险，而给 agent 加列意味着
 * 「新建的智能体默认不设防」，漏配一次就静默敞口。配置一处、全员生效，代价是暂时做不到「A 智能体要批、
 * B 智能体不用批」（真需要时再补 per-agent 覆盖，语义是「覆盖全局」而非「默认不设防」）。
 * <p>
 * <b>为什么不做成「每次都问」</b>：审批落在会话粒度（{@code tool_approval} 表 uk_conv_tool），
 * 一次授权在本会话内持续有效，过期时间由 {@code expire-minutes} 控制。逐次打断的审批最后一定会被
 * 用户点成肌肉记忆，闸门也就成了摆设。
 *
 * @param enabled       总开关（默认 false：不拦截任何工具）
 * @param tools         需要审批的工具名清单（与 {@code agent.tools_json} 白名单同名；含动态工具
 *                      {@code call_agent} / {@code handoff_agent}）
 * @param expireMinutes 一次授权的有效期（分钟，默认 30）；{@code 0} = 永不过期。
 *                      只作用于「已批准」：过期后该工具自动回到待确认（不留下长期敞口）。
 *                      「已拒绝」不受它影响 —— 用户说过不行就一直不行，只有显式撤销才改变。
 */
@ConfigurationProperties(prefix = "agent.tool-approval")
public record ToolApprovalProperties(Boolean enabled, List<String> tools, Integer expireMinutes) {

    /** 默认授权有效期（分钟）。 */
    public static final int DEFAULT_EXPIRE_MINUTES = 30;

    public ToolApprovalProperties {
        if (enabled == null) enabled = false;
        // 去空去重：配置里手写清单，留个空行 / 重复项不该变成「多一条永不命中的规则」或告警噪音
        tools = tools == null ? List.of() : tools.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (expireMinutes == null || expireMinutes < 0) expireMinutes = DEFAULT_EXPIRE_MINUTES;
    }

    /** 闸门是否实际生效（总开关开 且 清单非空）。 */
    public boolean on() {
        return Boolean.TRUE.equals(enabled) && !tools.isEmpty();
    }

    /** 该工具是否在清单内（= 执行前要用户点头）。 */
    public boolean gated(String toolName) {
        return toolName != null && tools.contains(toolName);
    }
}
