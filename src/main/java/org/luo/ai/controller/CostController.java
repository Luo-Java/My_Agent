package org.luo.ai.controller;

import org.luo.ai.dto.CostSummary;
import org.luo.ai.service.CostService;
import org.luo.system.constant.SysRoleCode;
import org.luo.system.security.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成本看板查询接口（只读，全量成本口径）。<b>仅 ADMIN 可访问</b>。
 * <p>
 * GET /api/cost/summary（近 N 天全量成本聚合：按天趋势 + 按用途拆解）。数据来自两张旁路表——
 * agent_trace（回答本身）与 llm_usage（路由/参数抽取/查询改写/任务规划/视觉/记忆合并等裸调用）。
 * <p>
 * <b>为什么是管理员专属而不是「每人看自己的」</b>：这张看板的口径是**全站聚合**，天然跨用户，
 * 无法按归属收敛（聚合成一行「今天花了多少 token」后就分不出是谁的）；而「你的成本是多少」这种
 * 按人拆分的口径会暴露他人用量对比，产品上也没有这个诉求。成本属于运营视角数据，收敛到 ADMIN。
 * 校验走统一 {@code /api/**} 登录鉴权 + {@link RequireRole}（不通过返回 403），
 * 前端顶栏「💰 成本」入口按同一角色显隐，普通用户根本看不到入口。
 */
@RestController
@RequestMapping("/api/cost")
@RequireRole(SysRoleCode.ADMIN)
public class CostController {

    private final CostService costService;

    public CostController(CostService costService) {
        this.costService = costService;
    }

    /** 近 N 天成本汇总。days 可选（默认 30、上限 90）。 */
    @GetMapping("/summary")
    public CostSummary summary(@RequestParam(required = false) Integer days) {
        return costService.summary(days);
    }
}
