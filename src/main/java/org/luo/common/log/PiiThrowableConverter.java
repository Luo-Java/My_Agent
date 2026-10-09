package org.luo.common.log;

import ch.qos.logback.classic.pattern.ExtendedThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.luo.common.util.PiiMasker;

/**
 * 脱敏版异常栈转换器：继承 {@link ExtendedThrowableProxyConverter}，保留其全部换行 / 缩进逻辑
 * （包括异常栈与前面消息之间的「前置空白分隔」、每行换行、末尾换行），仅对渲染结果做 PII 脱敏。
 *
 * <b>为什么不能写 {@code %pii(%wEx)}</b>：{@code %pii} 是 {@code CompositeConverter}，包住 {@code %wEx} 后，
 * composite 的 {@code transform} 接收的是子模式渲染结果，而 {@code ExtendedThrowableProxyConverter} 在
 * 「消息与异常栈之间」的前置换行会丢失，表现为「消息与异常栈挤在一行，不换行」。继承而非包裹
 * 才能保留原生换行。实证见 {@code .workbuddy/tools/PiiLogbackProbe4}。
 *
 * <b>开关</b>：与 {@link PiiMaskingConverter} 一致，走 logback 上下文属性 {@code piiMaskEnabled}
 * （由 {@code logback-spring.xml} 的 {@code <springProperty>} 从 {@code agent.pii.enabled} 注入）。
 * 但脱敏是安全侧，这里直接调用 {@link PiiMasker#mask}（缺省即脱敏），不二次判断开关——
 * 因为 {@code PiiMaskingConverter} 已经按开关决定是否脱敏消息，异常栈与消息同开同关即可，
 * 且 {@code PiiMasker.mask} 对无命中文本幂等返回原串，关闭开关时调用方应直接渲染不脱敏。
 * 为与消息侧行为严格一致，这里也读取 {@code piiMaskEnabled}，缺省按开启处理。
 */
public class PiiThrowableConverter extends ExtendedThrowableProxyConverter {

    private static final String ENABLED_PROPERTY = "piiMaskEnabled";

    @Override
    public String convert(ILoggingEvent event) {
        String rendered = super.convert(event);
        return enabled() ? PiiMasker.mask(rendered) : rendered;
    }

    private boolean enabled() {
        var ctx = getContext();
        if (ctx == null) {
            return true;
        }
        String v = ctx.getProperty(ENABLED_PROPERTY);
        return v == null || v.isBlank() || Boolean.parseBoolean(v.trim());
    }
}
