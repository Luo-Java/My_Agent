package org.luo.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.luo.ai.dto.ObservabilitySummary;
import org.luo.ai.dto.ObservabilitySummary.Overview;
import org.luo.ai.dto.ObservabilitySummary.SelfEvalSummary;
import org.luo.ai.entity.Notification;
import org.luo.ai.properties.AlertProperties;
import org.luo.system.constant.SysRoleCode;
import org.luo.system.entity.SysRole;
import org.luo.system.service.SysRoleService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阈值告警与周期报告：把 {@link ObservabilityService} 那些「要人主动去面板上看」的指标，变成越线主动推送
 * （落 {@code notification} 表，前端顶栏铃铛轮询展示，与 P0-2 共用同一个出口）。
 * <p>
 * <b>为什么用轮询而不是事件驱动</b>：这里判定的是<b>窗口聚合口径</b>（近 N 天的成功率、平均耗时），
 * 它没有单一的触发时刻 —— 本轮失败会让成功率变化，但那一下不足以判定「窗口质量不达标」，只有下一次聚合
 * 才知道。轮询聚合天然对得上这个语义；反过来，在每轮对话结束时顺手判一次，会把「聚合」做成一轮一算，
 * 既重复计算又与实际展示的窗口口径不一致（用户看到的成功率与告警依据的不是同一个数，是最坏的情况）。
 * <p>
 * <b>三道防噪</b>（告警一旦变成噪声就等于没有告警）：
 * <ol>
 *   <li>{@code min-sample}：样本不足不判定 —— 小流量下 1 次失败就是 33% 成功率；</li>
 *   <li>{@code cooldown-minutes}：指标是持续状态，冷却期内同一项只报一次；</li>
 *   <li>空窗口不推报告 —— 天天推一条「0 轮」会训练人忽略通知。</li>
 * </ol>
 * <b>绝不静默降级</b>：收件人查不到、报告无数据等情况一律记日志（WARN/INFO），
 * 「没告警」必须能区分「指标正常」与「功能没跑」。
 * <p>
 * <b>收件人是 ADMIN</b>：聚合口径跨会话、全站，属运营信息。一个 ADMIN 都查不到时改为广播并记 WARN ——
 * 广播不完美，但比「告警没人收到」强，且会在日志里留下明确线索。
 */
@Slf4j
@Service
public class AlertService {

    private final ObservabilityService observability;
    private final NotificationService notifications;
    private final SysRoleService roleService;
    private final AlertProperties props;

    /** 冷却表：告警项 key → 上次发出时刻（存内存，重启即清空 —— 宁可多报一次，不可因写失败永久静默）。 */
    private final Map<String, LocalDateTime> lastFired = new ConcurrentHashMap<>();

    public AlertService(ObservabilityService observability,
                        NotificationService notifications,
                        SysRoleService roleService,
                        AlertProperties props) {
        this.observability = observability;
        this.notifications = notifications;
        this.roleService = roleService;
        this.props = props;
        log.info("阈值告警初始化：开关={}，窗口={}天，最小样本={}轮，成功率下限={}，冷却={}分钟，周期报告={}",
                props.enabledOn(), props.windowDays(), props.minSample(), props.minSuccessRate(),
                props.cooldownMinutes(), props.reportOn());
    }

    /**
     * 轮询检查。间隔由 {@code agent.alert.check-interval-ms} 决定（默认 10 分钟）。
     * <p>
     * 整体吞异常：调度线程上抛异常会让 {@code @Scheduled} 任务静默停摆（Spring 不重启已停的定时任务），
     * 那是最难发现的一类故障 —— 表现为「从此再也不告警」，而日志里只有一行早先的堆栈。
     */
    @Scheduled(fixedDelayString = "${agent.alert.check-interval-ms:600000}", initialDelayString = "30000")
    public void check() {
        if (!props.enabledOn()) {
            return;
        }
        try {
            int sent = run();
            if (sent > 0) {
                log.info("阈值告警：本轮发出 {} 条（窗口 {} 天）", sent, props.windowDays());
            }
        } catch (Exception e) {
            log.error("阈值告警检查异常（下一轮继续）", e);
        }
    }

    /**
     * 跑一次检查，返回本次实际发出的告警条数。
     * <p>
     * 抽成公开方法（而不是把逻辑写在 {@link #check()} 里）是为了让「按需自检」与「定时轮询」走同一份判定 ——
     * 两条路径各写一份判定，迟早会漂移成「自检说没问题、实际不告警」。
     */
    public int run() {
        int days = props.windowDays();
        ObservabilitySummary summary = observability.summary(days);
        Overview ov = summary.overview();
        if (ov.rounds() < props.minSample()) {
            log.debug("阈值告警：窗口 {} 天仅 {} 轮，低于最小样本 {}，本轮不判定",
                    days, ov.rounds(), props.minSample());
            return 0;
        }
        List<Alert> alerts = evaluate(summary, days);
        if (alerts.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        List<Long> targets = adminIds();   // 只在真的有事要报时才查收件人
        int sent = 0;
        for (Alert a : alerts) {
            if (!cooledDown(a.key(), now)) {
                log.debug("阈值告警「{}」处于冷却期（{} 分钟）内，跳过", a.key(), props.cooldownMinutes());
                continue;
            }
            push(a, targets);
            lastFired.put(a.key(), now);
            sent++;
        }
        return sent;
    }

    /** 逐项比对阈值（三项衡量三个不同维度：可靠性、性能、回答质量）。 */
    private List<Alert> evaluate(ObservabilitySummary s, int days) {
        List<Alert> out = new ArrayList<>(3);
        Overview ov = s.overview();
        double rate = ov.successRate();
        double floor = props.minSuccessRate() * 100;
        if (rate < floor) {
            out.add(new Alert("success-rate", Notification.LEVEL_ERROR, "成功率跌破阈值",
                    String.format("近 %d 天共 %d 轮，失败 %d 轮，成功率 %.2f%%（阈值 %.2f%%）。",
                            days, ov.rounds(), ov.errors(), rate, floor)));
        }
        if (ov.avgElapsedMs() > props.maxAvgElapsedMs()) {
            out.add(new Alert("avg-elapsed", Notification.LEVEL_WARN, "平均耗时偏高",
                    String.format("近 %d 天平均耗时 %d ms（阈值 %d ms）。可在可观测面板查看最慢的几轮。",
                            days, ov.avgElapsedMs(), props.maxAvgElapsedMs())));
        }
        SelfEvalSummary se = s.selfEval();
        if (se.lowScore() > props.maxLowScoreCount()) {
            out.add(new Alert("low-score", Notification.LEVEL_WARN, "低分自评偏多",
                    String.format("近 %d 天有 %d 轮自评低于 %d 分（阈值 %d 轮；已自评 %d 轮，覆盖率 %.1f%%）。"
                                    + "可在可观测面板查看低分明细。",
                            days, se.lowScore(), s.lowScoreThreshold(), props.maxLowScoreCount(),
                            se.evaluated(), se.coverage())));
        }
        return out;
    }

    /**
     * 周期报告。cron 由 {@code agent.alert.report-cron} 决定（默认每天 09:00）。
     * 与告警共用「吞异常」纪律：报告失败不该让调度线程停摆。
     */
    @Scheduled(cron = "${agent.alert.report-cron:0 0 9 * * *}")
    public void report() {
        if (!props.enabledOn() || !props.reportOn()) {
            return;
        }
        try {
            sendReport();
        } catch (Exception e) {
            log.error("周期报告生成异常（下一周期继续）", e);
        }
    }

    /**
     * 生成并推送一份运行摘要，返回标题；窗口内无轮次时返回 {@code null}（不推空报告）。
     * 抽成公开方法的原因同 {@link #run()}。
     */
    public String sendReport() {
        int days = props.reportDays();
        ObservabilitySummary s = observability.summary(days);
        Overview ov = s.overview();
        if (ov.rounds() == 0) {
            log.info("周期报告：窗口 {} 天内没有任何轮次，不发报告", days);
            return null;
        }
        SelfEvalSummary se = s.selfEval();
        String title = "运行报告 · 近 " + days + " 天";
        String content = String.format(
                "%d 轮，成功率 %.2f%%（失败 %d 轮），平均耗时 %d ms，平均 token %d，token 合计 %d。%s",
                ov.rounds(), ov.successRate(), ov.errors(), ov.avgElapsedMs(), ov.avgTokens(), ov.totalTokens(),
                se.evaluated() == 0
                        ? "本窗口无自评样本。"
                        : String.format("自评覆盖 %.1f%%、均分 %.2f、低分 %d 轮。",
                                se.coverage(), se.avgScore(), se.lowScore()));
        // 报告不是告警，用「系统」类型（前端标签「系统」），别让日常摘要占用「告警」这个信号位
        deliver(adminIds(), Notification.TYPE_SYSTEM, Notification.LEVEL_INFO, title, content, "周期报告");
        return title;
    }

    /** 告警推送（走 ALERT 类型）。 */
    private void push(Alert a, List<Long> targets) {
        deliver(targets, Notification.TYPE_ALERT, a.level(), a.title(), a.content(), "阈值告警「" + a.key() + "」");
    }

    /**
     * 投递给全部 ADMIN；一个都没有则广播并记 WARN（见类注释）。
     * <p>
     * 收件人由调用方传进来而不是在这里现查：一次检查里可能有多条告警要发，每条都重查一遍收件人纯属浪费
     * （而且两次查询之间角色变了会导致同一批告警落在不同人身上，反而更难解释）。
     */
    private void deliver(List<Long> targets, String type, String level, String title, String content, String what) {
        if (targets == null || targets.isEmpty()) {
            log.warn("{} 找不到任何 ADMIN 收件人（请检查 sys_role / sys_user_role 是否已初始化），改为广播", what);
            notifications.push(null, type, level, title, content, null, null);
            return;
        }
        for (Long uid : targets) {
            notifications.push(uid, type, level, title, content, null, null);
        }
    }

    /** ADMIN 用户 ID 列表；查询失败按「无收件人」处理（走广播），不让告警本身成为新的故障点。 */
    private List<Long> adminIds() {
        try {
            SysRole admin = roleService.byCode(SysRoleCode.ADMIN);
            if (admin == null || admin.getId() == null) {
                return List.of();
            }
            List<Long> ids = roleService.userIdsOf(admin.getId());
            return ids == null ? List.of() : ids;
        } catch (Exception e) {
            log.warn("查询 ADMIN 收件人失败（本轮改为广播）：{}", e.getMessage());
            return List.of();
        }
    }

    /** 冷却判定：从未报过，或距上次报已超过冷却时长。 */
    private boolean cooledDown(String key, LocalDateTime now) {
        LocalDateTime last = lastFired.get(key);
        return last == null || !last.plusMinutes(props.cooldownMinutes()).isAfter(now);
    }

    /** 一条待发告警：{@code key} 只用于冷却去重，不对外展示。 */
    private record Alert(String key, String level, String title, String content) {
    }
}
