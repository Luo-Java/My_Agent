package org.luo.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工具审批闸门记录（对应 {@code tool_approval} 表）：回答「这个工具在本会话里放行了吗、谁批的、批完用了几次」。
 * <p>
 * <b>为什么需要它</b>：工具调用发生在模型内部（Spring AI 的 {@code ToolCallback.call}），此前一路绿灯 ——
 * 查库 / 画图 / 转交一键就执行，用户既看不到「这一步要做什么」，也没有喊停的机会。本表是那道闸门的落点：
 * 命中拦截时落一行 {@code PENDING}，用户在前端点批准，下一次调用才真正执行。
 * <p>
 * <b>粒度是「会话 × 工具」一行</b>（{@code uk_conv_tool}），语义是「本会话内该工具是否放行」，而不是
 * 「这一次调用批不批」。理由：一次授权、后续调用不再逐次打断，用户才受得了；反过来「拒绝」也一直挡住，
 * 不反复弹窗。授权带有效期（见 {@code agent.tool-approval.expire-minutes}），过期后自动回到待确认 ——
 * 既不留下长期敞口，也不至于每条都问。
 * <p>
 * <b>归属不落 user_id 列</b>：加了就得把身份一路传进工具回调链路，而工具回调只拿得到会话上下文。
 * 归属一律查询侧 {@code JOIN conversation} 判定（与 {@code agent_trace} 同一口径与同一理由）。
 */
@Data
@NoArgsConstructor
@TableName("tool_approval")
public class ToolApproval {

    /** 待确认：已拦截，等用户决断。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 已批准：本会话内该工具可执行（超过有效期则视同未决）。 */
    public static final String STATUS_APPROVED = "APPROVED";
    /** 已拒绝：本会话内该工具一直挡住（撤销后才回到待确认）。 */
    public static final String STATUS_REJECTED = "REJECTED";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属会话，关联 {@code conversation.id}。 */
    private String conversationId;

    /** 发起调用的智能体 ID（可空：通用助手 / 转交接力场景）。 */
    private Long agentId;

    /** 工具名（与 {@code agent.tools_json} 白名单里的名字一致）。 */
    private String toolName;

    /** 触发拦截的入参原样留档：用户据此判断「它到底要干什么」才谈得上批准。 */
    private String inputJson;

    /** 触发该调用的用户原话（已截断）：批准后据此重跑同一轮，不必让用户再打一遍。 */
    private String userMessage;

    /** 状态：PENDING / APPROVED / REJECTED。 */
    private String status;

    /** 用户决断时的备注（可空）。 */
    private String note;

    /** 决断人 ID，关联 {@code sys_user.id}（谁点的批准 —— 审计要答得出）。 */
    private Long decidedBy;

    /** 决断时间；{@code PENDING} 时为 NULL。 */
    private LocalDateTime decidedAt;

    /** 批准后实际放行执行次数（回答「批了之后真的用了吗」）。 */
    private Integer usedCount;

    /** 最近一次放行执行时间。 */
    private LocalDateTime lastUsedAt;

    /** 首次拦截时间。 */
    private LocalDateTime createdAt;
}
