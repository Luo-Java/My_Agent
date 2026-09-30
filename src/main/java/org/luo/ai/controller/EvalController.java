package org.luo.ai.controller;

import org.luo.ai.dto.EvalBatchResult;
import org.luo.ai.dto.EvalBatchSummary;
import org.luo.ai.dto.EvalCase;
import org.luo.ai.dto.EvalCaseResult;
import org.luo.ai.dto.EvalCompare;
import org.luo.ai.service.EvalService;
import org.luo.system.constant.SysRoleCode;
import org.luo.system.security.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提示词回归评测接口。用于回答「改了 {@code prompts.yaml} 之后到底变好还是变差」：跑一批固定用例，
 * 与上一次批次对比。
 * <p>
 * 用法闭环：改提示词 → {@code POST /api/eval/run} → {@code GET /api/eval/compare?from=<上一批>&to=<本批>}，
 * 返回的 {@code broken} 就是「这次改坏了哪些用例」。
 * <p>
 * 返回体不做 {@code RestResult} 包装，与 {@link TraceController} 一致（AI 层只读/工具接口的既有风格）。
 * 走统一 {@code /api/**} 登录鉴权（JwtAuthInterceptor）。
 * <p>
 * <b>为什么是管理员专属</b>：{@code POST /run} 会发起<b>真实模型调用</b>（一次跑批 13 条用例 = 13 次 LLM 请求），
 * 消耗计入 {@code llm_usage}/{@code agent_trace} 成本流水。与 {@link CostController} 同一性质——
 * 都是「会花钱的运维动作」，不该交给任意登录用户。只读的 {@code /cases}、{@code /batches}、
 * {@code /compare} 本可放宽，但它们只服务于「跑批」这一件事，单独放开没有使用场景，
 * 反而多一处需要单独解释的权限边界，故整类收敛。
 * 校验走统一 {@code /api/**} 登录鉴权 + {@link RequireRole}（不通过返回 403），
 * 前端顶栏「🧪 评测」入口按同一角色显隐。
 */
@RestController
@RequestMapping("/api/eval")
@RequireRole(SysRoleCode.ADMIN)
public class EvalController {

    private final EvalService evalService;

    public EvalController(EvalService evalService) {
        this.evalService = evalService;
    }

    /** 用例清单（只读用例集、不跑批），供前端预览「这次会跑哪些」。 */
    @GetMapping("/cases")
    public List<EvalCase> cases(@RequestParam(required = false) String scenario) {
        return evalService.loadCases(scenario);
    }

    /**
     * 跑一批并同步返回结果（同时落库供后续对比）。
     * 用例数上限 50、单条超时见 {@code agent.eval.timeout-seconds}；超限直接 400，不静默截断。
     */
    @PostMapping("/run")
    public EvalBatchResult run(@RequestParam(required = false) String scenario) {
        return evalService.run(scenario);
    }

    /** 历史批次列表（时间倒序）。前端用它选「拿哪两批做对比」。 */
    @GetMapping("/batches")
    public List<EvalBatchSummary> batches() {
        return evalService.listBatches();
    }

    /** 某批次的逐条明细。 */
    @GetMapping("/batches/{batchId}")
    public List<EvalCaseResult> batchDetail(@PathVariable String batchId) {
        return evalService.batchDetail(batchId);
    }

    /** 两批对比：fixed=改好了，broken=改坏了（最该看的一档），stillFailed=两次都没过。 */
    @GetMapping("/compare")
    public EvalCompare compare(@RequestParam String from, @RequestParam String to) {
        return evalService.compare(from, to);
    }
}
