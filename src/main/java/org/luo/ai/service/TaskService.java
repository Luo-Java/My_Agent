package org.luo.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.entity.Task;
import org.luo.ai.entity.TaskStep;
import org.luo.ai.mapper.TaskMapper;
import org.luo.ai.mapper.TaskStepMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 规划任务业务服务：任务（task）与步骤（task_step）的建 / 查 / 改 / 收尾封装。
 * <p>
 * 支撑「跨轮任务状态持久化」：动态规划每轮产出计划后落库为一条任务 + 若干步骤，逐步增量提交状态；
 * 服务重启 / 中断后可按会话续跑剩余步骤（显式按钮触发，端点都在 {@code ChatController} 的
 * {@code /api/chat/task/**} 下 —— 项目里没有 TaskController）。
 * <p>
 * <b>单会话单 RUNNING 不变量</b>：任一时刻至多一个进行中的规划任务；开新规划任务前先把旧 RUNNING 结
 * {@code CANCELLED}（见 {@link #cancelRunning}），保证续跑入口按 conversationId 定位无歧义。
 */
@Slf4j
@Service
public class TaskService {

    private final TaskMapper taskMapper;
    private final TaskStepMapper stepMapper;

    public TaskService(TaskMapper taskMapper, TaskStepMapper stepMapper) {
        this.taskMapper = taskMapper;
        this.stepMapper = stepMapper;
    }

    /**
     * 取该会话唯一的 RUNNING 任务（用于续跑入口定位）；不存在返回 null。
     */
    public Task findRunning(String conversationId) {
        return taskMapper.selectOne(new QueryWrapper<Task>()
                .eq("conversation_id", conversationId)
                .eq("status", Task.STATUS_RUNNING)
                .orderByDesc("created_at")
                .last("LIMIT 1"));
    }

    /**
     * 按任务 ID 取任务（不限状态）；不存在返回 null。
     * <p>
     * 与 {@link #findRunning} 的分工：后者服务「续跑入口」，只认 RUNNING；本方法服务「存为模板」这类
     * 对终态任务的操作 —— 任务跑完（DONE/FAILED）恰恰是最值得沉淀成模板的时刻。
     */
    public Task findById(String taskId) {
        return taskId == null ? null : taskMapper.selectById(taskId);
    }

    /** 取任务的步骤列表（按 step_index 升序）。 */
    public List<TaskStep> listSteps(String taskId) {
        return stepMapper.selectList(new QueryWrapper<TaskStep>()
                .eq("task_id", taskId)
                .orderByAsc("step_index"));
    }

    /** 取指定任务下 step_index 对应的步骤；不存在返回 null。 */
    public TaskStep findStep(String taskId, int stepIndex) {
        return stepMapper.selectOne(new QueryWrapper<TaskStep>()
                .eq("task_id", taskId)
                .eq("step_index", stepIndex)
                .last("LIMIT 1"));
    }

    /**
     * 就地编辑「待确认计划」中<b>尚未执行</b>的步骤：改智能体 / 指令 / 依赖前驱 / 审批标记。
     * <p>
     * 这个入口支撑「先看计划」的完整闭环：计划落库（task=RUNNING、步骤全 PENDING）→ 用户逐条审阅调整 →
     * 点执行走续跑通路。执行侧（{@code PlannerRoundHandler#resumeTask}）是从库里读步骤重建执行的，所以
     * <b>改库即生效，不需要第二套执行逻辑</b>。
     * <p>
     * 两条硬约束，都是有原因的：
     * <ul>
     *   <li><b>只收 PENDING</b>：RUNNING / DONE / FAILED / SKIPPED 的步骤承载着产出与重试计数，中途改写会让
     *       「状态是已完成、产出却是旧指令的结果」这种事实漂移永久留在库里；</li>
     *   <li><b>依赖只能指向更早的下标</b>：执行层按「依赖均已满足」分层推进，若允许依赖自身或后序，该步永远
     *       等不到前驱，只能靠断环逻辑兜底跳过。限制为「严格前序」同时天然杜绝了依赖环。</li>
     * </ul>
     * 依赖前驱的存在性不必单独校验：{@code step_index} 由规划器维持为 {@code 0..n-1} 连续（见
     * {@code PlannerRoundHandler#persistPlan}），故任何 {@code < stepIndex} 的下标必有对应步骤。
     *
     * @param dependsOn 新的依赖前序下标（null / 空 = 无依赖，可与同层步骤并行）
     * @param approvalRequired 新的「执行前需审批」标记；<b>null = 不改该列</b>（只改智能体/指令/依赖的调用方
     *                         不必关心审批），非 null 时覆盖写
     * @return null 表示成功；非 null 为拒绝原因（调用方转 400 / 404）
     */
    public String updatePendingStep(String taskId, int stepIndex, String agentCode, String instruction,
                                    List<Integer> dependsOn, Boolean approvalRequired) {
        TaskStep step = findStep(taskId, stepIndex);
        if (step == null) {
            return "第 " + (stepIndex + 1) + " 步不存在";
        }
        if (!TaskStep.STATUS_PENDING.equals(step.getStatus())) {
            return "第 " + (stepIndex + 1) + " 步已开始执行，不能再修改";
        }
        if (agentCode == null || agentCode.isBlank()) {
            return "智能体不能为空";
        }
        // 去重 + 升序：依赖列表的语义是集合，顺序不影响执行（执行时按列表顺序拼接前驱产出，排序只为落库稳定）
        List<Integer> deps = dependsOn == null ? List.of()
                : dependsOn.stream().filter(Objects::nonNull).distinct().sorted().toList();
        for (int d : deps) {
            if (d < 0 || d >= stepIndex) {
                return "第 " + (d + 1) + " 步不能作为第 " + (stepIndex + 1) + " 步的依赖（只能依赖更早的步骤）";
            }
        }
        LambdaUpdateWrapper<TaskStep> update = new LambdaUpdateWrapper<TaskStep>()
                .eq(TaskStep::getId, step.getId())
                // 状态再卡一次：上面的校验与下面的写入之间，执行线程可能已把它推进到 RUNNING
                .eq(TaskStep::getStatus, TaskStep.STATUS_PENDING)
                .set(TaskStep::getAgentCode, agentCode.trim())
                .set(TaskStep::getInstruction, instruction == null ? "" : instruction)
                .set(TaskStep::getDependsOn, TaskStep.depsToJson(deps));
        if (approvalRequired != null) {
            update.set(TaskStep::getApprovalRequired, approvalRequired);
            // 取消审批要求时把批准标记一并归零：否则「先勾审批 → 批准 → 再取消审批 → 又勾上」会沿用旧批准，
            // 用户以为重新设了关卡、实际它已被静默放行。
            if (!approvalRequired) {
                update.set(TaskStep::getApproved, false);
            }
        }
        int updated = stepMapper.update(null, update);
        return updated > 0 ? null : "第 " + (stepIndex + 1) + " 步已开始执行，不能再修改";
    }

    /**
     * 批准某一步的「执行前审批」（{@code approved = 1}），使它能被执行侧放行。
     * <p>
     * <b>只写标记、不触发执行</b>：批准之后由前端接着调 {@code POST /api/chat/task/resume}（断点续跑同一通路），
     * 于是「批准」与「继续跑」各自职责单一，执行逻辑仍然只有一处。
     * <p>
     * 只接受 {@code PENDING}：已完成/已失败/被跳过的步骤不存在「批准」这回事，批准它们会让库里留下
     * 「已批准但早已跑完」的误导性状态。
     *
     * @return null 表示成功；非 null 为拒绝原因
     */
    public String approveStep(String taskId, int stepIndex) {
        TaskStep step = findStep(taskId, stepIndex);
        if (step == null) {
            return "第 " + (stepIndex + 1) + " 步不存在";
        }
        if (!Boolean.TRUE.equals(step.getApprovalRequired())) {
            return "第 " + (stepIndex + 1) + " 步不需要审批";
        }
        if (!TaskStep.STATUS_PENDING.equals(step.getStatus())) {
            return "第 " + (stepIndex + 1) + " 步不在待审批状态（当前 " + step.getStatus() + "）";
        }
        stepMapper.update(null, new LambdaUpdateWrapper<TaskStep>()
                .eq(TaskStep::getId, step.getId())
                .eq(TaskStep::getStatus, TaskStep.STATUS_PENDING)   // 与写入之间可能已被执行线程推进
                .set(TaskStep::getApproved, true));
        return null;
    }

    /** 建任务（status=RUNNING），返回生成的 task。 */
    public Task create(String conversationId, String userGoal, int totalSteps) {
        Task t = new Task();
        t.setId(UUID.randomUUID().toString().replace("-", ""));
        t.setConversationId(conversationId);
        t.setUserGoal(userGoal);
        t.setStatus(Task.STATUS_RUNNING);
        t.setTotalSteps(totalSteps);
        t.setDoneSteps(0);
        LocalDateTime now = LocalDateTime.now();
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        taskMapper.insert(t);
        return t;
    }

    /** 建任务步骤（status=PENDING，retry_count=0，不带审批关卡）；返回落库后的步骤（含自增 id）。 */
    public TaskStep createStep(String taskId, int stepIndex, String agentCode, String instruction,
                               String dependsOnJson) {
        TaskStep s = new TaskStep();
        s.setTaskId(taskId);
        s.setStepIndex(stepIndex);
        s.setAgentCode(agentCode);
        s.setInstruction(instruction);
        s.setDependsOn(dependsOnJson);
        s.setStatus(TaskStep.STATUS_PENDING);
        s.setRetryCount(0);
        // 显式写默认值而非依赖列默认：MyBatis-Plus 默认只插非 null 字段，显式赋值让「新步骤不带审批关卡」
        // 这条语义读代码即可确认，不必回查 DDL（规划、重规划、套用模板三条写入方共用本方法）。
        s.setApprovalRequired(false);
        s.setApproved(false);
        stepMapper.insert(s);
        return s;
    }

    /** 步骤开始执行：PENDING/FAILED → RUNNING，started_at 落库。带前置条件防并发重复执行。 */
    public void markStepRunning(Long stepId) {
        stepMapper.update(null, new LambdaUpdateWrapper<TaskStep>()
                .eq(TaskStep::getId, stepId)
                .in(TaskStep::getStatus, TaskStep.STATUS_PENDING, TaskStep.STATUS_FAILED)
                .set(TaskStep::getStatus, TaskStep.STATUS_RUNNING)
                .set(TaskStep::getStartedAt, LocalDateTime.now()));
    }

    /** 步骤成功完成：RUNNING → DONE，产出与引用落库。 */
    public void markStepDone(Long stepId, String output, String citationsJson) {
        stepMapper.update(null, new LambdaUpdateWrapper<TaskStep>()
                .eq(TaskStep::getId, stepId)
                .set(TaskStep::getStatus, TaskStep.STATUS_DONE)
                .set(TaskStep::getOutput, output)
                .set(TaskStep::getCitationsJson, citationsJson)
                .set(TaskStep::getFinishedAt, LocalDateTime.now()));
    }

    /** 步骤跳过（agent 不存在等确定性失败）：RUNNING → SKIPPED。 */
    public void markStepSkipped(Long stepId) {
        stepMapper.update(null, new LambdaUpdateWrapper<TaskStep>()
                .eq(TaskStep::getId, stepId)
                .set(TaskStep::getStatus, TaskStep.STATUS_SKIPPED)
                .set(TaskStep::getFinishedAt, LocalDateTime.now()));
    }

    /** 步骤失败：RUNNING → FAILED，记录原因并 retry_count + 1。 */
    public void markStepFailed(Long stepId, String error) {
        stepMapper.update(null, new LambdaUpdateWrapper<TaskStep>()
                .eq(TaskStep::getId, stepId)
                .set(TaskStep::getStatus, TaskStep.STATUS_FAILED)
                .set(TaskStep::getError, error)
                .set(TaskStep::getFinishedAt, LocalDateTime.now())
                .setSql("retry_count = retry_count + 1"));
    }

    /** 推进任务完成步数（done_steps = 该任务下 DONE/SKIPPED 及不可重试 FAILED 的步骤数）。 */
    public void refreshDoneCount(String taskId) {
        Long done = stepMapper.selectCount(new QueryWrapper<TaskStep>()
                .eq("task_id", taskId)
                .in("status", TaskStep.STATUS_DONE, TaskStep.STATUS_SKIPPED));
        taskMapper.update(null, new LambdaUpdateWrapper<Task>()
                .eq(Task::getId, taskId)
                .set(Task::getDoneSteps, done == null ? 0 : done.intValue())
                .set(Task::getUpdatedAt, LocalDateTime.now()));
    }

    /** 任务收尾：置终态与最终结果。 */
    public void finish(String taskId, String status, String result) {
        taskMapper.update(null, new LambdaUpdateWrapper<Task>()
                .eq(Task::getId, taskId)
                .set(Task::getStatus, status)
                .set(Task::getResult, result)
                .set(Task::getUpdatedAt, LocalDateTime.now()));
    }

    /**
     * 用局部重规划产出的新步骤替换任务里「尚未成功的那一段」。
     * <p>
     * 保留两类步骤：<b>已终结的</b>（DONE / SKIPPED —— 它们的产出与状态是既有资产，不能因为重排而丢）与
     * <b>下标在起点之前的</b>。其余（PENDING / RUNNING / 未用尽重试的 FAILED）删掉，新步骤接在保留段之后。
     * <p>
     * <b>必须重排 step_index 的原因</b>：续跑重建执行时，依赖映射用的是<b>列表位置</b>而非 step_index
     * （见 {@code PlannerRoundHandler#resumeTask} 的 remap）。若保留段留下下标空洞（例如 0,1,3），
     * 依赖里写的 3 会因为超出 remap 长度被静默过滤成「无依赖」，执行语义就悄悄变了。故这里把保留段
     * 压紧到 {@code 0..k-1}，并同步重映射它们各自的 depends_on。
     * <p>
     * 新步骤的 dependsOn 一律按<b>段内相对下标</b>解释、不引用保留段：重规划提示词已要求模型把上游结论
     * 内联进指令（见 {@code prompts.yaml} 的 {@code planner-replan-system}），跨段依赖没有必要。
     * <p>
     * 注意 task_step 没有 (task_id, step_index) 唯一约束，所以「先改下标、再删旧行」的中间态不会冲突。
     *
     * @param fromIndex 重规划起点（0 基，含）：该下标之后尚未成功的步骤都要被替换
     * @param newSteps  重规划产出的新步骤（顺序即执行顺序）；用 {@link TaskStep.Def} —— 与模板骨架同一种
     *                  结构（智能体 + 指令 + 依赖，无运行态），落库口径因此只有一处
     * @return 替换后的完整步骤列表（按新 step_index 升序），供调用方构造计划 JSON
     */
    public List<TaskStep> replanTail(String taskId, int fromIndex, List<TaskStep.Def> newSteps) {
        List<TaskStep> all = listSteps(taskId);
        List<TaskStep> kept = new ArrayList<>();
        List<TaskStep> dropped = new ArrayList<>();
        for (TaskStep s : all) {
            if (isSettled(s) || s.getStepIndex() < fromIndex) {
                kept.add(s);
            } else {
                dropped.add(s);
            }
        }
        // 原下标 → 新下标（保留段按原相对顺序压紧到 0..k-1）
        Map<Integer, Integer> remap = new HashMap<>();
        for (int i = 0; i < kept.size(); i++) {
            remap.put(kept.get(i).getStepIndex(), i);
        }
        for (int i = 0; i < kept.size(); i++) {
            TaskStep s = kept.get(i);
            // 保留段自身的依赖也要跟着压紧：被删掉的前驱（若有）映射不到就丢弃
            List<Integer> deps = TaskStep.depsFromJson(s.getDependsOn()).stream()
                    .map(remap::get)
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList();
            stepMapper.update(null, new LambdaUpdateWrapper<TaskStep>()
                    .eq(TaskStep::getId, s.getId())
                    .set(TaskStep::getStepIndex, i)
                    .set(TaskStep::getDependsOn, TaskStep.depsToJson(deps)));
        }
        for (TaskStep s : dropped) {
            stepMapper.deleteById(s.getId());
        }
        int base = kept.size();
        for (int i = 0; i < newSteps.size(); i++) {
            TaskStep.Def ns = newSteps.get(i);
            createStep(taskId, base + i, ns.agentCode(), ns.instruction(), TaskStep.depsToJson(ns.dependsOn()));
        }
        taskMapper.update(null, new LambdaUpdateWrapper<Task>()
                .eq(Task::getId, taskId)
                .set(Task::getTotalSteps, base + newSteps.size())
                .set(Task::getUpdatedAt, LocalDateTime.now()));
        return listSteps(taskId);
    }

    /** 步骤是否已终结（不会再被执行）：DONE / SKIPPED。 */
    public static boolean isSettled(TaskStep step) {
        return TaskStep.STATUS_DONE.equals(step.getStatus()) || TaskStep.STATUS_SKIPPED.equals(step.getStatus());
    }

    /** 把该会话所有 RUNNING 任务结为 CANCELLED（开新规划任务前调用，保证单 RUNNING）。 */
    public void cancelRunning(String conversationId) {
        taskMapper.update(null, new LambdaUpdateWrapper<Task>()
                .eq(Task::getConversationId, conversationId)
                .eq(Task::getStatus, Task.STATUS_RUNNING)
                .set(Task::getStatus, Task.STATUS_CANCELLED)
                .set(Task::getUpdatedAt, LocalDateTime.now()));
    }
}
