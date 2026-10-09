package org.luo.ai.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.entity.Notification;
import org.luo.ai.mapper.NotificationMapper;
import org.luo.ai.properties.NotifyProperties;
import org.luo.ai.properties.PiiProperties;
import org.luo.common.util.PiiMasker;
import org.luo.common.util.TextClip;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知服务：落库 + （可选的）站外外发。是「用户不在对话里时发生的事」唯一能到达用户的通道。
 * <p>
 * 三条红线：
 * <ol>
 *   <li><b>落库绝不失败得静默</b>：push 的调用方都是「业务已经发生、需要告知用户」的场景（任务跑完、
 *       门禁劣化）。通知丢了一条不比业务失败，但也不能假装成功 —— 故落库异常只记日志（不向调用方抛），
 *       而外发异常记 WARN。业务动作本身<b>绝不因为通知失败而回滚</b>。</li>
 *   <li><b>外发是尽力而为</b>：webhook 挂了只记 WARN。定时任务的成功与否取决于任务本身，不取决于钉钉。</li>
 *   <li><b>外发低于 {@code min-level} 的级别不发</b>：INFO 全量外发会把群刷爆，等于把告警变成噪声。</li>
 * </ol>
 * 隔离靠 SQL：取列表一律「{@code user_id = ? OR user_id IS NULL}」—— 定向通知只本人可见，广播人人可见。
 */
@Slf4j
@Service
public class NotificationService {

    /** 列表返回条数上限（通知是「最近发生了什么」，不需要翻历史台账）。 */
    private static final int LIST_LIMIT = 50;

    /** 标题/正文落库截断长度（与 DDL 列宽一致）。 */
    private static final int TITLE_MAX = 200;
    private static final int CONTENT_MAX = 1000;

    private final NotificationMapper notificationMapper;
    private final NotifyProperties props;
    private final PiiProperties piiProperties;

    public NotificationService(NotificationMapper notificationMapper, NotifyProperties props,
                             PiiProperties piiProperties) {
        this.notificationMapper = notificationMapper;
        this.props = props;
        this.piiProperties = piiProperties;
    }

    /** 落库 / 外发前脱敏；开关与消息正文共用同一个（{@code agent.pii.enabled}）。 */
    private String maskForStore(String text) {
        return piiProperties.enabledOn() ? PiiMasker.mask(text) : text;
    }

    /**
     * 推一条通知：落库（失败只记日志），并在级别达到门槛时尽力外发。
     *
     * @param userId 目标用户；{@code null} = 全员广播
     * @return 落库后的通知（落库失败返回未持久化的对象，调用方无需判空）
     */
    public Notification push(Long userId, String type, String level, String title, String content,
                             String refType, String refId) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setLevel(level == null ? Notification.LEVEL_INFO : level);
        // 内容与标题同样过脱敏：这里的 content 是模型回复摘要 / 失败原因（AlertService.sendReport、
        // ScheduledTaskService.execute 都往里写真实数据），而 pushWebhook 还会把它<b>外发到站外</b>
        // （钉钉 / 企微）—— 那是本项目控制范围之外、且不可撤回的出口。
        n.setTitle(truncate(maskForStore(title), TITLE_MAX));
        n.setContent(truncate(maskForStore(content), CONTENT_MAX));
        n.setRefType(refType);
        n.setRefId(refId);
        n.setCreatedAt(LocalDateTime.now());
        try {
            notificationMapper.insert(n);
        } catch (Exception e) {
            log.error("通知落库失败（业务动作不受影响）：type={}，title={}", type, title, e);
        }
        pushWebhook(n);
        return n;
    }

    /** 本人可见的通知（含全员广播），时间倒序。 */
    public List<Notification> list(Long userId, boolean unreadOnly) {
        LambdaQueryWrapper<Notification> q = visible(userId).orderByDesc(Notification::getId).last("LIMIT " + LIST_LIMIT);
        if (unreadOnly) q.isNull(Notification::getReadAt);
        return notificationMapper.selectList(q);
    }

    /** 未读数（含全员广播）。 */
    public long unreadCount(Long userId) {
        Long c = notificationMapper.selectCount(visible(userId).isNull(Notification::getReadAt));
        return c == null ? 0L : c;
    }

    /**
     * 标记单条已读。
     * <p>
     * <b>只允许本人行</b>，广播行（{@code user_id IS NULL}）一律不动：{@code read_at} 是单列，
     * 表达不了「每人各自读过」—— 让任意登录用户点一下就把全体共享的广播置为已读，
     * 等于任何人都能永久抹掉别人的告警信号（门禁结论、阈值告警正是广播形态），且不可逆。
     * 代价是广播会一直显示未读直到每人都读过；这里选择「宁可一直提示，不可静默消失」。
     *
     * @return true=标记成功；false=通知不存在、非本人、广播、或本来就已读
     */
    public boolean markRead(Long id, Long userId) {
        if (id == null || userId == null) return false;
        return notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, id)
                .eq(Notification::getUserId, userId)
                .isNull(Notification::getReadAt)
                .set(Notification::getReadAt, LocalDateTime.now())) > 0;
    }

    /** 全部标记已读，返回本次标记的条数。同样只含本人行 —— 广播不在其中（理由见 {@link #markRead}）。 */
    public int markAllRead(Long userId) {
        if (userId == null) return 0;
        return notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .isNull(Notification::getReadAt)
                .set(Notification::getReadAt, LocalDateTime.now()));
    }

    /**
     * 「本人 + 广播」的<b>可见性</b>条件（列表 / 未读数用，与上面两条的写入口径刻意不同）。
     * <p>
     * 别把这三个口径合并成一个方法：读要看到广播、写不能碰广播，合并的结果就是任一人替全体标记已读。
     */
    private static LambdaQueryWrapper<Notification> visible(Long userId) {
        return new LambdaQueryWrapper<Notification>()
                .and(w -> w.eq(Notification::getUserId, userId).or().isNull(Notification::getUserId));
    }

    /**
     * 站外外发（尽力而为）：未配置地址、或级别低于门槛时直接返回，不产生任何网络请求。
     * <p>
     * 载荷用「机器人文本消息」的通用形态（钉钉 / 企业微信文本卡片同构：{@code {"msgtype":"text","text":{"content":"..."}}}）。
     */
    private void pushWebhook(Notification n) {
        if (!props.webhookOn()) return;
        if (rank(n.getLevel()) < rank(props.minLevel())) return;
        String body = buildWebhookBody(n);
        try {
            String resp = HttpRequest.post(props.webhookUrl())
                    .timeout(props.readTimeoutMs())
                    .body(body)
                    .execute()
                    .body();
            log.info("通知外发完成：level={}，响应长度={}", n.getLevel(), resp == null ? 0 : resp.length());
        } catch (Exception e) {
            log.warn("通知外发失败（仅影响站外送达，不影响库内通知与业务动作）：{}", e.getMessage());
        }
    }

    /** 机器人文本消息载荷（Hutool 拼 JSON，避免手写转义出错）。 */
    static String buildWebhookBody(Notification n) {
        JSONObject text = new JSONObject();
        text.set("content", "【" + n.getLevel() + "】" + n.getTitle()
                + (n.getContent() == null || n.getContent().isBlank() ? "" : "\n" + n.getContent()));
        JSONObject root = new JSONObject();
        root.set("msgtype", "text");
        root.set("text", text);
        return root.toString();
    }

    /** 级别排序用于比较（未知级别按 INFO 处理，宁可少发也不误发）。 */
    private static int rank(String level) {
        if (Notification.LEVEL_ERROR.equals(level)) return 3;
        if (Notification.LEVEL_WARN.equals(level)) return 2;
        return 1;
    }

    private static String truncate(String s, int max) {
        return TextClip.clip(s, max, null);
    }
}
