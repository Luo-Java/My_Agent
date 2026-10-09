package org.luo.system.controller;

import org.luo.common.result.PageResult;
import org.luo.common.result.RestResult;
import org.luo.system.constant.SysRoleCode;
import org.luo.system.entity.AuditLog;
import org.luo.system.security.RequireRole;
import org.luo.system.service.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理操作审计查询（{@code /api/audit}）：只读，整个控制器限 ADMIN。
 * <p>
 * <b>为什么只读</b>：审计记录的价值来自「改不掉」。本控制器<b>不提供</b>任何写入 / 修改 / 删除入口 ——
 * 写入由业务侧在动作发生时调用 {@link AuditService#record}，清理只能由 DBA 直接操作数据库。
 * 一个能通过接口删审计的系统，等于没有审计。
 * <p>
 * <b>为什么返回实体而不是 VO</b>：与 {@code SysUser} 必须走 VO 的情况不同（那个藏的是 {@code password}），
 * 审计记录本身就是「给人看的记录」，没有需要隐藏的字段 —— 明细在<b>写入时</b>就已脱敏（见
 * {@link AuditService}），而不是靠读取时过滤。读取侧再遮一层只会造成「库里存着原文」的错觉。
 */
@RestController
@RequestMapping("/api/audit")
@RequireRole(SysRoleCode.ADMIN)
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 分页查询审计记录（时间倒序）。
     *
     * @param page   页码（从 1 起）
     * @param size   每页条数（上限 100）
     * @param action 动作编码筛选（可空 = 全部）
     */
    @GetMapping("/page")
    public RestResult<PageResult<AuditLog>> page(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size,
                                                 @RequestParam(required = false) String action) {
        return RestResult.ok(auditService.page(page, size, action));
    }
}
