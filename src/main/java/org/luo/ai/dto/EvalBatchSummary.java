package org.luo.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批次摘要（历史批次列表的一行）。
 * <p>
 * 用 {@code @Data} bean 而非 record：本类既做 MyBatis 结果投影（列名 AS 别名与字段一一对应，
 * 见 {@code mapper/ai/EvalResultMapper.xml}）又做接口返回体，bean 的 setter 映射不依赖
 * 构造器参数名保留（{@code -parameters}）这一编译期条件，更稳。
 */
@Data
public class EvalBatchSummary {

    /** 批次 ID。 */
    private String batchId;

    /** 批次时间（同批次各行的最小 created_at）。 */
    private LocalDateTime batchAt;

    /** 用例总数。 */
    private Integer total;

    /** 通过数。 */
    private Integer passed;

    /** 失败数（不含配置错误）。 */
    private Integer failed;

    /** 配置错误数。 */
    private Integer configErrors;

    /** 该批次覆盖的场景；批次内混合多个场景时为 null。 */
    private String scenario;
}
