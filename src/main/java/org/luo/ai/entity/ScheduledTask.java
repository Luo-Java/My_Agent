package org.luo.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 定时任务（对应 {@code scheduled_task} 表）：把「一句话 + 一个周期」变成自动执行。
 * <p>
 * <b>为什么是独立一张表，而不是复用 {@code task}</b>：{@code task} 是<b>一轮规划的运行实例</b>
 * （状态机 RUNNING→DONE/FAILED，单会话单 RUNNING），生命周期以「一次执行」为单位；定时任务是
 * <b>长期存在的定义</b>（周期、开关、下次触发时间），每次到点才产生一次执行。两者是「模板 vs 实例」
 * 的关系，塞进同一张表会让「单会话单 RUNNING」这条既有不变量失去意义。
 * <p>
 * 执行体刻意<b>不新造一套</b>：到点后就是「以本用户的身份，在一个会话里把 {@code prompt} 问一遍」——
 * 直接走既有的 {@code ChatService.chat}，于是路由 / 规划 / RAG / 记忆 / 工具全都自动继承，
 * 定时任务不需要知道这些能力的存在。
 */
@Data
@NoArgsConstructor
@TableName("scheduled_task")
public class ScheduledTask {

    /** 上次执行状态：成功。 */
    public static final String STATUS_OK = "OK";
    /** 上次执行状态：失败。 */
    public static final String STATUS_ERROR = "ERROR";
    /** 上次执行状态：正在执行（供「跳过重叠触发」判断，见 ScheduledTaskService）。 */
    public static final String STATUS_RUNNING = "RUNNING";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归属用户 ID，关联 {@code sys_user.id}；按用户隔离，越权一律 404。 */
    private Long userId;

    /** 任务名称（同时用作首次执行时创建会话的标题）。 */
    private String name;

    /** 触发周期（Spring {@code CronExpression} 六段式：秒 分 时 日 月 周）。 */
    private String cron;

    /** 绑定智能体 ID（可空：为空则走智能路由）。 */
    private Long agentId;

    /** 到点时发给对话的提示词（就是一句用户话）。 */
    private String prompt;

    /** 承载本任务的会话 ID：首次执行时创建并回填，之后一直复用（结果都留在同一个会话里）。 */
    private String conversationId;

    /** 是否启用。 */
    private Boolean enabled;

    /** 执行完成后是否发通知（默认 true —— 不发通知的定时任务等于跑给人看不见）。 */
    private Boolean notifyOn;

    /** 上次执行时间。 */
    private LocalDateTime lastRunAt;

    /** 上次执行状态：OK / ERROR / RUNNING。 */
    private String lastStatus;

    /** 上次执行结果摘要（已截断，便于列表一眼看到产出）。 */
    private String lastResult;

    /** 下次触发时间（由 cron 预先算出并落库，扫描据此取「到点的」）。 */
    private LocalDateTime nextRunAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
