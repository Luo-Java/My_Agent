package org.luo.ai.dto;

import cn.hutool.json.JSONObject;

/**
 * 一条评测用例（来自 {@code eval-cases.yaml} 或库内 {@code eval_case} 表）。
 *
 * @param name            用例名（对比批次时作为身份标识，改名会被视作「删旧增新」）
 * @param scenario        场景：{@link #SCENARIO_ROUTE} / {@link #SCENARIO_PLAN}
 * @param input           输入（路由场景=用户消息；规划场景=用户目标）
 * @param pendingQuestion 路由场景的「待回答追问」上下文（可空）：用于验证「在回答追问」不被误判成新话题
 * @param expect          期望值（键随场景不同，见 {@link org.luo.ai.service.EvalService} 的断言实现）
 * @param source          来源：{@link #SOURCE_YAML} / {@link #SOURCE_FEEDBACK}。仅供前端标注与排查用，
 *                        <b>不参与断言</b> —— 断言该看的是内容，不是它从哪来
 */
public record EvalCase(String name, String scenario, String input, String pendingQuestion, JSONObject expect,
                       String source) {

    /** 场景：智能路由（{@code agent.prompt.router-system}）。 */
    public static final String SCENARIO_ROUTE = "ROUTE";

    /** 场景：动态规划（{@code agent.prompt.planner-system}）。 */
    public static final String SCENARIO_PLAN = "PLAN";

    /** 来源：用例集文件（打包进 jar 的只读种子）。 */
    public static final String SOURCE_YAML = "YAML";

    /** 来源：由用户反馈转入（存 {@code eval_case} 表）。 */
    public static final String SOURCE_FEEDBACK = "FEEDBACK";

    /** 用例集文件里的用例（来源恒为 {@link #SOURCE_YAML}）—— 读取 yaml 时少写一个参数。 */
    public EvalCase(String name, String scenario, String input, String pendingQuestion, JSONObject expect) {
        this(name, scenario, input, pendingQuestion, expect, SOURCE_YAML);
    }

    /** 该用例是否同时属于指定场景；{@code null} / 空表示「全部场景」。 */
    public boolean matches(String scenarioFilter) {
        return scenarioFilter == null || scenarioFilter.isBlank() || scenarioFilter.equals(scenario);
    }
}
