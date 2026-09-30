package org.luo.ai.controller;

import org.luo.ai.dto.ObservabilitySummary;
import org.luo.ai.service.ObservabilityService;
import org.luo.system.constant.SysRoleCode;
import org.luo.system.security.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可观测面板查询接口（只读，聚合 {@code agent_trace}）。<b>仅 ADMIN 可访问</b>。
 * <p>
 * GET /api/observability/summary（近 N 天运行质量：成功率 / 平均耗时 / 按天 / 按模式 / 按来源 / 按智能体 / 慢轮 Top N）。
 * <p>
 * <b>为什么是管理员专属</b>：与 {@link CostController} 同一逻辑——这是<b>跨会话全站聚合</b>的运营视角，
 * 聚合成「今天跑了多少轮、成功率多少」之后就分不出是谁的，按归属收敛技术上做不到；按人拆分又会暴露
 * 他人用量与失败情况。逐条看自己某会话的链路细节走 {@link TraceController}（已按归属隔离），
 * 全站质量看这里（仅 ADMIN）。
 */
@RestController
@RequestMapping("/api/observability")
@RequireRole(SysRoleCode.ADMIN)
public class ObservabilityController {

    private final ObservabilityService observabilityService;

    public ObservabilityController(ObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    /** 近 N 天可观测聚合。days 可选（默认 7、上限 90）。 */
    @GetMapping("/summary")
    public ObservabilitySummary summary(@RequestParam(required = false) Integer days) {
        return observabilityService.summary(days);
    }
}
