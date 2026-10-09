package org.luo.ai.controller;

import org.luo.ai.dto.ToolApprovalDecision;
import org.luo.ai.dto.ToolApprovalDto;
import org.luo.ai.service.ToolApprovalService;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.system.security.AuthContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工具审批闸门接口：GET /api/tool-approval（本人某会话的记录）、POST /api/tool-approval/{id}/approve|reject|reset。
 * <p>
 * <b>只要求登录</b>：审批是「我自己的会话里这个工具放不放行」，天然按人隔离 —— 归属由服务层
 * {@code JOIN conversation} 判定（本表不存 user_id），越权与不存在一律 <b>404</b>，不泄漏记录是否存在。
 * <p>
 * <b>为什么不给 ADMIN 一个全局视角</b>：跨会话的「谁批了什么」属于管理操作审计，是另一件事（另建审计表），
 * 不靠给这张业务表加一个管理员入口来凑。本接口只服务「用户看着自己会话里的闸门做决断」这一场景。
 */
@RestController
@RequestMapping("/api/tool-approval")
public class ToolApprovalController {

    private final ToolApprovalService service;

    public ToolApprovalController(ToolApprovalService service) {
        this.service = service;
    }

    /** 本人某会话下的审批记录（时间倒序，最多 50 条）；不传会话则回本人全部。 */
    @GetMapping
    public List<ToolApprovalDto> list(@RequestParam(required = false) String conversationId) {
        return service.list(AuthContext.require().id(), conversationId);
    }

    /** 批准：本会话内该工具放行。返回更新后的记录，前端据此重跑那一轮。 */
    @PostMapping("/{id}/approve")
    public ToolApprovalDto approve(@PathVariable Long id, @RequestBody(required = false) ToolApprovalDecision body) {
        return require(service.approve(id, AuthContext.require().id(), note(body)));
    }

    /** 拒绝：本会话内该工具一直挡住（撤销前不再询问）。 */
    @PostMapping("/{id}/reject")
    public ToolApprovalDto reject(@PathVariable Long id, @RequestBody(required = false) ToolApprovalDecision body) {
        return require(service.reject(id, AuthContext.require().id(), note(body)));
    }

    /** 撤销决断：回到待确认，让用户改主意（拒绝是一票到底，没有撤销口就只能删会话）。 */
    @PostMapping("/{id}/reset")
    public ToolApprovalDto reset(@PathVariable Long id) {
        return require(service.reset(id, AuthContext.require().id()));
    }

    /** 统一把「不存在 / 非本人」折算成 404，两者不可区分（防 ID 探测）。 */
    private static ToolApprovalDto require(ToolApprovalDto dto) {
        if (dto == null) throw new AiBusinessException(AiErrorCode.NOT_FOUND, "审批记录不存在");
        return dto;
    }

    private static String note(ToolApprovalDecision body) {
        return body == null ? null : body.note();
    }
}
