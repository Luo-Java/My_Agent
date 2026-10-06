package org.luo.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.dto.TaskTemplateSummary;
import org.luo.ai.entity.TaskStep;
import org.luo.ai.entity.TaskTemplate;
import org.luo.ai.mapper.TaskTemplateMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 规划模板业务服务：把跑顺的规划**步骤骨架**沉淀为可复用资产（存的读的都在这里，套用见
 * {@code PlannerRoundHandler#applyTemplate}）。
 * <p>
 * 定位：{@code task}/{@code task_step} 只服务断点续跑 —— 一次规划跑完就沉在库里，同类目标下次仍要重新
 * 花一次模型往返。模板把「这类事该怎么排」留下来，套用时按骨架直接落库，省掉那次规划调用。
 * <p>
 * <b>归属按 user_id 隔离</b>（与 {@code conversation} 同口径）：查询一律带 {@code user_id} 条件，
 * 取不到就当作不存在 —— 调用方转 404，与「这个 ID 不存在」不可区分，防止拿 ID 探测他人模板。
 */
@Slf4j
@Service
public class TaskTemplateService {

    /** 模板名长度上限：超出<b>截断</b>而非报错 —— 用户多半是随手起名，为长度退回一次保存不值当。 */
    private static final int MAX_NAME_CHARS = 100;

    /** 备注长度上限（同上，截断）。 */
    private static final int MAX_DESC_CHARS = 500;

    private final TaskTemplateMapper templateMapper;

    public TaskTemplateService(TaskTemplateMapper templateMapper) {
        this.templateMapper = templateMapper;
    }

    /**
     * 当前用户的模板列表（创建时间倒序）。
     * <p>
     * 排序带 {@code id} 兜底：{@code created_at} 是秒级精度，连续保存两个模板时间可能完全相同，
     * 只按时间排会得到不稳定的顺序（与 {@code chat_message} 的排序口径一致）。
     */
    public List<TaskTemplateSummary> list(Long userId) {
        return templateMapper.selectList(new QueryWrapper<TaskTemplate>()
                        .eq("user_id", userId)
                        .orderByDesc("created_at")
                        .orderByDesc("id"))
                .stream()
                .map(t -> new TaskTemplateSummary(t.getId(), t.getName(), t.getDescription(),
                        TaskTemplate.stepsFromJson(t.getStepsJson()).size(),
                        t.getUseCount() == null ? 0 : t.getUseCount(),
                        t.getCreatedAt()))
                .toList();
    }

    /**
     * 按归属取单个模板；不存在<b>或不属于该用户</b>一律返回 null（调用方统一转 404）。
     *
     * @param id     模板 ID（可为 null，直接返回 null）
     * @param userId 当前登录用户 ID
     */
    public TaskTemplate findOwned(Long id, Long userId) {
        if (id == null || userId == null) return null;
        return templateMapper.selectOne(new QueryWrapper<TaskTemplate>()
                .eq("id", id)
                .eq("user_id", userId)
                .last("LIMIT 1"));
    }

    /**
     * 从任务的<b>当前</b>步骤骨架存出模板（读的是库里的步骤，不是调用方传进来的快照 —— 重规划会改库，
     * 以库为准才不会存到一份过期的计划）。
     *
     * @param description 备注，可空
     * @return 存好的模板（含自增 id）；名称为空或任务无步骤时返回 null，由调用方转 400
     */
    public TaskTemplate saveFromTask(String taskId, Long userId, String name, String description,
                                     List<TaskStep> steps) {
        if (name == null || name.isBlank()) return null;
        if (steps == null || steps.isEmpty()) return null;
        List<TaskStep.Def> defs = steps.stream()
                .map(s -> new TaskStep.Def(s.getAgentCode(), s.getInstruction(),
                        TaskStep.depsFromJson(s.getDependsOn())))
                .toList();
        TaskTemplate t = new TaskTemplate();
        t.setUserId(userId);
        t.setName(truncate(name, MAX_NAME_CHARS));
        t.setDescription(truncate(description, MAX_DESC_CHARS));
        t.setStepsJson(TaskTemplate.stepsToJson(defs));
        t.setSourceTaskId(taskId);
        t.setUseCount(0);
        LocalDateTime now = LocalDateTime.now();
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        templateMapper.insert(t);
        return t;
    }

    /** 套用计数 +1（模板被套用一次即一次）。用 {@code setSql} 原子自增，避免读改写丢更新。 */
    public void markUsed(Long id) {
        templateMapper.update(null, new LambdaUpdateWrapper<TaskTemplate>()
                .eq(TaskTemplate::getId, id)
                .setSql("use_count = use_count + 1")
                .set(TaskTemplate::getUpdatedAt, LocalDateTime.now()));
    }

    /**
     * 删除本人模板。
     *
     * @return 实际删除行数；0 表示不存在或非本人（调用方转 404，与查询同一口径）
     */
    public int delete(Long id, Long userId) {
        if (id == null || userId == null) return 0;
        return templateMapper.delete(new QueryWrapper<TaskTemplate>()
                .eq("id", id)
                .eq("user_id", userId));
    }

    /** 去空白后按上限截断（null 原样返回，落库为 NULL）。 */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
