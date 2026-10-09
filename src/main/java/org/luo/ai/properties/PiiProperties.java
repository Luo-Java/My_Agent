package org.luo.ai.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据侧脱敏配置（{@code agent.pii.*}）：控制 {@link org.luo.common.util.PiiMasker} 在<b>持久化边界</b>上是否生效。
 * <p>
 * <b>为什么只有一个开关，而不是「消息 / 追踪 / 日志」三个</b>：{@code chat_message.content} 与
 * {@code agent_trace.user_message} 之间存在一条<b>跨表逐字匹配</b>契约 ——
 * 反馈转回归用例与点踩强制自评都用 {@code feedback.user_input} 去 {@code eq} 匹配 {@code agent_trace.user_message}，
 * 而 {@code user_input} 是从 {@code chat_message} 里取的上一条用户消息。两处若可分别开关，就会出现
 * 「一边脱敏、一边不脱敏」，匹配<b>静默失效</b>：表现是转用例偶尔失败、日志只留一句 WARN，极难归因。
 * 故三处同开同关，一致性由结构保证而不是靠人记住。
 * <p>
 * <b>默认开启</b>：数据保护应当是默认态。关闭的合理理由是「内部部署且业务需要模型看到完整号码」
 * （见 {@link #enabledOn()} 的副作用说明），代价是明文落库。
 *
 * @param enabled 是否脱敏（默认 true）
 */
@ConfigurationProperties(prefix = "agent.pii")
public record PiiProperties(Boolean enabled) {

    public PiiProperties {
        if (enabled == null) enabled = true;
    }

    /**
     * 是否脱敏（默认 true）。
     * <p>
     * <b>唯一的运行期副作用</b>：{@code chat_message} 脱敏后会被 {@code DbChatMemory.get} 读回、
     * 注入下一轮 prompt —— 即模型此后<b>看不到完整号码</b>（只看到 {@code 138****8000}）。工具调用与检索
     * 在脱敏发生<b>之前</b>就已执行完毕，故不受影响；{@code agent_trace} 只用于展示与排查，同样不受影响。
     * 因此「要不要脱敏」实际是在问：<b>业务是否需要模型在后续轮次里复述完整号码</b>。
     */
    public boolean enabledOn() {
        return Boolean.TRUE.equals(enabled);
    }
}
