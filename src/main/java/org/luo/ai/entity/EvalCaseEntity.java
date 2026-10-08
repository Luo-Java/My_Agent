package org.luo.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 库内回归用例实体（对应 {@code eval_case} 表）。
 * <p>
 * 与 {@code eval-cases.yaml} 的分工：<b>yaml 是只读种子</b>（打包进 jar，运行时写不了），本表承接运行时
 * 新增的用例（当前唯一来源是用户反馈）。两者在 {@code EvalService.loadCases} 里合并参与跑批，
 * <b>同名以 yaml 为准</b> —— 种子是人工审校过的，不该被一条自动生成的记录静默顶掉。
 * <p>
 * 类名带 {@code Entity} 后缀是因为 DTO {@link org.luo.ai.dto.EvalCase} 已经占用了「用例」这个自然名字：
 * 那个是跨层传递的读模型（yaml 与库内用例合并后的统一形态），这个是库表行。
 */
@Data
@NoArgsConstructor
@TableName("eval_case")
public class EvalCaseEntity {

    /** 来源：用例集文件（本表不写该值，仅供展示层标注混合来源时使用）。 */
    public static final String SOURCE_YAML = "YAML";

    /** 来源：由用户反馈转入。 */
    public static final String SOURCE_FEEDBACK = "FEEDBACK";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用例名：批次对比的身份标识（改名会被视作一增一删）。 */
    private String name;

    /** 场景：ROUTE / PLAN。 */
    private String scenario;

    /** 用例输入（路由场景=用户消息；规划场景=用户目标）。 */
    private String input;

    /** 路由场景的「待回答追问」上下文（可空）。 */
    private String pendingQuestion;

    /** 期望断言（JSON 字符串，键与 yaml 用例的 {@code expect} 同构）。 */
    private String expectJson;

    /** 来源：FEEDBACK（当前唯一取值）。 */
    private String source;

    /** 来源反馈 ID，关联 {@code message_feedback.id}。 */
    private Long feedbackId;

    /** 是否参与跑批：0=停用（留在库里但不再计入批次与对比）。 */
    private Boolean enabled;

    private LocalDateTime createdAt;
}
