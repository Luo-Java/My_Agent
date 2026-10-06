package org.luo.ai.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 内容安全配置（{@code agent.safety.*}）：对话链路的输入/输出侧基础护栏。
 * <p>
 * <b>默认关闭</b>（{@code enabled = false}）：关着时输入输出两侧都完全短路，不编译规则、不扫文本，
 * 行为与改造前一致。
 * <p>
 * <b>为什么是「规则命中即拦截」而不是「提示模型注意安全」</b>：后者属于提示词行为工程，模型不给这份
 * 提示词就不存在；而本配置表落的是<b>平台侧可确定执行的判定</b>——命中规则一定被拦下，与模型是否听话无关。
 * 这是两者最本质的区别。
 * <p>
 * <b>拦截口径（不许静默降级）</b>：
 * <ul>
 *   <li><b>输入侧命中</b> → 明确拒绝：同步接口抛 422（{@code CONTENT_BLOCKED}），流式接口推一条
 *       {@code error} 事件；本轮<b>不产生任何模型调用</b>，绝不「悄悄过滤掉敏感词再问模型」；</li>
 *   <li><b>输出侧命中</b> → 明确替换为 {@link #blockedMessage()}（前端照常显示该文案）并落 WARN 日志，
 *       绝不「假装回答成功、内容却已被悄悄改写」；</li>
 *   <li>未命中 → 原样放行。</li>
 * </ul>
 * <p>
 * <b>规则语法</b>：Java 正则（{@link java.util.regex.Pattern}），逐条 {@code find()} 匹配；规则错误在启动/首次
 * 使用时即被记录并跳过该条（不静默失效——日志会写明哪条规则编译失败）。规则文本<b>只进配置</b>，
 * 命中记录也只写规则说明与会话 id，<b>不落用户原文</b>（避免把被拦内容二次留存）。
 * <p>
 * <b>已知边界（如实标注）</b>：正则是<b>字面/模式匹配</b>，不是语义审核 —— 换个说法、用同音字或跨语种
 * 表达就能绕过。它挡的是「明确违规的固定表达」，不是「所有违规意图」；要语义级审核需接入专门的内容审核
 * 服务，那是另一件事，不在本配置范围内。
 *
 * @param enabled       是否启用护栏（默认 false=不启用，两侧全部短路）
 * @param inputPatterns 输入侧拦截规则（正则列表，任一命中即拒绝；空列表=输入侧不拦）
 * @param outputPatterns 输出侧拦截规则（正则列表，任一命中即替换为 blockedMessage；空列表=输出侧不拦）
 * @param blockedMessage 命中时的提示文案（输入侧用于 422/error 事件；输出侧用于替换正文）
 * @param maxInputChars 单条用户输入长度上限（字符，默认 0=不限）；超限按「输入侧拦截」同一口径处理
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
