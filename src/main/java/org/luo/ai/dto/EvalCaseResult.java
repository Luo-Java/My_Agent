package org.luo.ai.dto;

/**
 * 单条用例的评测结果。
 *
 * @param caseName    用例名
 * @param scenario    场景（ROUTE / PLAN）
 * @param input       用例输入
 * @param expected    期望值摘要（用例 expect 的文本化，便于前端与历史对比）
 * @param actual      实际值摘要（被测组件真实产出的决策）
 * @param passed      是否通过
 * @param configError 用例自身配置错误（如引用了不存在的 agentCode、场景名写错）：
 *                    这类<b>不计入</b>提示词质量——把「用例写错」算成「prompt 改坏」会让回归结果失去意义
 * @param failure     失败原因（逐条断言的差异说明；通过时为空串）
 * @param detail      被测组件的完整原始输出（排查用）
 * @param costMs      该用例耗时（毫秒）
 */
public record EvalCaseResult(String caseName, String scenario, String input, String expected, String actual,
                             boolean passed, boolean configError, String failure, String detail, long costMs) {

    /** 用例配置错误（不算失败，单列一档）。 */
    public static EvalCaseResult configError(String caseName, String scenario, String input,
                                             String expected, String reason, long costMs) {
        return new EvalCaseResult(caseName, scenario, input, expected, "-", false, true, reason, "", costMs);
    }
}
