package org.luo.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通知（对应 {@code notification} 表）：给「用户不在对话里时发生的事」留一个到达用户的出口。
 * <p>
 * <b>为什么需要它</b>：此前平台<b>只会在对话里说话</b> —— 定时任务跑完、用量告警触发、提示词门禁判劣化，
 * 这些都不是「用户正在问问题」的时刻，没有任何通道能把结果送到人面前。于是定时任务的价值归零
 * （跑完了没人看），告警也成了摆设。本表是最小可用的出口：落一行、前端轮询、按人隔离。
 * <p>
 * <b>为什么不直接推 SSE</b>：SSE 通道是「一次对话请求」的生命周期，而通知的产生时机与任何请求无关
 * （后台线程 / 定时器）。给这类事件硬塞一条长连接既不经济也不可靠（用户关页面就丢）。轮询一张表，
 * 简单、可重放、可回看。
 * <p>
 * {@code userId} 为 NULL = <b>全员广播</b>（系统级告警、门禁结论），前端按「本人 + 广播」取；
 * 非 NULL = 定向通知（如某人的定时任务跑完）。
 */
@Data
@NoArgsConstructor
@TableName("notification")
public class Notification {

    /** 类型：系统消息。 */
    public static final String TYPE_SYSTEM = "SYSTEM";
    /** 类型：定时任务执行完成。 */
    public static final String TYPE_SCHEDULED_TASK = "SCHEDULED_TASK";
    /** 类型：阈值告警。 */
    public static final String TYPE_ALERT = "ALERT";
    /** 类型：提示词门禁结论。 */
    public static final String TYPE_PROMPT_GATE = "PROMPT_GATE";

    /** 级别：常规信息。 */
    public static final String LEVEL_INFO = "INFO";
    /** 级别：需要留意（如门禁 DEGRADED）。 */
    public static final String LEVEL_WARN = "WARN";
    /** 级别：失败（如任务执行报错）。 */
    public static final String LEVEL_ERROR = "ERROR";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 目标用户 ID，关联 {@code sys_user.id}；NULL = 全员广播（系统级）。 */
    private Long userId;

    /** 类型：SYSTEM / SCHEDULED_TASK / ALERT / PROMPT_GATE。 */
    private String type;

    /** 级别：INFO / WARN / ERROR。 */
    private String level;

    /** 标题（列表一行一句）。 */
    private String title;

    /** 正文（可空；通常是被通知事件的摘要，已截断）。 */
    private String content;

    /** 关联对象类型：CONVERSATION / SCHEDULED_TASK / PROMPT_SNAPSHOT / ALERT_RULE；可空。 */
    private String refType;

    /** 关联对象 ID（字符串，兼容自增主键与业务 UUID）；可空。 */
    private String refId;

    /** 已读时间；NULL = 未读。 */
    private LocalDateTime readAt;

    private LocalDateTime createdAt;
}
