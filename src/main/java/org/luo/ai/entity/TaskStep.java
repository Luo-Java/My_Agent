package org.luo.ai.entity;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 规划任务步骤实体（对应 {@code task_step} 表）：任务的一步 = 一个智能体 + 指令 + 依赖前驱。
 * <p>
 * 状态机：{@code PENDING → RUNNING → DONE/SKIPPED/FAILED}；{@code FAILED} 续跑时重试一次，
 * 累计 {@code retryCount >= 2} 判确定性失败（不再重试，依赖步回落原始目标），见设计文档 §4.5。
 * <p>
 * <b>审批关卡（{@link #approvalRequired} / {@link #approved}）独立于状态机</b>：标记为需审批的步骤在
 * 执行前若尚未批准，整条流水线在此暂停（该步与剩余步骤都保持 {@code PENDING}，task 仍 {@code RUNNING}），
 * 用户批准后再走 {@code resumeTask} 继续 —— 因为「暂停」不改变任何一步的执行事实，本表因此不必新增状态值。
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

    /** 跳过原因（写入 {@code error} 列）：该步的智能体已被删除，无法执行。 */
    public static final String SKIP_REASON_AGENT_MISSING = "智能体已被删除，跳过该步";

    /** 跳过原因（写入 {@code error} 列）：用户手动跳过（某步反复失败、或就想跳过它继续跑）。 */
    public static final String SKIP_REASON_USER = "用户手动跳过该步";

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

    /**
     * 该步执行前是否需要用户审批（计划阶段可改）。
     * <p>
     * <b>刻意不并入 {@link #status} 状态机</b>：status 表达「这一步跑到哪了」，审批表达「允不允许跑」，
     * 两者正交 —— 把「待审批」做成一个 status 值，会让 {@code markStepRunning}、{@code isSettled}、
     * 续跑重试判定等所有既有分支都要额外考虑这个新态。
     */
    private Boolean approvalRequired;

    /** 审批是否已通过（仅 {@link #approvalRequired} 为 true 时有约束意义）；批准后走续跑通路继续执行。 */
    private Boolean approved;

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

    /**
     * 步骤定义：决定「这一步是什么」的三元组（智能体 + 指令 + 依赖），<b>不含任何运行态</b>。
     * <p>
     * 两个写入方共用同一形状：局部重规划产出的新步骤（{@code TaskService.replanTail}）与规划模板的步骤骨架
     * （{@code TaskTemplate.stepsFromJson}）。{@code dependsOn} 一律是<b>段内 0 基下标</b>，与 {@code step_index}
     * 同口径，因此落库时按原样使用、不需要重映射。
     */
    public record Def(String agentCode, String instruction, List<Integer> dependsOn) {
    }

    /**
     * 只保留「严格前序」的依赖（{@code 0 <= d < self}），去重并升序。
     * <p>
     * 执行层按「依赖均已满足」分层推进：依赖里出现自身或后序下标，该步就永远等不到前驱；限死严格前序
     * 同时天然杜绝依赖环，因此不需要额外的环检测。规划的常规产出本来就满足这个约束，本方法服务于
     * <b>持久化资产</b>（模板骨架、落库步骤）—— 它们可能被手改 SQL、或由更早的版本写入。
     * <p>
     * <b>净化而非拒绝</b>：调用方是「套用模板」这类不该整体失败的动作，丢弃坏依赖并记 WARN 即可；
     * 而交互式编辑（{@code TaskService#updatePendingStep}）面对的是具体用户，应当明确回报原因而不是悄悄改。
     */
    public static List<Integer> strictPriorDeps(List<Integer> raw, int self) {
        if (raw == null || raw.isEmpty()) return List.of();
        return raw.stream()
                .filter(x -> x != null && x >= 0 && x < self)
                .distinct().sorted().toList();
    }

    /**
     * 该步是否「需要审批且尚未批准」——执行侧的闸门判据。
     * <p>
     * null 一律按 false 处理（既兼容未执行 alter 的存量行，也让「没勾审批」这一默认路径保持零判断成本）。
     */
    public static boolean awaitingApproval(TaskStep step) {
        return Boolean.TRUE.equals(step.getApprovalRequired()) && !Boolean.TRUE.equals(step.getApproved());
    }

    /**
     * 依赖下标列表 → JSON 数组字符串（写库 {@code task_step.depends_on}）。
     * <p>
     * 这一列的格式约定<b>只此一处</b>：规划落库（PlannerRoundHandler）、续跑读回、就地编辑改写三处共用，
     * 避免各自手写 JSON 拼装后格式漂移（例如某处写成 {@code [1, 2]} 带空格、另一处写 {@code []} 与 {@code null} 混用）。
     */
    public static String depsToJson(List<Integer> deps) {
        if (deps == null || deps.isEmpty()) return "[]";
        JSONArray arr = new JSONArray(deps.size());
        for (int d : deps) arr.add(d);
        return arr.toString();
    }

    /** JSON 数组字符串 → 依赖下标列表（读回 {@code task_step.depends_on}）；空 / 解析失败返回空表（视为无依赖）。 */
    public static List<Integer> depsFromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JSONArray arr = JSONUtil.parseArray(json);
            List<Integer> out = new ArrayList<>(arr.size());
            for (Object o : arr) {
                if (o instanceof Number num) out.add(num.intValue());
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
