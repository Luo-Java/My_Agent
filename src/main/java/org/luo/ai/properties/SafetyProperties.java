package org.luo.ai.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 内容安全配置（{@code agent.safety.*}）：对话链路的输入/输出侧基础护栏，<b>默认关闭</b>（关着时两侧完全短路）。
 * <p>
 * 红线：命中即<b>明确拒绝、绝不静默降级</b> —— 输入侧抛 422 / 推 {@code error} 事件且本轮零模型调用，输出侧替换为
 * {@link #blockedMessage()} 并落 WARN。命中记录<b>只写规则说明与会话 id，不落用户原文</b>。
 * 正则是字面/模式匹配而非语义审核（换说法、同音字、跨语种即可绕过）；要语义级审核须另接内容审核服务。
 *
 * @param enabled        是否启用护栏（默认 false=两侧全部短路）
 * @param inputPatterns  输入侧拦截规则（正则列表，逐条 find() 命中即拒绝；空=不拦）
 * @param outputPatterns 输出侧拦截规则（任一命中即替换为 blockedMessage；空=不拦）
 * @param blockedMessage 命中时的提示文案（输入侧用于 422 / error 事件，输出侧用于替换正文）
 * @param maxInputChars  单条输入长度上限（默认 0=不限），超限按输入侧拦截同一口径处理
 */
@ConfigurationProperties(prefix = "agent.safety")
public record SafetyProperties(Boolean enabled, List<String> inputPatterns, List<String> outputPatterns,
                               String blockedMessage, Integer maxInputChars) {

    /** 默认拦截文案（配置未给或为空白时使用）。 */
    public static final String DEFAULT_BLOCKED_MESSAGE = "该内容不符合使用规范，已被安全策略拦截";

    public SafetyProperties {
        if (enabled == null) enabled = false;
        if (inputPatterns == null) inputPatterns = List.of();
        if (outputPatterns == null) outputPatterns = List.of();
        if (blockedMessage == null || blockedMessage.isBlank()) blockedMessage = DEFAULT_BLOCKED_MESSAGE;
        if (maxInputChars == null || maxInputChars < 0) maxInputChars = 0;
    }

    /** 护栏是否生效（显式开启）。 */
    public boolean enabledOn() {
        return Boolean.TRUE.equals(enabled);
    }

    /** 输入长度上限（{@code <=0} 表示不限）。 */
    public int maxInputCharsLimit() {
        return maxInputChars == null ? 0 : maxInputChars;
    }
}
