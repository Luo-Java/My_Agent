package org.luo.ai.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 成本配额配置（{@code agent.quota.*}）：给单个用户的<b>当日 token 消耗</b>装上界，防止一个账号把额度烧光。
 * <b>默认关闭</b>（关着时闸门完全短路，无额外查询与判断）。
 * <p>
 * 红线：① 按「<b>用户 × 自然日</b>」聚合而非按会话 —— 同一用户开十个会话，额度不会因此变成十份；
 * {@code agent_trace} / {@code llm_usage} 只记 {@code conversation_id}，用户归属靠 {@code JOIN conversation.user_id} 判定。
 * ② 超限必须<b>明确报错，不许静默降级</b>：说明「已用多少 / 上限多少 / 何时重置」，绝不默默转小模型、截断历史或假装成功。
 * ③ 已知边界：检查发生在每轮<b>开始前</b>，故单轮消耗可能让总量小幅超出上限（多步规划的用量无法事先预知）；
 * 评测跑批与记忆合并的后台异步调用不在闸门覆盖内 —— 其 token 计入用量但不被拦截。
 *
 * @param enabled      是否启用配额闸门（默认 false）
 * @param dailyTokens  每用户每自然日 token 上限（默认 200 万；非正数回落默认值）
 * @param warnRatio    预警比例（0~1，默认 0.9）：达到即在流里播一条提示，<b>不拦截</b>
 * @param exemptAdmins 是否豁免 ADMIN（默认 true）—— 否则管理员用满额度后将无法再通过界面调整配额
 */
@ConfigurationProperties(prefix = "agent.quota")
public record QuotaProperties(Boolean enabled, Long dailyTokens, Double warnRatio, Boolean exemptAdmins) {

    /** 每日默认上限（token）：按「一轮普通对话 2~5 千 token」估算，约合 400~1000 轮，够用且能挡住失控循环。 */
    public static final long DEFAULT_DAILY_TOKENS = 2_000_000L;

    /** 默认预警比例。 */
    public static final double DEFAULT_WARN_RATIO = 0.9;

    public QuotaProperties {
        if (enabled == null) enabled = false;
        if (dailyTokens == null || dailyTokens <= 0) dailyTokens = DEFAULT_DAILY_TOKENS;
        if (warnRatio == null || warnRatio <= 0 || warnRatio >= 1) warnRatio = DEFAULT_WARN_RATIO;
        if (exemptAdmins == null) exemptAdmins = true;
    }

    /** 配额是否生效（显式开启）。 */
    public boolean enabledOn() {
        return Boolean.TRUE.equals(enabled);
    }

    /** ADMIN 是否豁免。 */
    public boolean exemptAdminsOn() {
        return Boolean.TRUE.equals(exemptAdmins);
    }
}
