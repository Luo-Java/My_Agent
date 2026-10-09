package org.luo.ai.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通知外发配置（{@code agent.notify.*}）：把落库的通知再往站外推一份（如钉钉 / 企业微信机器人）。
 * <p>
 * <b>默认不外发</b>：{@code webhook-url} 为空时，通知只落 {@code notification} 表、由前端轮询展示，
 * 不产生任何外部请求 —— 行为与不配这一节完全一致。
 * <p>
 * 红线：外发是<b>尽力而为</b>，失败只记 WARN，<b>绝不影响</b>产生通知的业务动作（定时任务不能因为
 * 钉钉挂了就算失败）。URL 属敏感信息（内含 access_token），故与 API Key 同处置：<b>不写进主 yaml</b>，
 * 放 {@code application-local.yaml} 或环境变量。
 *
 * @param webhookUrl   机器人 webhook 地址（为空 = 不外发）
 * @param connectTimeoutMs 连接超时（毫秒，默认 3000）
 * @param readTimeoutMs    读超时（毫秒，默认 5000）
 * @param minLevel     最低外发级别（默认 WARN）：低于该级别的通知只落库不外发，避免把 INFO 刷到群里
 */
@ConfigurationProperties(prefix = "agent.notify")
public record NotifyProperties(String webhookUrl, Integer connectTimeoutMs, Integer readTimeoutMs, String minLevel) {

    /** 默认最低外发级别。 */
    public static final String DEFAULT_MIN_LEVEL = "WARN";

    public NotifyProperties {
        if (webhookUrl == null) webhookUrl = "";
        if (connectTimeoutMs == null || connectTimeoutMs <= 0) connectTimeoutMs = 3000;
        if (readTimeoutMs == null || readTimeoutMs <= 0) readTimeoutMs = 5000;
        if (minLevel == null || minLevel.isBlank()) minLevel = DEFAULT_MIN_LEVEL;
    }

    /** 是否配置了外发地址。 */
    public boolean webhookOn() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }
}
