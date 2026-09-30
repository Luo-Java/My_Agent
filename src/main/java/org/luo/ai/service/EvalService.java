package org.luo.ai.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.setting.yaml.YamlUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.agent.AgentRouter;
import org.luo.ai.agent.PlannerService;
import org.luo.ai.agent.PlannerService.PlanStep;
import org.luo.ai.dto.EvalBatchResult;
import org.luo.ai.dto.EvalBatchSummary;
import org.luo.ai.dto.EvalCase;
import org.luo.ai.dto.EvalCaseResult;
import org.luo.ai.dto.EvalCompare;
import org.luo.ai.entity.EvalResult;
import org.luo.ai.mapper.EvalResultMapper;
import org.luo.ai.properties.EvalProperties;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 提示词回归评测服务：把固定用例集逐条喂给真实的决策组件（{@link AgentRouter} / {@link PlannerService}）
 * 并断言结果，产出可跨批次对比的通过 / 失败清单。
 * <p>
 * <b>为什么不判答案质量</b>：判定「回答得好不好」需要 LLM 当裁判，而裁判自身不稳定、同一次改动前后可能给出
 * 相反的判断，回归结果就失去可比性。这里只断言<b>可确定的部分</b>——路由到哪个智能体、判定是否在回答追问、
 * 计划几步、包含哪些智能体。改坏了这些，答案质量必然保不住；这些没坏而答案变差，是另一层问题（属于模型自身
 * 波动，不是 prompt 配置的问题）。
 * <p>
 * <b>用例配置错误单独一档</b>：用例引用了 agent 表里不存在的 agentCode、场景名写错等，算「用例写错」而非
 * 「prompt 改坏」。混在一起会让回归结果失去意义（每次跑都失败，却不知道是自己写错了还是真改坏了）。
 * <p>
 * <b>成本可识别</b>：评测跑的是真实调用，token 会照常进 {@code llm_usage}，但 {@code conversation_id} 统一
 * 打上 {@link #EVAL_CONVERSATION_ID} 标记 —— 它不属于任何真实会话，不该混进真实对话的成本分布。
 */
@Slf4j
@Service
public class EvalService {

    /** 跑批时写给 {@code llm_usage.conversation_id} 的标记值（见类注释「成本可识别」）。 */
    public static final String EVAL_CONVERSATION_ID = "__eval__";

    /** 保留的批次数（含最新）：评测的价值在「和上一次比」，不需要长期归档。 */
    private static final int KEEP_BATCHES = 20;

    /** 单批用例数上限：防误把上百条用例一次点亮、白烧 token。超限<b>直接报错</b>，不静默截断。 */
    private static final int MAX_CASES = 50;

    private final EvalProperties props;
    private final ResourceLoader resourceLoader;
    private final AgentRouter agentRouter;
    private final PlannerService plannerService;
    private final AgentService agentService;
    private final EvalResultMapper evalResultMapper;
    private final Executor evalExecutor;

    public EvalService(EvalProperties props,
                       ResourceLoader resourceLoader,
                       AgentRouter agentRouter,
                       PlannerService plannerService,
                       AgentService agentService,
                       EvalResultMapper evalResultMapper,
                       @Qualifier("evalExecutor") Executor evalExecutor) {
        this.props = props;
        this.resourceLoader = resourceLoader;
        this.agentRouter = agentRouter;
        this.plannerService = plannerService;
        this.agentService = agentService;
        this.evalResultMapper = evalResultMapper;
        this.evalExecutor = evalExecutor;
    }

    // ------------------------------------------------------------------
    // 用例集
    // ------------------------------------------------------------------

    /**
     * 读取用例集并按场景过滤。
     * <p>
     * 文件不存在 / YAML 解析失败 / 缺 {@code cases} 列表 → 抛 400：评测自己坏掉必须立刻说清楚，
     * 不能悄悄跑出个空结果让人误以为「全过了」。单条用例字段不全则跳过并告警（不影响其余用例）。
     */
    public List<EvalCase> loadCases(String scenarioFilter) {
        Resource res = resourceLoader.getResource(props.casesFile());
        if (!res.exists()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "评测用例集不存在：" + props.casesFile());
        }
        JSONArray raw;
        // Hutool 的 YamlUtil 只有 load(Reader) / load(InputStream, Class)，没有 load(InputStream)：
        // 显式按 UTF-8 包一层 Reader —— 用例名与断言说明都是中文，跟随平台默认编码会在非 UTF-8 环境读成乱码。
        try (InputStream in = res.getInputStream();
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            raw = JSONUtil.parseObj(YamlUtil.load(reader)).getJSONArray("cases");
        } catch (Exception e) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "评测用例集解析失败：" + props.casesFile() + " —— " + e.getMessage(), e);
        }
        if (raw == null || raw.isEmpty()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "评测用例集为空（缺少 cases 列表）：" + props.casesFile());
        }
        List<EvalCase> out = new ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof JSONObject c)) continue;
            String name = c.getStr("name");
            String scenario = c.getStr("scenario");
            String input = c.getStr("input");
            if (isBlank(name) || isBlank(scenario) || isBlank(input)) {
                log.warn("评测用例缺少 name/scenario/input，已跳过：{}", c);
                continue;
            }
            JSONObject expect = c.getJSONObject("expect");
            EvalCase ec = new EvalCase(name.trim(), scenario.trim().toUpperCase(Locale.ROOT), input,
                    c.getStr("pendingQuestion"), expect == null ? new JSONObject() : expect);
            if (ec.matches(scenarioFilter)) out.add(ec);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 跑批
    // ------------------------------------------------------------------

    /**
     * 跑一批：并发评测 → 落库 → 返回明细。
     *
     * @param scenarioFilter 只跑指定场景（null / 空 = 全部）
     */
    public EvalBatchResult run(String scenarioFilter) {
        List<EvalCase> cases = loadCases(scenarioFilter);
        if (cases.isEmpty()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "没有匹配的评测用例（场景=" + scenarioFilter + "）");
        }
        if (cases.size() > MAX_CASES) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "单批用例数 " + cases.size() + " 超过上限 " + MAX_CASES + "，请分开跑（避免一次点亮过多模型调用）");
        }
        String batchId = UUID.randomUUID().toString().replace("-", "");
        long started = System.currentTimeMillis();
        log.info("评测跑批开始：批次={}，用例={}，场景={}", batchId, cases.size(), scenarioFilter);

        // 并发跑 + 单条超时：一条卡住不该拖垮整批。超时只让本批不再等它，被留下的调用仍会跑完（无法中断），
        // 但它占的是评测专用池，不影响对话主链路。
        List<CompletableFuture<EvalCaseResult>> futures = new ArrayList<>(cases.size());
        for (EvalCase c : cases) {
            futures.add(CompletableFuture
                    .supplyAsync(() -> evaluate(c), evalExecutor)
                    .completeOnTimeout(timeoutResult(c), props.timeoutMillis(), TimeUnit.MILLISECONDS)
                    .exceptionally(e -> failureResult(c, "执行异常：" + unwrap(e))));
        }
        List<EvalCaseResult> results = futures.stream().map(CompletableFuture::join).toList();

        int passed = (int) results.stream().filter(EvalCaseResult::passed).count();
        int configErrors = (int) results.stream().filter(EvalCaseResult::configError).count();
        int failed = results.size() - passed - configErrors;
        long costMs = System.currentTimeMillis() - started;
        log.info("评测跑批结束：批次={}，通过 {}/{}，失败 {}，配置错误 {}，耗时 {}ms",
                batchId, passed, results.size(), failed, configErrors, costMs);
        persist(batchId, results);
        return new EvalBatchResult(batchId, results.size(), passed, failed, configErrors, costMs, results);
    }

    /** 按场景分发到具体断言实现；未识别场景记为「用例配置错误」。 */
    private EvalCaseResult evaluate(EvalCase c) {
        long started = System.currentTimeMillis();
        String expected = c.expect().toString();
        try {
            return switch (c.scenario()) {
                case EvalCase.SCENARIO_ROUTE -> evalRoute(c, expected, started);
                case EvalCase.SCENARIO_PLAN -> evalPlan(c, expected, started);
                default -> EvalCaseResult.configError(c.name(), c.scenario(), c.input(), expected,
                        "未知场景「" + c.scenario() + "」（可选：ROUTE / PLAN）", elapsed(started));
            };
        } catch (Exception e) {
            log.warn("评测用例执行异常：{}", c.name(), e);
            return failureResult(c, "执行异常：" + unwrap(e));
        }
    }

    /**
     * ROUTE 场景：调智能路由，断言命中哪个 agent / 是否不路由 / 是否判定为「在回答追问」。
     * 支持同时声明多条断言（全部通过才算通过），失败原因逐条列出——只报「失败」不报「哪条断言差在哪」，
     * 排查时还得自己复现一遍。
     */
    private EvalCaseResult evalRoute(EvalCase c, String expected, long started) {
        JSONObject expect = c.expect();
        String wantCode = expect.getStr("agentCode");
        if (!isBlank(wantCode) && agentService.getByCode(wantCode) == null) {
            return EvalCaseResult.configError(c.name(), c.scenario(), c.input(), expected,
                    "期望的 agentCode「" + wantCode + "」在 agent 表里不存在", elapsed(started));
        }
        AgentRouter.RouteDecision d = agentRouter.route(c.input(), c.pendingQuestion(), null, EVAL_CONVERSATION_ID);

        List<String> fails = new ArrayList<>();
        if (!isBlank(wantCode)) {
            String got = d.agent() == null ? null : d.agent().getAgentCode();
            if (!wantCode.equals(got)) {
                fails.add("期望路由到「" + wantCode + "」，实际「" + (got == null ? "未路由" : got) + "」");
            }
        }
        if (expect.containsKey("noRoute")) {
            boolean want = expect.getBool("noRoute", false);
            boolean got = d.agent() == null && !d.continuation();
            if (want != got) {
                fails.add("期望「" + (want ? "不路由" : "路由") + "」，实际「" + describe(d) + "」");
            }
        }
        if (expect.containsKey("continuation")) {
            boolean want = expect.getBool("continuation", false);
            if (want != d.continuation()) {
                fails.add("期望「" + (want ? "判定为回答追问" : "不判定为回答追问") + "」，实际「" + describe(d) + "」");
            }
        }
        if (fails.isEmpty() && expect.keySet().isEmpty()) {
            return EvalCaseResult.configError(c.name(), c.scenario(), c.input(), expected,
                    "用例没有声明任何断言（expect 为空）", elapsed(started));
        }
        String actual = describe(d);
        return new EvalCaseResult(c.name(), c.scenario(), c.input(), expected, actual,
                fails.isEmpty(), false, String.join("；", fails), actual, elapsed(started));
    }

    /**
     * PLAN 场景：调动态规划，断言计划步数与所包含的智能体。
     * 注意规划结果为空既可能是「模型判定无需编排」，也可能是「JSON 解析失败」——{@link PlannerService}
     * 对后者静默返回空表，评测区分不了；故只断言「空 / 非空」与具体结构，不深究空的原因。
     */
    private EvalCaseResult evalPlan(EvalCase c, String expected, long started) {
        JSONObject expect = c.expect();
        List<String> wantAgents = strList(expect.getJSONArray("containsAgents"));
        for (String code : wantAgents) {
            if (agentService.getByCode(code) == null) {
                return EvalCaseResult.configError(c.name(), c.scenario(), c.input(), expected,
                        "期望包含的智能体「" + code + "」在 agent 表里不存在", elapsed(started));
            }
        }
        String firstAgent = expect.getStr("firstAgent");
        if (!isBlank(firstAgent) && agentService.getByCode(firstAgent) == null) {
            return EvalCaseResult.configError(c.name(), c.scenario(), c.input(), expected,
                    "期望的首步智能体「" + firstAgent + "」在 agent 表里不存在", elapsed(started));
        }

        List<PlanStep> plan = plannerService.plan(c.input());
        List<String> codes = plan.stream().map(PlanStep::agentCode).toList();

        List<String> fails = new ArrayList<>();
        if (expect.containsKey("planEmpty")) {
            boolean want = expect.getBool("planEmpty", false);
            if (want != plan.isEmpty()) {
                fails.add("期望计划「" + (want ? "为空" : "非空") + "」，实际「" + (plan.isEmpty() ? "为空" : "非空") + "」");
            }
        }
        Integer min = expect.getInt("minSteps");
        if (min != null && plan.size() < min) {
            fails.add("期望至少 " + min + " 步，实际 " + plan.size() + " 步");
        }
        Integer max = expect.getInt("maxSteps");
        if (max != null && plan.size() > max) {
            fails.add("期望至多 " + max + " 步，实际 " + plan.size() + " 步");
        }
        for (String code : wantAgents) {
            if (!codes.contains(code)) {
                fails.add("计划中缺少智能体「" + code + "」");
            }
        }
        if (!isBlank(firstAgent) && (codes.isEmpty() || !firstAgent.equals(codes.get(0)))) {
            fails.add("期望首步为「" + firstAgent + "」，实际「" + (codes.isEmpty() ? "无" : codes.get(0)) + "」");
        }
        if (fails.isEmpty() && expect.keySet().isEmpty()) {
            return EvalCaseResult.configError(c.name(), c.scenario(), c.input(), expected,
                    "用例没有声明任何断言（expect 为空）", elapsed(started));
        }
        String actual = plan.isEmpty() ? "无计划（普通回答）" : plan.size() + " 步：" + codes;
        return new EvalCaseResult(c.name(), c.scenario(), c.input(), expected, actual,
                fails.isEmpty(), false, String.join("；", fails), actual, elapsed(started));
    }

    /** 落库（失败只记日志：结果已经算出来了，不该因为存不下就不返回给用户看）。 */
    private void persist(String batchId, List<EvalCaseResult> results) {
        try {
            LocalDateTime now = LocalDateTime.now();
            for (EvalCaseResult r : results) {
                EvalResult row = new EvalResult();
                row.setBatchId(batchId);
                row.setScenario(r.scenario());
                row.setCaseName(truncate(r.caseName(), 128));
                row.setInput(truncate(r.input(), 500));
                row.setExpected(truncate(r.expected(), 500));
                row.setActual(truncate(r.actual(), 500));
                row.setPassed(r.passed());
                row.setConfigError(r.configError());
                row.setFailure(truncate(r.failure(), 1000));
                row.setDetail(r.detail());
                row.setCostMs((int) r.costMs());
                row.setCreatedAt(now);
                evalResultMapper.insert(row);
            }
            evalResultMapper.deleteBatchesBeyond(KEEP_BATCHES);
        } catch (Exception e) {
            log.error("评测结果落库失败（结果已返回，仅未持久化，跨批次对比将缺这一批）：批次={}", batchId, e);
        }
    }

    // ------------------------------------------------------------------
    // 批次查询与对比
    // ------------------------------------------------------------------

    /** 历史批次列表（按批次时间倒序，最多 {@value #KEEP_BATCHES} 条）。 */
    public List<EvalBatchSummary> listBatches() {
        return evalResultMapper.selectBatchSummaries(KEEP_BATCHES);
    }

    /** 某批次的逐条明细（按写入顺序）。 */
    public List<EvalCaseResult> batchDetail(String batchId) {
        List<EvalResult> rows = evalResultMapper.selectList(new LambdaQueryWrapper<EvalResult>()
                .eq(EvalResult::getBatchId, batchId).orderByAsc(EvalResult::getId));
        if (rows.isEmpty()) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "评测批次不存在：" + batchId);
        }
        return rows.stream().map(EvalService::toDto).toList();
    }

    /**
     * 两个批次对比：以用例名为身份对齐，分出「改好了 / 改坏了 / 一直失败 / 增删的用例」。
     * <p>
     * <b>排除配置错误的用例</b>：那是用例自己写错了、不是提示词质量问题，混进来会让「改坏了」这一档失真。
     */
    public EvalCompare compare(String fromBatch, String toBatch) {
        Map<String, Boolean> from = passMap(fromBatch);
        Map<String, Boolean> to = passMap(toBatch);
        List<String> fixed = new ArrayList<>();
        List<String> broken = new ArrayList<>();
        List<String> stillFailed = new ArrayList<>();
        List<String> onlyFrom = new ArrayList<>();
        List<String> onlyTo = new ArrayList<>();

        for (Map.Entry<String, Boolean> e : to.entrySet()) {
            Boolean was = from.get(e.getKey());
            if (was == null) {
                onlyTo.add(e.getKey());
            } else if (!was && e.getValue()) {
                fixed.add(e.getKey());
            } else if (was && !e.getValue()) {
                broken.add(e.getKey());
            } else if (!was) {
                stillFailed.add(e.getKey());
            }
        }
        for (String name : from.keySet()) {
            if (!to.containsKey(name)) onlyFrom.add(name);
        }
        int fromPassed = (int) from.values().stream().filter(Boolean::booleanValue).count();
        int toPassed = (int) to.values().stream().filter(Boolean::booleanValue).count();
        return new EvalCompare(fromBatch, toBatch, fromPassed, from.size(), toPassed, to.size(),
                fixed, broken, stillFailed, onlyFrom, onlyTo);
    }

    /** 取某批次的「用例名 → 是否通过」映射（排除配置错误行，见 {@link #compare}）。 */
    private Map<String, Boolean> passMap(String batchId) {
        List<EvalResult> rows = evalResultMapper.selectList(new LambdaQueryWrapper<EvalResult>()
                .eq(EvalResult::getBatchId, batchId)
                .eq(EvalResult::getConfigError, false));
        if (rows.isEmpty()) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "评测批次不存在（或结果全为用例配置错误）：" + batchId);
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (EvalResult r : rows) {
            out.put(r.getCaseName(), Boolean.TRUE.equals(r.getPassed()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 批次明细行 → DTO。 */
    private static EvalCaseResult toDto(EvalResult r) {
        return new EvalCaseResult(r.getCaseName(), r.getScenario(), r.getInput(), r.getExpected(), r.getActual(),
                Boolean.TRUE.equals(r.getPassed()), Boolean.TRUE.equals(r.getConfigError()),
                r.getFailure() == null ? "" : r.getFailure(),
                r.getDetail() == null ? "" : r.getDetail(),
                r.getCostMs() == null ? 0L : r.getCostMs());
    }

    /** 路由决策的可读描述（进结果 actual 与失败原因）。 */
    private static String describe(AgentRouter.RouteDecision d) {
        if (d.continuation()) return "继续当前追问";
        if (d.agent() == null) return "未路由（普通对话）";
        return "路由到 " + d.agent().getAgentCode() + "（" + d.agent().getName() + "）";
    }

    private EvalCaseResult timeoutResult(EvalCase c) {
        return new EvalCaseResult(c.name(), c.scenario(), c.input(), c.expect().toString(), "-",
                false, false, "超时（>" + props.timeoutSeconds() + "s）", "", props.timeoutMillis());
    }

    private static EvalCaseResult failureResult(EvalCase c, String reason) {
        return new EvalCaseResult(c.name(), c.scenario(), c.input(), c.expect().toString(), "-",
                false, false, reason, "", 0L);
    }

    /** 剥掉 {@link CompletableFuture} 包的一层异常，取到真正的原因。 */
    private static String unwrap(Throwable e) {
        Throwable t = (e instanceof CompletionException && e.getCause() != null) ? e.getCause() : e;
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static List<String> strList(JSONArray arr) {
        if (arr == null || arr.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(arr.size());
        for (Object o : arr) {
            if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o).trim());
        }
        return out;
    }

    private static long elapsed(long started) {
        return System.currentTimeMillis() - started;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 按列宽截断（MySQL varchar 按字符计长，故按字符截）。 */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
