package org.luo.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.dto.SaveScheduledTaskRequest;
import org.luo.ai.entity.Conversation;
import org.luo.ai.entity.Notification;
import org.luo.ai.entity.ScheduledTask;
import org.luo.ai.mapper.ScheduledTaskMapper;
import org.luo.ai.properties.ScheduleProperties;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.common.util.TextClip;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务服务：把「一句话 + 一个周期」变成自动执行的对话。
 * <p>
 * <b>执行体就是一次普通对话</b>：到点后以任务归属用户的身份，在承载会话里把 {@code prompt} 问一遍
 * （{@code ChatService.chat}）。于是智能路由 / 规划 / RAG / 记忆 / 工具 / 追踪全都原样继承 ——
 * 定时任务自己不需要知道这些能力的存在。这不是取巧，而是刻意：任何「另起一套执行逻辑」的做法都会
 * 让定时任务的产出与手动问一次的结果不一致，那种不一致极难排查。
 * <p>
 * <b>触发的三条纪律</b>：
 * <ol>
 *   <li><b>抢占式触发</b>：先把 {@code next_run_at} 推进到下一次（CAS：只有 {@code next_run_at} 仍等于
 *       读到的旧值时更新才生效），抢到的线程才执行。这样即便多实例部署、或某次执行超过了轮询间隔，
 *       同一时刻也只有一个执行者，且不会因为「上一轮还没跑完」而重复触发。</li>
 *   <li><b>单轮限量</b>：一次轮询最多执行 {@code max-per-tick} 个到期任务，避免同一时刻堆积把模型额度打满。</li>
 *   <li><b>执行失败不影响下一次</b>：失败只记状态与通知，{@code next_run_at} 早已推进，不会卡死。</li>
 * </ol>
 * <b>已知边界</b>：定时执行发生在调度线程上，<b>不经过</b> {@code ChatController} 的配额闸门（与
 * {@code QuotaProperties} 注释里「后台异步调用不被拦截」一致）；配额只保护交互式入口。
 */
@Slf4j
@Service
public class ScheduledTaskService {

    /** 下次触发时间的最远推算年限（防止 cron 表达式永不命中时无限逼近）。 */
    private static final int LOOKAHEAD_DAYS = 366;

    /** 名称列宽（DDL {@code VARCHAR(100)}）：写入前服务端校验，超长报 400 而不是让 MySQL 报 1406。 */
    private static final int NAME_MAX = 100;

    /** cron 列宽（DDL {@code VARCHAR(64)}）。合法但超长的 cron 是可以构造的（把日/月/周全枚举出来即可）。 */
    private static final int CRON_MAX = 64;

    private final ScheduledTaskMapper taskMapper;
    private final ScheduleProperties props;
    private final ConversationService conversationService;
    private final ChatService chatService;
    private final NotificationService notificationService;

    public ScheduledTaskService(ScheduledTaskMapper taskMapper,
                                ScheduleProperties props,
                                ConversationService conversationService,
                                ChatService chatService,
                                NotificationService notificationService) {
        this.taskMapper = taskMapper;
        this.props = props;
        this.conversationService = conversationService;
        this.chatService = chatService;
        this.notificationService = notificationService;
    }

    // ------------------------------------------------------------------
    // 定义管理（全部按 userId 隔离，越权一律 404）
    // ------------------------------------------------------------------

    /** 本人的定时任务（新→旧）。 */
    public List<ScheduledTask> list(Long userId) {
        return taskMapper.selectList(new LambdaQueryWrapper<ScheduledTask>()
                .eq(ScheduledTask::getUserId, userId).orderByDesc(ScheduledTask::getId));
    }

    /** 取本人任务，不存在或非本人一律 404（与「不存在」不可区分）。 */
    public ScheduledTask require(Long id, Long userId) {
        ScheduledTask t = id == null ? null : taskMapper.selectById(id);
        if (t == null || !userId.equals(t.getUserId())) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "定时任务不存在：" + id);
        }
        return t;
    }

    /** 新建或修改（带 id 即修改）。 */
    public ScheduledTask save(SaveScheduledTaskRequest req, Long userId) {
        String name = trim(req.name());
        String cron = trim(req.cron());
        String prompt = trim(req.prompt());
        if (name.isEmpty()) throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "任务名称不能为空");
        if (prompt.isEmpty()) throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "提示词不能为空");
        // 列宽必须服务端校验：前端的 maxlength 只是客户端属性，直接调 API 能绕过。
        // 不校验的后果是 insert 撞 VARCHAR(100) → MySQL 1406，而 save() 没有 try/catch，
        // 表现为「一个明显的入参问题被报成 500，且任务没创建」——错误形态会误导排查方向。
        if (name.length() > NAME_MAX) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "任务名称不能超过 " + NAME_MAX + " 个字符");
        }
        if (cron.length() > CRON_MAX) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "cron 表达式不能超过 " + CRON_MAX + " 个字符");
        }
        // cron 非法必须当场报错：否则任务存下了却永远不触发，表现得像「功能没生效」
        CronExpression expr = parseCron(cron);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = nextRun(expr, now);
        boolean enabled = req.enabled() == null || req.enabled();
        ScheduledTask t;
        if (req.id() == null) {
            t = new ScheduledTask();
            t.setUserId(userId);
            t.setCreatedAt(now);
        } else {
            t = require(req.id(), userId);
        }
        t.setName(name);
        t.setCron(cron);
        t.setAgentId(req.agentId());
        t.setPrompt(prompt);
        t.setEnabled(enabled);
        t.setNotifyOn(req.notifyOn() == null || req.notifyOn());
        // 关闭的任务不排下一次：避免「关着的任务」因为 next_run_at 过期而在重新开启时被立刻补跑
        t.setNextRunAt(enabled ? next : null);
        t.setUpdatedAt(now);
        if (req.id() == null) {
            taskMapper.insert(t);
        } else {
            taskMapper.updateById(t);
        }
        log.info("定时任务{}：id={}，name={}，cron={}，下次触发={}", req.id() == null ? "新建" : "更新",
                t.getId(), name, cron, t.getNextRunAt());
        return t;
    }

    /** 删除本人任务。 */
    public void delete(Long id, Long userId) {
        require(id, userId);
        taskMapper.deleteById(id);
    }

    /** 启用 / 停用（开启时重算下次触发时间，{@code next_run_at} 归零的历史状态就此复位）。 */
    public ScheduledTask toggle(Long id, boolean enabled, Long userId) {
        ScheduledTask t = require(id, userId);
        t.setEnabled(enabled);
        t.setNextRunAt(enabled ? nextRun(parseCron(t.getCron()), LocalDateTime.now()) : null);
        t.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(t);
        return t;
    }

    // ------------------------------------------------------------------
    // 手动触发
    // ------------------------------------------------------------------

    /**
     * 立即执行一次（不影响既定的下次触发时间）。
     * <p>
     * 刻意<b>不</b>抢 {@code next_run_at}：手动跑一次是「我想现在看看结果」，改掉排期会打乱用户设定的节奏。
     * 代价是可能与自动触发撞车 —— 那正是「用户主动要求」与「系统按期执行」两条独立意图，各自跑一次是对的。
     */
    public ScheduledTask runNow(Long id, Long userId) {
        ScheduledTask t = require(id, userId);
        execute(t);
        return require(id, userId);
    }

    // ------------------------------------------------------------------
    // 调度
    // ------------------------------------------------------------------

    /**
     * 轮询到期任务。间隔由 {@code agent.schedule.poll-interval-ms} 决定（默认 30 秒）。
     * <p>
     * 整体吞异常：调度线程上抛异常会让 {@code @Scheduled} 任务静默停摆（Spring 不重启已停的定时任务），
     * 那是最难发现的一类故障 —— 表现为「从此再也不执行」，而日志里只有一行早先的堆栈。
     */
    @Scheduled(fixedDelayString = "${agent.schedule.poll-interval-ms:30000}", initialDelayString = "10000")
    public void tick() {
        if (!props.enabledOn()) return;
        try {
            List<ScheduledTask> due = taskMapper.selectList(new LambdaQueryWrapper<ScheduledTask>()
                    .eq(ScheduledTask::getEnabled, true)
                    .isNotNull(ScheduledTask::getNextRunAt)
                    .le(ScheduledTask::getNextRunAt, LocalDateTime.now())
                    .orderByAsc(ScheduledTask::getNextRunAt)
                    .last("LIMIT " + props.maxPerTick()));
            for (ScheduledTask t : due) {
                dispatch(t);
            }
        } catch (Exception e) {
            log.error("定时任务轮询异常（下一轮继续）", e);
        }
    }

    /** 抢占并执行：推进 {@code next_run_at} 成功者才执行（CAS，见类注释的纪律一）。 */
    private void dispatch(ScheduledTask t) {
        LocalDateTime newNext = nextRun(parseCron(t.getCron()), LocalDateTime.now());
        LambdaUpdateWrapper<ScheduledTask> u = new LambdaUpdateWrapper<ScheduledTask>()
                .eq(ScheduledTask::getId, t.getId())
                .eq(ScheduledTask::getNextRunAt, t.getNextRunAt())
                .set(ScheduledTask::getNextRunAt, newNext)
                .set(ScheduledTask::getLastStatus, ScheduledTask.STATUS_RUNNING)
                .set(ScheduledTask::getLastRunAt, LocalDateTime.now());
        if (taskMapper.update(null, u) == 0) {
            // 别的线程已抢到（或任务刚被改动）——不是错误，静默跳过
            log.debug("定时任务未抢到执行权（已被其他线程处理或定义刚变更）：id={}", t.getId());
            return;
        }
        execute(t);
    }

    /**
     * 执行一次：定位（或创建）承载会话 → 走普通对话 → 回写状态 + 发通知。
     * <p>
     * 任何异常都被收敛成「状态 ERROR + 通知里说明原因」，绝不向外抛 —— 见 {@link #tick} 的注释。
     */
    private void execute(ScheduledTask t) {
        long started = System.currentTimeMillis();
        String status;
        String result;
        String convId = t.getConversationId();
        try {
            if (convId == null || conversationService.getConversation(convId) == null) {
                convId = openConversation(t);
            }
            String reply = chatService.chat(convId, t.getPrompt(), null, null, null, t.getUserId(), null);
            status = ScheduledTask.STATUS_OK;
            result = summarize(reply);
            log.info("定时任务执行完成：id={}，耗时={}ms，回复长度={}", t.getId(),
                    System.currentTimeMillis() - started, reply == null ? 0 : reply.length());
        } catch (Exception e) {
            status = ScheduledTask.STATUS_ERROR;
            result = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("定时任务执行失败：id={}，name={}", t.getId(), t.getName(), e);
        }
        try {
            taskMapper.update(null, new LambdaUpdateWrapper<ScheduledTask>()
                    .eq(ScheduledTask::getId, t.getId())
                    .set(ScheduledTask::getLastStatus, status)
                    .set(ScheduledTask::getLastResult, result)
                    .set(ScheduledTask::getConversationId, convId)
                    .set(ScheduledTask::getUpdatedAt, LocalDateTime.now()));
        } catch (Exception e) {
            log.error("定时任务状态回写失败：id={}", t.getId(), e);
        }
        if (Boolean.TRUE.equals(t.getNotifyOn())) {
            notify(t, status, result, convId);
        }
    }

    /** 为任务开一个承载会话（绑定其智能体、标题即任务名），并回填 conversationId。 */
    private String openConversation(ScheduledTask t) {
        Conversation c = conversationService.createConversation(t.getAgentId(), null, false, t.getUserId());
        conversationService.renameConversation(c.getId(), t.getName(), t.getUserId());
        taskMapper.update(null, new LambdaUpdateWrapper<ScheduledTask>()
                .eq(ScheduledTask::getId, t.getId())
                .set(ScheduledTask::getConversationId, c.getId()));
        log.info("定时任务创建承载会话：id={}，会话={}", t.getId(), c.getId());
        return c.getId();
    }

    /** 发一条完成通知（用户不在对话里，这是唯一能告知的通道）。 */
    private void notify(ScheduledTask t, String status, String result, String convId) {
        boolean ok = ScheduledTask.STATUS_OK.equals(status);
        notificationService.push(t.getUserId(), Notification.TYPE_SCHEDULED_TASK,
                ok ? Notification.LEVEL_INFO : Notification.LEVEL_ERROR,
                "定时任务「" + t.getName() + "」" + (ok ? "已完成" : "执行失败"),
                result, "CONVERSATION", convId);
    }

    /** 结果摘要：截断到配置长度（通知与列表都只需一眼看个大概，全文在会话里）。 */
    private String summarize(String reply) {
        if (reply == null) return "";
        String flat = reply.replaceAll("\\s+", " ").trim();
        // 必须走 TextClip：早先写的是 substring(0, max) + "…"，标记算在预算之外 ⇒
        // 落 last_result（VARCHAR(1000)）时超 1 字符 → MySQL 1406，
        // 而 execute() 的 catch 只会吞掉 ⇒ 状态永久停在 RUNNING 且不再发通知。
        return TextClip.clip(flat, props.maxResultChars());
    }

    // ------------------------------------------------------------------
    // cron
    // ------------------------------------------------------------------

    /** 解析 cron，非法直接 400（绑定失败但存下来 = 永远不触发，比报错更难查）。 */
    static CronExpression parseCron(String cron) {
        try {
            return CronExpression.parse(cron);
        } catch (Exception e) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "cron 表达式非法（需六段式「秒 分 时 日 月 周」）：" + cron + " —— " + e.getMessage());
        }
    }

    /** 从 {@code from} 起算的下一次触发时间；一年内无命中返回 null（任务保持但不再自动触发）。 */
    static LocalDateTime nextRun(CronExpression expr, LocalDateTime from) {
        LocalDateTime next = expr.next(from);
        if (next != null && next.isAfter(from.plusDays(LOOKAHEAD_DAYS))) {
            log.warn("cron 下次触发时间超过 {} 天（{}），按「无排期」处理", LOOKAHEAD_DAYS, next);
            return null;
        }
        return next;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
