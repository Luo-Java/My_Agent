package org.luo.ai.dto;

import org.luo.ai.entity.ToolApproval;

import java.time.LocalDateTime;

/**
 * 工具审批记录的对外投影（{@code GET /api/tool-approval}）。
 * <p>
 * <b>为什么要有 DTO 而不是直接出实体</b>：实体的 {@code status} 是「库里怎么记的」，而前端要判的是
 * 「这条现在还算不算数」—— 已批准的授权过了有效期就<b>等效于待确认</b>，但这个降级是惰性的（下一次工具
 * 调用时才写回库）。让服务端一次算清 {@code effective}，前端就不必各写一份「有没有过期」的判据，
 * 也就不会出现「界面说已批准、闸门却拦住」这种两边不一致。
 *
 * @param id             主键
 * @param agentId        发起调用的智能体 ID（可空）
 * @param toolName       工具名
 * @param inputJson      触发拦截的入参（原样，前端截断展示）
 * @param userMessage    触发该调用的用户原话（批准后据此重跑同一轮）
 * @param status         库中状态：PENDING / APPROVED / REJECTED
 * @param effective      当前实际生效状态（把「已过期的 APPROVED」折算为 PENDING）
 * @param note           用户决断备注
 * @param decidedAt      决断时间
 * @param usedCount      批准后放行执行次数
 * @param lastUsedAt     最近一次放行执行时间
 * @param createdAt      首次拦截时间
 * @param expireMinutes  本次授权有效期（分钟，0=永不过期），供前端解释「为什么又问了」
 */
public record ToolApprovalDto(Long id, Long agentId, String toolName, String inputJson, String userMessage,
                              String status, String effective, String note, LocalDateTime decidedAt,
                              Integer usedCount, LocalDateTime lastUsedAt, LocalDateTime createdAt,
                              int expireMinutes) {

    /**
     * 折算生效状态：{@code APPROVED} 过了有效期就视同 {@code PENDING}（用户得重新确认），
     * {@code REJECTED} 永久有效（用户说过不行，只有显式撤销才改变 —— 不搞「过一会儿自动放行」）。
     */
    public static ToolApprovalDto of(ToolApproval e, int expireMinutes) {
        if (e == null) return null;
        return new ToolApprovalDto(e.getId(), e.getAgentId(), e.getToolName(), e.getInputJson(), e.getUserMessage(),
                e.getStatus(), effective(e, expireMinutes, LocalDateTime.now()), e.getNote(), e.getDecidedAt(),
                e.getUsedCount() == null ? 0 : e.getUsedCount(), e.getLastUsedAt(), e.getCreatedAt(), expireMinutes);
    }

    /** 生效状态判据：与 {@code ToolApprovalService} 的放行判据必须同一口径（改一处就得改另一处）。 */
    private static String effective(ToolApproval e, int expireMinutes, LocalDateTime now) {
        if (!ToolApproval.STATUS_APPROVED.equals(e.getStatus())) return e.getStatus();
        LocalDateTime at = e.getDecidedAt() != null ? e.getDecidedAt() : e.getCreatedAt();
        if (expireMinutes <= 0 || at == null) return ToolApproval.STATUS_APPROVED;
        return at.plusMinutes(expireMinutes).isAfter(now) ? ToolApproval.STATUS_APPROVED : ToolApproval.STATUS_PENDING;
    }

    /** 是否仍待用户决断（前端据此渲染待确认条）。 */
    public boolean pending() {
        return ToolApproval.STATUS_PENDING.equals(effective);
    }
}
