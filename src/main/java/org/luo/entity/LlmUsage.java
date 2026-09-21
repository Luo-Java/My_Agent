package org.luo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 单次裸 LLM 调用的成本流水（全量成本口径）。
 * <p>
 * 与 {@code agent_trace}（只记「正式回答 + 工具循环」的 token）互补：路由判定、参数抽取、查询改写、
 * 视觉识别、记忆合并这些<b>裸 {@code ChatModel.call()}</b>（不经 Advisor）的 token 在这里各自记一条，
 * 使得成本看板能做<b>真正全量</b>的聚合与按用途（{@link #purpose}）拆解。
 * <p>
 * 与 agent_trace 同款旁路语义：异步落库、失败只记日志、业务代码不读它、删表不影响对话。
 */
@Data
@NoArgsConstructor
@TableName("llm_usage")
public class LlmUsage {

    /** 自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 本轮追踪 ID（可空：视觉识别是独立请求、早于 trace 建立，记不进当轮 trace）。 */
    private String traceId;

    /** 所属会话 ID（可空：如生成智能体人设这类无会话的调用）。 */
    private String conversationId;

    /** 调用用途：ROUTE=智能路由 / CLARIFY=参数抽取 / REWRITE=查询改写 / VISION=视觉识别 / MEMORY_MERGE=记忆合并。 */
    private String purpose;

    /** 实际使用的模型名（可空：未显式指定时取默认模型）。 */
    private String model;

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

    private LocalDateTime createdAt;
}
