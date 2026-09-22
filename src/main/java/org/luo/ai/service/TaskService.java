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
import java.util.List;
import java.util.UUID;

/**
 * 规划任务业务服务：任务（task）与步骤（task_step）的建 / 查 / 改 / 收尾封装。
 * <p>
 * 支撑「跨轮任务状态持久化」：动态规划每轮产出计划后落库为一条任务 + 若干步骤，逐步增量提交状态；
 * 服务重启 / 中断后可按会话续跑剩余步骤（显式按钮触发，见 {@code TaskController}）。
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

    /** 取任务的步骤列表（按 step_index 升序）。 */
    public List<TaskStep> listSteps(String taskId) {
        return stepMapper.selectList(new QueryWrapper<TaskStep>()
                .eq("task_id", taskId)
                .orderByAsc("step_index"));
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

    /** 建任务步骤（status=PENDING，retry_count=0）；返回落库后的步骤（含自增 id）。 */
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

    /** 把该会话所有 RUNNING 任务结为 CANCELLED（开新规划任务前调用，保证单 RUNNING）。 */
    public void cancelRunning(String conversationId) {
        taskMapper.update(null, new LambdaUpdateWrapper<Task>()
                .eq(Task::getConversationId, conversationId)
                .eq(Task::getStatus, Task.STATUS_RUNNING)
                .set(Task::getStatus, Task.STATUS_CANCELLED)
                .set(Task::getUpdatedAt, LocalDateTime.now()));
    }
}
