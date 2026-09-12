package org.luo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 智能体链路追踪记录：一轮对话的全链路留痕（可观测性）。
 * <p>
 * 与业务表的关键区别：这是<b>旁路数据</b>——异步落库、失败只记日志，不参与任何对话逻辑；
 * 反之业务逻辑也从不读取它。删掉这张表，对话功能不受任何影响。
 */
@Data
@NoArgsConstructor
@TableName("agent_trace")
public class AgentTrace {

    /** 自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 本轮唯一追踪 ID（UUID，与本轮日志里的 traceId 对应）。 */
    private String traceId;

    private String conversationId;

    /** 本轮形态：agent=普通/智能体对话，planner=规划模式。 */
    private String mode;

    /** 处理方来源：BOUND=会话显式绑定，ROUTE=智能路由命中，NONE=通用助手，PLAN=规划编排。 */
    private String routeSource;

    /** 本轮实际处理（规划模式为最终步骤）的智能体编码。 */
    private String agentCode;

    private String userMessage;

    /**
     * 本轮实际用于知识库检索的问题（多轮查询改写的产物）。
     * null = 未改写（未开 RAG / 首轮无历史 / 关闭改写 / 模型判定原话已自包含）。
     */
    private String retrievalQuery;

    /** 规划模式的步骤计划 JSON。 */
    private String planJson;

    /** 工具调用明细 JSON 数组（name/args/result，均截断）。 */
    private String toolCalls;

    private Integer kbHitCount;

    /** RAG 引用来源 JSON（与 chat_message.citations_json 同构）。 */
    private String citationsJson;

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

    /** 本轮总耗时（毫秒）。 */
    private Long elapsedMs;

    /** ok / error。 */
    private String status;

    private String errorMessage;

    private LocalDateTime createdAt;
}
