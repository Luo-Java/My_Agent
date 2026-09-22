package org.luo.ai.controller;

import org.luo.ai.dto.CostSummary;
import org.luo.ai.service.CostService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成本看板查询接口（只读，全量成本口径）。
 * <p>
 * GET /api/cost/summary（近 N 天全量成本聚合：按天趋势 + 按用途拆解）。数据来自两张旁路表——
 * agent_trace（回答本身）与 llm_usage（路由/参数抽取/查询改写/视觉/记忆合并等裸调用），
 * 走统一 {@code /api/**} 鉴权（ApiKeyInterceptor）。
 */
@RestController
@RequestMapping("/api/cost")
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
