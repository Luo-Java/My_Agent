package org.luo.common.log;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Context;
import org.luo.common.util.PiiMasker;

/**
 * logback 普通转换器（无括号用法 {@code %pii}）：对日志消息做 PII 脱敏，等价于把官方默认的 {@code %m} 换成脱敏版。
 * <p>
 * <b>为什么是普通转换器、不是复合转换器 {@code %pii(%m)}</b>：logback 解析器在复合转换器（{@code %pii(...)} / {@code %clr(...)}）
 * 的右括号 {@code )} 之后<b>紧跟</b>的 {@code %n} / {@code %piiEx} 等会被当成<b>字面字符串</b>而非换行符 / 转换器，
 * 导致「控制台日志不换行」或异常栈脱敏失效（实证：{@code %m%n} 正常、{@code %pii(%m)%n} 却输出字面 %n）。
 * 改成无括号的普通 {@code %pii} 后，它后面紧跟的 {@code %n} / {@code %piiEx} 都跟在普通转换器之后，解析正常。
 * <p>
 * <b>为什么日志也要脱敏</b>：日志会被收集、落盘、转发，生命周期比数据库更长、访问面也更宽；
 * 一句 {@code log.info("收到消息：{}", text)} 就等于把用户随口说的号码永久留在了日志系统里。
 * <p>
 * 掩码对象用 {@link ILoggingEvent#getFormattedMessage()}（参数已代入的最终消息），而非 {@code getMessage()}
 * （占位符原文），因为 PII 往往就在带入参里。
 * <p>
 * 开关走 logback 上下文属性 {@code piiMaskEnabled}，由 {@code logback-spring.xml} 的 {@code <springProperty>}
 * 从 {@code agent.pii.enabled} 注入 —— <b>与数据库两处共用同一个配置键</b>，保证三处同开同关
 * （理由见 {@link org.luo.ai.properties.PiiProperties}）。取不到该属性时<b>按开启处理</b>：脱敏是安全侧，缺席应当保守。
 */
public class PiiMaskingConverter extends ClassicConverter {

    /** 与 {@code logback-spring.xml} 里 {@code <springProperty name="...">} 的 name 一致。 */
    private static final String ENABLED_PROPERTY = "piiMaskEnabled";

    @Override
    public String convert(ILoggingEvent event) {
        String msg = event.getFormattedMessage();
        return enabled() ? PiiMasker.mask(msg) : msg;
    }

    /**
     * 上下文属性缺失（非 {@code false}）一律按开启处理。
     * <p>
     * 这里不缓存：属性值由 Spring 在启动早期注入一次，之后不变；但 logback 可能在 Spring Environment
     * 就绪<b>之前</b>就先跑过几条日志（那时属性还没注入），缓存会把「未注入」固化成「关闭」。
     */
    private boolean enabled() {
        Context ctx = getContext();
        if (ctx == null) {
            return true;
        }
        String v = ctx.getProperty(ENABLED_PROPERTY);
        return v == null || v.isBlank() || Boolean.parseBoolean(v.trim());
    }
}
