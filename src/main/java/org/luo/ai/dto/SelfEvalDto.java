package org.luo.ai.dto;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.luo.ai.entity.AgentTrace;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮回答的自评结果（追踪详情 / 低分轮次视图）。
 * <p>
 * <b>{@code score} 与其余明细来源不同</b>：{@code score} 取自 {@code agent_trace.self_eval_score} 这一列
 * （独立的列，面板要按它过滤与聚合，JSON 里解析不出索引），其余字段取自 {@code self_eval_json}。因此当
 * JSON 不可读时仍会有一个可信的分数、而明细为空 —— 那时 {@code answered} / {@code grounded} 是
 * <b>{@code null} 而不是 {@code false}</b>：「明细缺失」与「模型明确说没答到点上」是两件事，
 * 用 {@code false} 顶替会把后者（一个真实结论）凭空造出来。
 *
 * @param score    1~5 的自评分（来自列，恒有值）
 * @param answered 明细里的「是否回答了用户的问题」；null = 明细不可读
 * @param grounded 明细里的「是否只依据给到的依据作答」；null = 明细不可读
 * @param issues   问题短语（最多 3 条，可能为空表）
 * @param comment  模型给的一句话说明
 * @param trigger  触发来源：{@code SAMPLE}=按比例抽检，{@code FEEDBACK}=用户点踩强制
 * @param at       自评发生时间（ISO 字符串；相对那一轮本身是后补的，故单独记）
 */
public record SelfEvalDto(int score, Boolean answered, Boolean grounded, List<String> issues,
                          String comment, String trigger, String at) {

    /** 触发来源：按比例采样抽检。 */
    public static final String TRIGGER_SAMPLE = "SAMPLE";
    /** 触发来源：用户点踩强制（不看开关与采样率）。 */
    public static final String TRIGGER_FEEDBACK = "FEEDBACK";

    /**
     * 从追踪行取自评：{@code self_eval_score} 为 NULL 即「这一轮没被评到」（采样未命中 / 回答过短 /
     * 调用失败 / 尚未评完），返回 {@code null} —— 与「评了低分」是两件事，界面上必须分开。
     */
    public static SelfEvalDto of(AgentTrace t) {
        if (t == null || t.getSelfEvalScore() == null) return null;
        JSONObject o = parseObject(t.getSelfEvalJson());
        return new SelfEvalDto(t.getSelfEvalScore(),
                o == null ? null : o.getBool("answered"),
                o == null ? null : o.getBool("grounded"),
                o == null ? List.of() : strings(o.getJSONArray("issues")),
                o == null ? null : o.getStr("comment"),
                o == null ? null : o.getStr("trigger"),
                o == null ? null : o.getStr("at"));
    }

    /** 自身 JSON 的解析（只供 {@link #of} 与自检使用；解析失败返回 null，调用方按「明细缺失」处理）。 */
    private static JSONObject parseObject(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSONUtil.parseObj(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> strings(JSONArray arr) {
        if (arr == null || arr.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            String s = arr.getStr(i);
            if (s != null && !s.isBlank()) out.add(s);
        }
        return out;
    }
}
