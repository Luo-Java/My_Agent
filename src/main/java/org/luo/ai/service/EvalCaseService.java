package org.luo.ai.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.dto.EvalCase;
import org.luo.ai.entity.AgentTrace;
import org.luo.ai.entity.EvalCaseEntity;
import org.luo.ai.entity.MessageFeedback;
import org.luo.ai.mapper.EvalCaseMapper;
import org.luo.ai.mapper.MessageFeedbackMapper;
import org.luo.ai.trace.TraceService;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 库内回归用例服务：{@code eval_case} 表的存取，以及「把一条用户反馈变成一条回归用例」。
 * <p>
 * 为何需要：{@code eval-cases.yaml} 打包进 jar 后运行时写不了，而「点踩 → 转成回归用例」必须运行时新增。
 * 故 yaml 退化为<b>只读种子</b>，本表承接增量，两者在 {@link EvalService#loadCases} 合并、同名以 yaml 为准。
 * <p>
 * <b>能自动生成什么、不能生成什么（别高估）</b>：自动预填的只有<b>可确定的部分</b> —— 那一轮实际路由到了哪个
 * 智能体 / 计划里有哪几个智能体。这与评测的既有立场一致：判「答案写得好不好」需要 LLM 当裁判，裁判自身不稳定、
 * 回归结果会失去可比性。故反馈选「路由或规划不对」时生成的用例<b>有真实断言价值</b>（把「这条输入以后不该再走
 * 那条路」钉住）；选「答非所问 / 编造」时只能<b>钉住当前的路由与规划、钉不住答案质量</b>，用户写的补充说明只作人工线索。
 */
@Slf4j
@Service
public class EvalCaseService {

    /** {@code agent_trace.mode} 的规划取值（与 {@code ChatService.MODE_PLANNER} 同值；那个是 private）。 */
    private static final String MODE_PLANNER = "planner";

    /** 用例名里保留的用户输入字数（用例名要短且能认出是哪条，完整输入另存在 {@code input} 列）。 */
    private static final int NAME_INPUT_CHARS = 24;

    /** 用例名与输入列的字符上限（与 DDL 列宽一致）。 */
    private static final int NAME_MAX = 128;
    private static final int INPUT_MAX = 500;

    private final EvalCaseMapper caseMapper;
    private final MessageFeedbackMapper feedbackMapper;
    /** 「从消息倒推是哪一轮」的匹配口径唯一在 TraceService（自评也用它），此处只调用不自己写查询。 */
    private final TraceService traceService;

    public EvalCaseService(EvalCaseMapper caseMapper, MessageFeedbackMapper feedbackMapper,
                           TraceService traceService) {
        this.caseMapper = caseMapper;
        this.feedbackMapper = feedbackMapper;
        this.traceService = traceService;
    }

    // ------------------------------------------------------------------
    // 查询 / 删除
    // ------------------------------------------------------------------

    /** 参与跑批的库内用例（{@code enabled=1}，按创建时间正序 —— 与 yaml 的顺序语义一致，便于比对）。 */
    public List<EvalCaseEntity> listEnabled() {
        return caseMapper.selectList(new LambdaQueryWrapper<EvalCaseEntity>()
                .eq(EvalCaseEntity::getEnabled, true)
                .orderByAsc(EvalCaseEntity::getId));
    }

    /** 全部库内用例（含已停用），供评测弹窗展示与删除。 */
    public List<EvalCaseEntity> listAll() {
        return caseMapper.selectList(new LambdaQueryWrapper<EvalCaseEntity>()
                .orderByDesc(EvalCaseEntity::getId));
    }

    /** 删除库内用例；不存在抛 404（不静默成功）。 */
    public void delete(Long id) {
        if (id == null || caseMapper.selectById(id) == null) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "用例不存在：" + id);
        }
        caseMapper.deleteById(id);
        log.info("删除库内回归用例：id={}", id);
    }

    // ------------------------------------------------------------------
    // 从反馈生成用例
    // ------------------------------------------------------------------

    /**
     * 把一条用户反馈转成库内回归用例：从该会话的追踪记录里还原「那一轮实际怎么处理的」，据此预填断言。
     * <p>
     * 预填的来源与口径：按 {@code conversation_id + user_message} 精确匹配最近一条 {@code agent_trace}
     * （{@code user_message} 与反馈的 {@code user_input} 同为「用户原话」快照，能对上）。匹配不到就<b>明确报错</b>
     * 而不是生成一条没有断言的用例 —— 后者每次跑批都会红，用的人很快就学会无视它。
     * <p>
     * 幂等保护：反馈上已有 {@code eval_case_id} 时拒绝重复生成（用例是全局资产，重复转会在批次对比里
     * 变成「一增一删」的噪声）。
     *
     * @return 新生成的用例
     */
    @Transactional
    public EvalCaseEntity createFromFeedback(Long feedbackId) {
        MessageFeedback fb = feedbackId == null ? null : feedbackMapper.selectById(feedbackId);
        if (fb == null) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "反馈不存在：" + feedbackId);
        }
        if (fb.getEvalCaseId() != null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "这条反馈已经生成过用例（用例 ID " + fb.getEvalCaseId() + "），不重复生成");
        }
        String input = fb.getUserInput();
        if (input == null || input.isBlank()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "这条反馈没有留下用户输入快照（该消息是本会话第一条），无法生成用例");
        }
        AgentTrace trace = traceService.findLatestByUserMessage(fb.getConversationId(), input);
        if (trace == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "这一轮没有追踪记录（追踪是旁路数据，可能未落库或被清理），拿不到实际的处理结果，无法预填断言");
        }

        boolean planner = MODE_PLANNER.equals(trace.getMode());
        String scenario = planner ? EvalCase.SCENARIO_PLAN : EvalCase.SCENARIO_ROUTE;
        JSONObject expect = planner ? planExpect(trace) : routeExpect(trace);

        EvalCaseEntity row = new EvalCaseEntity();
        row.setName(caseName(feedbackId, input, scenario));
        row.setScenario(scenario);
        row.setInput(truncate(input, INPUT_MAX));
        row.setPendingQuestion(null);
        row.setExpectJson(truncate(expect.toString(), 500));
        row.setSource(EvalCaseEntity.SOURCE_FEEDBACK);
        row.setFeedbackId(feedbackId);
        row.setEnabled(true);
        row.setCreatedAt(LocalDateTime.now());
        caseMapper.insert(row);

        fb.setEvalCaseId(row.getId());
        fb.setUpdatedAt(LocalDateTime.now());
        feedbackMapper.updateById(fb);
        log.info("反馈转回归用例：反馈={}，用例={}，场景={}，断言={}", feedbackId, row.getId(), scenario, expect);
        return row;
    }

    /** 路由场景的断言：那一轮实际路由到了哪个智能体；未路由则断言「不应路由」。 */
    private static JSONObject routeExpect(AgentTrace trace) {
        JSONObject expect = new JSONObject();
        String code = trace.getAgentCode();
        if (code == null || code.isBlank()) {
            // 未路由（走了通用助手）——钉住「这条输入不该被路由出去」，与 yaml 用例的 noRoute 同口径
            expect.set("noRoute", true);
        } else {
            expect.set("agentCode", code);
        }
        return expect;
    }

    /**
     * 规划场景的断言：那一轮计划里包含哪几个智能体。
     * <p>
     * 只断言「包含」不断言步数与顺序：步数/顺序受模型波动影响大，钉死会持续假失败（yaml 用例的注释里
     * 已经把这条取舍写明了）。
     */
    private static JSONObject planExpect(AgentTrace trace) {
        JSONObject expect = new JSONObject();
        String planJson = trace.getPlanJson();
        Set<String> codes = new LinkedHashSet<>();
        if (planJson != null && !planJson.isBlank()) {
            try {
                JSONArray arr = JSONUtil.parseArray(planJson);
                for (Object o : arr) {
                    if (o instanceof JSONObject step) {
                        String code = step.getStr("agentCode");
                        if (code != null && !code.isBlank()) codes.add(code);
                    }
                }
            } catch (Exception e) {
                // 计划 JSON 解析不了：退回「计划为空」断言，并把原因记进日志（不静默当成非空）
                log.warn("反馈转用例：追踪里的计划不是合法 JSON（trace={}）：{}", trace.getTraceId(), e.getMessage());
            }
        }
        if (codes.isEmpty()) {
            expect.set("planEmpty", true);
        } else {
            expect.set("containsAgents", new ArrayList<>(codes));
        }
        return expect;
    }

    /** 用例名：{@code 反馈#<id> <前 N 字输入>}。带反馈 ID 才保证唯一（同名会顶掉 yaml 用例，见类注释）。 */
    private static String caseName(Long feedbackId, String input, String scenario) {
        String head = input.replaceAll("\\s+", " ").strip();
        if (head.length() > NAME_INPUT_CHARS) head = head.substring(0, NAME_INPUT_CHARS) + "…";
        return truncate("反馈#" + feedbackId + " " + head, NAME_MAX);
    }

    /** 按列宽截断（MySQL varchar 按字符计长）。 */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
