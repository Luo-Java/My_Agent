package org.luo.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.luo.ai.mapper.QuotaMapper;
import org.luo.ai.properties.QuotaProperties;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 成本配额服务：计算当前用户的当日 token 用量与配额状态，供对话入口做闸门判断。
 * <p>
 * <b>只读、无副作用</b>：本服务不记录用量（用量来自 {@code agent_trace} / {@code llm_usage} 两张流水表，
 * 由对话链路自身写入），只回答「这个用户今天花了多少、还剩多少、还能不能继续」。
 * <p>
 * <b>配置关闭时不查库</b>：{@code agent.quota.enabled=false}（默认）时直接返回「未启用」，连一条 SQL 都不发，
 * 保证「没开配额」这条路与改造前完全等价。
 */
@Slf4j
@Service
public class QuotaService {

    private final QuotaProperties properties;
    private final QuotaMapper quotaMapper;

    public QuotaService(QuotaProperties properties, QuotaMapper quotaMapper) {
        this.properties = properties;
        this.quotaMapper = quotaMapper;
    }

    /**
     * 当前用户的配额状态。
     *
     * @param userId 用户 ID
     * @param admin  是否具备 ADMIN 角色（由 HTTP 线程从 {@code AuthContext} 取出后传入——
     *               配额检查发生在异步执行之前，但保持「身份只在 HTTP 线程读」的一致约定）
     */
    public QuotaStatus status(Long userId, boolean admin) {
        if (!properties.enabledOn()) {
            return QuotaStatus.disabled();
        }
        long limit = properties.dailyTokens();
        if (admin && properties.exemptAdminsOn()) {
            // 豁免者不查库：ADMIN 被自己配的额度锁在门外将无法再通过界面把额度调回来
            return QuotaStatus.exempt(limit);
        }
        LocalDateTime since = LocalDate.now().atStartOfDay();
        long used;
        try {
            used = quotaMapper.sumUserTokensSince(userId, since);
        } catch (Exception e) {
            // 统计失败 → WARN + 放行（fail-open），这是刻意的：配额是治理手段而非正确性保障，
            // 一条统计 SQL 出问题不该让所有人都发不出消息。此处不是「静默降级」——日志明确记了原因。
            log.warn("配额统计失败，本轮放行：userId={}，错误={}", userId, e.getMessage());
            return new QuotaStatus(true, false, 0, limit, false, false);
        }
        return new QuotaStatus(true, false, used, limit,
                used >= limit, used >= (long) (limit * properties.warnRatio()));
    }

    /**
     * 配额状态快照。
     *
     * @param enabled   配额是否启用（false 时其余字段无意义）
     * @param exempt    是否为豁免账号（ADMIN 且开了 {@code exempt-admins}）
     * @param used      今日已用 token
     * @param limit     今日上限
     * @param exhausted 是否已用尽（调用方据此拒绝本轮）
     * @param warn      是否达到预警线（未用尽但接近上限，调用方据此播一条提示但<b>不</b>拦截）
     */
    public record QuotaStatus(boolean enabled, boolean exempt, long used, long limit, boolean exhausted,
                              boolean warn) {

        static QuotaStatus disabled() {
            return new QuotaStatus(false, false, 0, 0, false, false);
        }

        static QuotaStatus exempt(long limit) {
            return new QuotaStatus(true, true, 0, limit, false, false);
        }

        /** 剩余可用 token（用尽时为 0，不出现负数）。 */
        public long remaining() {
            return Math.max(0, limit - used);
        }

        /** 超限时的说明文案；未超限返回 null。措辞含「已用 / 上限 / 何时重置」三要素，不留给用户猜。 */
        public String exhaustedMessage() {
            return exhausted
                    ? "已超出本日 token 配额（已用 " + used + " / 上限 " + limit + "），配额将于明日 00:00 重置。"
                    : null;
        }

        /** 接近上限时的提示文案（只提示、不拦截）。 */
        public String warnMessage() {
            return "⚠️ 本日 token 用量已达 " + used + " / " + limit + "，接近配额上限。";
        }
    }
}
