package org.luo.ai.entity;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
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
 * 规划模板实体（对应 {@code task_template} 表）：把一次跑顺的规划**步骤骨架**存成可复用资产。
 * <p>
 * 与 {@link Task} 的关系：task/task_step 记录「这一次怎么跑的」（含状态与产出），模板只记「这类事该怎么排」——
 * 是一份与任何一次具体执行无关的快照，因此同一步骤在模板里没有运行态。套用走
 * {@code PlannerRoundHandler#applyTemplate}：按骨架落库成新的 task + task_step，之后点「执行计划」走的是
 * 现成的断点续跑通路，<b>模板不引入第二套执行逻辑</b>。
 * <p>
 * 归属按 {@code user_id} 隔离（与 {@code conversation} 同口径）：越权访问一律 404，与「不存在」不可区分。
 */
@Data
@NoArgsConstructor
@TableName("task_template")
public class TaskTemplate {

    /** 自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 创建者用户 ID，关联 {@code sys_user.id}。 */
    private Long userId;

    /** 模板名称（用户填写）。 */
    private String name;

    /** 备注：适用场景、套用时该填什么目标（可空）。 */
    private String description;

    /**
     * 步骤骨架 JSON 数组：{@code [{"agentCode":"A001","instruction":"…","dependsOn":[0]}]}。
     * <p>
     * {@code dependsOn} 是<b>段内 0 基下标</b>，与 {@code task_step.step_index} 同口径 —— 套用时按原样落库即可，
     * 不需要任何重映射（只有「步骤被跳过」时才需要，而跳过发生在执行侧，见 {@code resumeTask}）。
     * <p>
     * 刻意<b>不含智能体展示名</b>：名称会随重命名变化，存进去只会随时间漂移；展示时按 {@code agentCode} 现查。
     */
    private String stepsJson;

    /** 来源任务 ID（从哪次规划存下来的，仅供追溯；源任务被删不影响模板）。 */
    private String sourceTaskId;

    /** 被套用次数（统计用）。 */
    private Integer useCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * 步骤骨架列表 → JSON 数组字符串（写库 {@code task_template.steps_json}）。
     * <p>
     * 这一列的格式约定<b>只此一处</b>：存模板（从任务读回步骤）与套用模板（读回骨架落库）两处共用，
     * 避免各自手写 JSON 拼装后格式漂移。用 Hutool JSON 而非 Jackson，与项目其余 LLM/JSON 处理口径一致。
     */
    public static String stepsToJson(List<TaskStep.Def> steps) {
        if (steps == null || steps.isEmpty()) return "[]";
        JSONArray arr = new JSONArray(steps.size());
        for (TaskStep.Def s : steps) {
            JSONObject o = new JSONObject();
            o.set("agentCode", s.agentCode());
            o.set("instruction", s.instruction() == null ? "" : s.instruction());
            JSONArray deps = new JSONArray();
            if (s.dependsOn() != null) {
                for (int d : s.dependsOn()) deps.add(d);
            }
            o.set("dependsOn", deps);
            arr.add(o);
        }
        return arr.toString();
    }

    /**
     * {@code steps_json} → 步骤骨架列表（套用时读回）。
     * <p>
     * 容错口径：<b>缺 {@code agentCode} 的项直接跳过</b>（没有智能体的步骤无法执行，落库只会变成一步永远
     * 跑不通的步骤）；{@code instruction} 缺失按空串、{@code dependsOn} 缺失按空表。整串解析失败返回空表。
     * 这样「模板里混进了坏数据」的后果是少一步，而不是整套套用失败或落进一个坏步骤。
     */
    public static List<TaskStep.Def> stepsFromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JSONArray arr = JSONUtil.parseArray(json);
            List<TaskStep.Def> out = new ArrayList<>(arr.size());
            for (Object item : arr) {
                if (!(item instanceof JSONObject o)) continue;
                String code = o.getStr("agentCode");
                if (code == null || code.isBlank()) continue;
                List<Integer> deps = new ArrayList<>();
                JSONArray rawDeps = o.getJSONArray("dependsOn");
                if (rawDeps != null) {
                    for (Object d : rawDeps) {
                        if (d instanceof Number n) deps.add(n.intValue());
                    }
                }
                out.add(new TaskStep.Def(code, o.getStr("instruction", ""), deps));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
