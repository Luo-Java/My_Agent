package org.luo.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 提示词回归评测结果：一次跑批的每个用例一行，按 {@code batch_id} 分组。
 * <p>
 * 与 {@code agent_trace} 同为「旁路留痕」，但目的不同：trace 记的是一轮真实对话的过程，本表记的是
 * <b>固定用例集</b>在某一版提示词下的判定结果——有了它，改 {@code prompts.yaml} 前后的对比才有依据。
 * <p>
 * 只留最近若干个批次（见 {@code EvalService} 的清理），它不需要长期归档：价值在「和上一次比」，
 * 不在「翻了三个月前的一次」。
 */
@Data
@NoArgsConstructor
@TableName("eval_result")
public class EvalResult {

    /** 自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 批次 ID：同一次跑批的所有用例共用一个，批次间对比按它取数。 */
    private String batchId;

    /** 用例场景：ROUTE=智能路由 / PLAN=动态规划。 */
    private String scenario;

    /** 用例名（来自 eval-cases.yaml），批次间以它对齐身份。 */
    private String caseName;

    /** 用例输入。 */
    private String input;

    /** 期望值摘要（用例 expect 的文本化）。 */
    private String expected;

    /** 实际值摘要（被测组件真实产出的决策）。 */
    private String actual;

    /** 是否通过。 */
    private Boolean passed;

    /** 用例自身配置错误（如引用了不存在的 agentCode）：计入本列而非 failed，避免「用例写错」污染提示词质量判断。 */
    private Boolean configError;

    /** 失败原因（逐条断言的差异说明）。 */
    private String failure;

    /** 被测组件的完整原始输出，排查用。 */
    private String detail;

    /** 该用例耗时（毫秒）。 */
    private Integer costMs;

    private LocalDateTime createdAt;
}
