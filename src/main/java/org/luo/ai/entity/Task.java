package org.luo.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 规划任务实体（对应 {@code task} 表）：一轮规划 = 一条 task + N 条 task_step，落库支撑断点续跑。
 * <p>
 * 与无状态的动态规划不同，任务把「用户目标 → 步骤计划 → 逐步产出」持久化，服务重启后仍可续跑剩余步骤。
 * 状态机见 {@code docs/跨轮任务状态持久化-设计方案.md}：{@code RUNNING → DONE/FAILED/CANCELLED}。
 */
@Data
@NoArgsConstructor
@TableName("task")
public class Task {

    /** 任务状态：进行中（可续跑）。 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 任务状态：全部步骤完成。 */
    public static final String STATUS_DONE = "DONE";
    /** 任务状态：失败（无可用产出）。 */
    public static final String STATUS_FAILED = "FAILED";
    /** 任务状态：已取消（开新规划任务时结旧）。 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 任务 ID，业务层生成 UUID 后写入（INPUT 表示自行赋值）。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 所属会话 ID，关联 {@code conversation.id}。 */
    private String conversationId;

    /** 用户原始目标（触发规划的那句原话）。 */
    private String userGoal;

    /** 任务状态：RUNNING / DONE / FAILED / CANCELLED。 */
    private String status;

    /** 步骤总数（快照，避免反复数）。 */
    private Integer totalSteps;

    /** 已完成步骤数（冗余，供列表快速展示进度）。 */
    private Integer doneSteps;

    /**
     * 用户「中途喊停」信号位：1=已请求暂停。
     * <p>
     * <b>这不是状态</b>：任务仍是 {@link #STATUS_RUNNING}，它是一个「让执行循环在下一个层边界自行停下」的
     * 一次性信号。执行体跑在 SSE 的异步线程上，用库里的列而不是内存标志，是为了与「单会话单 RUNNING」
     * 这条既有不变量放在同一处（task 表），不引入第二种状态源。
     * <p>
     * 清零责任在续跑入口（{@code PlannerRoundHandler#resumeTask} 开头），否则用户点了「继续执行」会立刻
     * 又被自己的暂停位拦住。
     */
    private Boolean pauseRequested;

    /** 最终汇总结果（汇总步产出 / 最后一个成功步骤产出）。 */
    private String result;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
