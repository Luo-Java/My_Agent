package org.luo.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 规划任务步骤实体（对应 {@code task_step} 表）：任务的一步 = 一个智能体 + 指令 + 依赖前驱。
 * <p>
 * 状态机：{@code PENDING → RUNNING → DONE/SKIPPED/FAILED}；{@code FAILED} 续跑时重试一次，
 * 累计 {@code retryCount >= 2} 判确定性失败（不再重试，依赖步回落原始目标），见设计文档 §4.5。
 */
@Data
@NoArgsConstructor
@TableName("task_step")
public class TaskStep {

    /** 步骤状态：待执行。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 步骤状态：执行中（过渡态）。 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 步骤状态：成功产出。 */
    public static final String STATUS_DONE = "DONE";
    /** 步骤状态：被跳过（agent 不存在等确定性失败，不重试）。 */
    public static final String STATUS_SKIPPED = "SKIPPED";
    /** 步骤状态：异常失败（续跑时重试一次）。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 失败步骤续跑重试次数上限（达到即判确定性失败、放弃重试）。 */
    public static final int MAX_RETRY = 2;

    /** 自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属任务 ID，关联 {@code task.id}。 */
    private String taskId;

    /** 步骤下标（0 基，对应规划里 {@code steps} 数组位置）。 */
    private Integer stepIndex;

    /** 本步执行的智能体编码。 */
    private String agentCode;

    /** 给该智能体的指令。 */
    private String instruction;

    /** 依赖的前序步骤下标（JSON 数组，如 {@code [0,1]}；空表=无依赖）。 */
    private String dependsOn;

    /** 步骤状态：PENDING / RUNNING / DONE / SKIPPED / FAILED。 */
    private String status;

    /** 重试次数（0=未重试；续跑时对 FAILED 步骤重试一次，累计 ≥ 2 即放弃）。 */
    private Integer retryCount;

    /** 本步产出文本（成功时写入；失败/空为 NULL）。 */
    private String output;

    /** 失败原因（FAILED 时）。 */
    private String error;

    /** 本步 RAG 引用（与 {@code chat_message.citations_json} 同构）。 */
    private String citationsJson;

    /** 开始执行时间。 */
    private LocalDateTime startedAt;

    /** 完成时间。 */
    private LocalDateTime finishedAt;
}
