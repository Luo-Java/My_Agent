package org.luo.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 提示词快照：把「某一版 {@code prompts.yaml} 的评测结论」固化成一行，让「改了提示词有没有变差」有据可查。
 * <p>
 * <b>为什么需要它</b>：{@code eval_result} 只记「某一批跑了什么」，不知道自己跑的是哪一版提示词。
 * 于是「这批是改前还是改后」只能靠人记时间点；重启一次、隔天再看就分不清了。本表把
 * <b>内容指纹（{@code fingerprint}）</b>与批次绑定，改没改、改完跑没跑、跑完是变好还是变差，一眼可判。
 * <p>
 * <b>不认识 prompt 的语义</b>：指纹是内容 SHA-256 的定长前缀，只回答「变了没有」，不回答「改了哪句」——
 * 后者要 diff 工具，不是本表的职责。注释类改动同样会让指纹变化（这是刻意的：注释也会进模型上下文）。
 * <p>
 * {@code verdict} 四态与 {@code EvalService} 的三态一一对应并多一态：
 * <ul>
 *   <li>{@link #VERDICT_PENDING} —— 检测到变更但<b>还没跑批</b>（未开启自动跑批，或跑批尚未触发）；</li>
 *   <li>{@link #VERDICT_PASS} —— 跑了，且相对上一批没有「上批过、本批败」的用例；</li>
 *   <li>{@link #VERDICT_DEGRADED} —— 跑了，出现 {@code broken}（上批过、本批败）—— 这是<b>最该看的一档</b>；</li>
 *   <li>{@link #VERDICT_STILL_FAILED} —— 跑了，没有新坏但仍有一直没过的用例（与 PASS 分开，别把「本来就差」读成「改对了」）。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@TableName("prompt_snapshot")
public class PromptSnapshot {

    /** 已检测到提示词变更，但尚未跑批验证。 */
    public static final String VERDICT_PENDING = "PENDING";
    /** 跑批通过：相对上一批无 broken。 */
    public static final String VERDICT_PASS = "PASS";
    /** 跑批劣化：出现 broken（上批通过、本批失败）—— 最该关注。 */
    public static final String VERDICT_DEGRADED = "DEGRADED";
    /** 跑批完成：无新 broken，但仍有一直未通过的用例。 */
    public static final String VERDICT_STILL_FAILED = "STILL_FAILED";
    /** 跑批本身失败（用例集缺失/解析失败/无匹配用例等），未产出结论。 */
    public static final String VERDICT_ERROR = "ERROR";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** prompts.yaml 内容的 SHA-256 定长前缀（只回答「变了没有」，不回答「改了哪句」）。 */
    private String fingerprint;

    /** 关联 {@code eval_result.batch_id}；NULL = 尚未跑批（PENDING）。 */
    private String batchId;

    /** 本次跑批用例总数。 */
    private Integer total;

    /** 通过数。 */
    private Integer passed;

    /** 失败数（不含配置错误）。 */
    private Integer failed;

    /** 用例配置错误数（用例自己写错，不是提示词问题）。 */
    private Integer configError;

    /** 相对上一批「上批过、本批败」的用例数。 */
    private Integer brokenCount;

    /** 相对上一批「上批败、本批过」的用例数。 */
    private Integer fixedCount;

    /** 结论：PENDING / PASS / DEGRADED / STILL_FAILED / ERROR。 */
    private String verdict;

    /** 明细 JSON：{@code {"broken":[...],"fixed":[...],"error":"..."}}，供前端展开看是哪几条。 */
    private String detailJson;

    private LocalDateTime createdAt;
}
