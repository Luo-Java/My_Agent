package org.luo.ai.agent.handler;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.dto.KbCitation;
import org.luo.ai.entity.Agent;
import org.luo.ai.entity.Conversation;
import org.luo.ai.entity.Task;
import org.luo.ai.entity.TaskStep;
import org.luo.ai.service.AgentService;
import org.luo.ai.service.TaskService;
import org.luo.ai.chat.ChatComposer;
import org.luo.ai.agent.PlannerService;
import org.luo.ai.agent.PlannerService.PlanStep;
import org.luo.ai.properties.PlannerProperties;
import org.luo.ai.trace.RoundTrace;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.luo.ai.service.KbSearchService;

/**
 * 规划模式会话策略：动态规划器（PlannerService）在运行时根据用户目标产出多智能体步骤，再顺序执行
 * （前一步输出作为后一步输入）；不预配置步骤、不追问参数。
 * <p>
 * 与普通对话策略的关键差异在<b>记忆写入契约</b>：本策略全程用无记忆的
 * {@link ChatComposer#internalChatClient()}（避免「指令+上一步输出」这类合成串污染历史），
 * 回复产出后由 {@link #savePlannerExchange} 显式补写「用户原话 → 最终回复」整对。
 * 执行过程经 {@code progress} 回调实时播报，<b>只用于展示、不写入记忆</b>。
 * <p>
 * <b>RAG 引用只取最后一步</b>：每步各自检索、各自从 [1] 编号，跨步合并会出现重复序号、与最终回答角标对不上。
 * <p>
 * <b>跨轮任务状态持久化</b>：一轮规划落库为一条 {@code task} + 若干 {@code task_step}，每步执行完即增量提交
 * 状态与产出（{@link TaskService}），服务重启 / 中断后可由 {@link #resumeTask} 显式续跑剩余步骤（不重新规划）。
 * 单会话单 RUNNING 任务，开新规划前自动结旧（见 {@link TaskService#cancelRunning}）。
 * <p>
 * <b>前驱产出有配额上限</b>：步骤间产物传递经 {@link #truncateUpstream} 按 {@link PlannerProperties} 的配额截断，
 * 超额保留前段 + 显式省略标注 + WARN。配额只作用于「注入下一步的输入」，不影响 {@code task_step.output} 落库
 * 与最终回复——用户看到的始终是完整产出。
 */
@Slf4j
@Service
public class PlannerRoundHandler implements RoundHandler {

    /** 追踪用：处理方来源枚举值（与 agent_trace.route_source 列注释一致）。 */
    private static final String SRC_PLAN = "PLAN";
    private static final String SRC_NONE = "NONE";

    private final PlannerService plannerService;
    private final AgentService agentService;
    private final ChatComposer composer;
    private final ChatMemory chatMemory;
    private final TaskService taskService;
    /** 规划步骤并行执行线程池（无依赖步骤同层并发调 LLM）。 */
    private final Executor stepExecutor;
    /** 步骤间产物传递配额（见 {@link PlannerProperties}）：防前驱产出无界累积击穿模型上下文。 */
    private final PlannerProperties plannerProperties;

    public PlannerRoundHandler(PlannerService plannerService,
                               AgentService agentService,
                               ChatComposer composer,
                               ChatMemory chatMemory,
                               TaskService taskService,
                               PlannerProperties plannerProperties,
                               @Qualifier("plannerStepExecutor") Executor stepExecutor) {
        this.plannerService = plannerService;
        this.agentService = agentService;
        this.composer = composer;
        this.chatMemory = chatMemory;
        this.taskService = taskService;
        this.plannerProperties = plannerProperties;
        this.stepExecutor = stepExecutor;
    }

    @Override
    public RoundResult handle(Conversation conv, String conversationId, String message, String material,
                             Consumer<String> progress, RoundTrace trace) {
        // message 为纯提问（不含附件）；附件材料 material 仅当轮注入（首步 / 兜底），不进会话记忆。
        PlannerOutcome po = handlePlannerConversation(conv, conversationId, message, material, progress, trace);
        // 防御：规划未产出任何结果（理论不会发生）→ 显式回退信号，调用方转普通对话策略
        if (po.reply() == null) return RoundResult.fallback();
        // 多步执行期间不写记忆，需在回复产出后显式补写「用户原话 → 最终回复」整对
        if (po.needSaveExchange()) savePlannerExchange(conversationId, message, po.reply());
        return RoundResult.answer(po.reply(), po.citations());
    }

    /**
     * 规划模式会话处理：规划为空或计划中的智能体全不存在 → 回退通用助手直接回答（走带记忆的 chatClient，
     * 记忆由 Advisor 自动落库）；否则顺序执行 spec，记忆由 {@link #savePlannerExchange} 统一补写。
     * <p>
     * 本方法<b>不写记忆</b>：是否需显式落库由返回值的 {@code needSaveExchange} 告知调用方。
     * <p>
     * 有可执行计划时，先把计划落库为 task + task_step（开新任务前自动结旧），再执行并逐步提交状态。
     *
     * @param progress 进度回调（流式接口传事件推送，同步接口传空回调）
     * @param trace    本轮追踪上下文（可为 null）：记录计划与最终处理方
     */
    private PlannerOutcome handlePlannerConversation(Conversation conv, String conversationId, String message,
                                                     String material, Consumer<String> progress, RoundTrace trace) {
        progress.accept("🧭 正在分析目标并规划执行步骤…");
        List<PlanStep> plan = plannerService.plan(message);
        log.info("动态规划：会话={}, 步骤={}", conversationId, plan);
        if (plan == null || plan.isEmpty()) {
            log.info("动态规划：无需编排（无可用智能体或目标无关），普通回答");
            progress.accept("💬 无需多智能体协同，由通用助手直接回答");
            if (trace != null) {
                trace.plan("[]");
                trace.route(SRC_NONE, null);   // 实际由通用助手处理
            }
            return answerDefault(conv, conversationId, message, material, trace);
        }
        List<StepSpec> specs = new ArrayList<>();
        // 原始 plan 下标 → specs 连续下标 的映射（跳过不存在的 agent 会让 specs 变短，必须重映射，
        // 否则 dependsOn 引用的原始下标会错位）。被跳过的下标映射为 -1，执行时按「无有效依赖」过滤。
        int[] remap = new int[plan.size()];
        java.util.Arrays.fill(remap, -1);
        for (int i = 0; i < plan.size(); i++) {
            PlanStep s = plan.get(i);
            Agent a = agentService.getByCode(s.agentCode());
            if (a == null) {
                log.warn("动态规划：智能体编码 {} 不存在，跳过该步骤", s.agentCode());
                progress.accept("⚠️ 跳过：智能体「" + s.agentCode() + "」不存在");
                continue;
            }
            remap[i] = specs.size();
            specs.add(new StepSpec(null, a, s.instruction(), remapDeps(s.dependsOn(), remap)));
        }
        if (specs.isEmpty()) {
            log.warn("动态规划：计划中的智能体均不存在，回退普通回答");
            progress.accept("💬 计划中的智能体都不可用，改由通用助手回答");
            if (trace != null) {
                trace.plan(planJson(specs));
                trace.route(SRC_NONE, null);
            }
            return answerDefault(conv, conversationId, message, material, trace);
        }
        log.info("动态规划启动：会话={}，步骤数={}", conversationId, specs.size());
        // 落库：单会话单 RUNNING，开新规划前结旧；随后建 task + steps，拿回每步 DB id 用于逐步提交状态
        Task task = persistPlan(conversationId, message, specs);
        if (trace != null) {
            trace.plan(planJson(specs));
            trace.route(SRC_PLAN, specs.get(specs.size() - 1).agent().getAgentCode());
        }
        // 播报计划清单：让用户先看到「准备怎么做」，再看到逐步执行情况
        StringBuilder planText = new StringBuilder("📋 规划完成，共 " + specs.size() + " 步：");
        for (int i = 0; i < specs.size(); i++) {
            StepSpec s = specs.get(i);
            planText.append("\n").append(i + 1).append(". ").append(s.agent().getName());
            if (s.instruction() != null && !s.instruction().isBlank()) {
                planText.append(" —— ").append(s.instruction());
            }
        }
        progress.accept(planText.toString());
        StepsOutcome executed = executeSteps(specs, task.getId(), message, conversationId, conv, null, material,
                progress, trace);
        String reply = executed.reply();
        if (reply == null || reply.isBlank()) {
            log.warn("动态规划未产出结果，回退普通回答");
            progress.accept("⚠️ 各步骤均未产出结果，改由通用助手回答");
            if (trace != null) trace.route(SRC_NONE, null);
            taskService.finish(task.getId(), Task.STATUS_FAILED, null);
            return answerDefault(conv, conversationId, message, material, trace);
        }
        progress.accept("✅ 全部步骤执行完毕，已生成最终结果");
        taskService.finish(task.getId(), Task.STATUS_DONE, reply);
        // 多步执行期全程不写记忆，需由调用方在回复推送后显式补写「用户原话 → 最终回复」整对
        return new PlannerOutcome(reply, true, executed.citations());
    }

    /**
     * 显式续跑：按会话定位唯一 RUNNING 任务，回填已完成步骤产出、只执行剩余步骤（不重新规划）。
     * <p>
     * 由 {@code ChatService.resume} 调用（用户点「继续执行」按钮）。续跑成功后补写「任务目标 → 最终回复」
     * 进会话记忆（与 {@link #savePlannerExchange} 一致）。
     *
     * @return 续跑结果；无 RUNNING 任务或全部步骤失败时返回 {@link RoundResult#fallback()}（reply 为 null）
     */
    public RoundResult resumeTask(Conversation conv, String conversationId, Consumer<String> progress,
                                  RoundTrace trace) {
        Task task = taskService.findRunning(conversationId);
        if (task == null) {
            return RoundResult.fallback();
        }
        List<TaskStep> rows = taskService.listSteps(task.getId());
        log.info("续跑任务：会话={}，任务={}，步骤={}", conversationId, task.getId(), rows.size());
        progress.accept("⏸ 检测到未完成任务，继续执行剩余步骤（已完成 " + task.getDoneSteps()
                + "/" + task.getTotalSteps() + "）…");

        // 从持久化的步骤重建 specs（agent 可能已被删除，删除者标 SKIPPED 不执行、产出记 null）
        int n = rows.size();
        List<StepSpec> specs = new ArrayList<>(n);
        int[] remap = new int[n];   // task_step.step_index(=首次 specs 下标) → 续跑 specs 下标
        java.util.Arrays.fill(remap, -1);
        List<Long> skippedIds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            TaskStep row = rows.get(i);
            Agent a = agentService.getByCode(row.getAgentCode());
            if (a == null) {
                log.warn("续跑：智能体编码 {} 已不存在，步骤 {} 跳过", row.getAgentCode(), row.getStepIndex());
                progress.accept("⚠️ 跳过：智能体「" + row.getAgentCode() + "」已不存在");
                skippedIds.add(row.getId());
                continue;
            }
            remap[i] = specs.size();
            specs.add(new StepSpec(row.getId(), a, row.getInstruction(), depsFromJson(row.getDependsOn())));
        }
        // 重映射依赖：续跑 specs 变短时，dependsOn 引用的是首次连续下标，需按 remap 压缩；被跳过前驱过滤掉
        for (int i = 0; i < specs.size(); i++) {
            StepSpec s = specs.get(i);
            specs.set(i, new StepSpec(s.stepId(), s.agent(), s.instruction(), remapDeps(s.dependsOn(), remap)));
        }
        if (specs.isEmpty()) {
            log.warn("续跑：计划中的智能体均不存在，任务失败");
            taskService.finish(task.getId(), Task.STATUS_FAILED, null);
            return RoundResult.fallback();
        }

        // 回填已完成步骤的产出与状态；SKIPPED（agent 已删）标终态、产出 null
        String[] preOutputs = new String[specs.size()];
        boolean[] preDone = new boolean[specs.size()];
        @SuppressWarnings("unchecked")
        List<KbCitation>[] preCitations = new List[specs.size()];
        for (int i = 0; i < specs.size(); i++) {
            StepSpec s = specs.get(i);
            TaskStep row = rows.stream().filter(r -> r.getId().equals(s.stepId())).findFirst().orElse(null);
            if (row == null) continue;
            String st = row.getStatus();
            if (TaskStep.STATUS_DONE.equals(st)) {
                preOutputs[i] = row.getOutput();
                preCitations[i] = KbCitation.parse(row.getCitationsJson());
                preDone[i] = true;
            } else if (TaskStep.STATUS_FAILED.equals(st)
                    && row.getRetryCount() != null && row.getRetryCount() >= TaskStep.MAX_RETRY) {
                // 确定性失败：不再重试，视为「前驱失败」，依赖步回落原始目标
                preDone[i] = true;
            }
            // PENDING / 可重试的 FAILED(retry_count<2) → preDone 保持 false，续跑时重新执行
        }
        // 被删除 agent 的 SKIPPED 步骤：标终态落库（一步到位，避免逐条标记）
        for (Long id : skippedIds) {
            taskService.markStepSkipped(id);
        }

        StepsOutcome executed = executeStepsWithState(specs, task.getId(), task.getUserGoal(), conversationId, conv,
                null, null, progress, trace, preOutputs, preDone, preCitations);
        String reply = executed.reply();
        if (reply == null || reply.isBlank()) {
            log.warn("续跑：剩余步骤均未产出结果，任务失败");
            taskService.finish(task.getId(), Task.STATUS_FAILED, null);
            return RoundResult.fallback();
        }
        progress.accept("✅ 全部步骤执行完毕，已生成最终结果");
        taskService.finish(task.getId(), Task.STATUS_DONE, reply);
        // 续跑期间各步骤走无记忆 ChatClient，此处补写「任务目标 → 最终回复」整对进会话记忆
        savePlannerExchange(conversationId, task.getUserGoal(), reply);
        return RoundResult.answer(reply, executed.citations());
    }

    /** 把计划落库为 task + task_step（开新规划前先结旧），返回 task 且回填每步 DB id 到 specs。 */
    private Task persistPlan(String conversationId, String userGoal, List<StepSpec> specs) {
        taskService.cancelRunning(conversationId);
        Task task = taskService.create(conversationId, userGoal, specs.size());
        for (int i = 0; i < specs.size(); i++) {
            StepSpec s = specs.get(i);
            // createStep 落库后自增 id 已回填到返回的实体，直接回填进 specs 供逐步提交状态用
            TaskStep row = taskService.createStep(task.getId(), i, s.agent().getAgentCode(), s.instruction(),
                    depsToJson(s.dependsOn()));
            specs.set(i, new StepSpec(row.getId(), s.agent(), s.instruction(), s.dependsOn()));
        }
        return task;
    }

    /** 依赖下标 → JSON 数组字符串（落库 task_step.depends_on）。 */
    private static String depsToJson(List<Integer> deps) {
        if (deps == null || deps.isEmpty()) return "[]";
        JSONArray arr = new JSONArray(deps.size());
        for (int d : deps) arr.add(d);
        return arr.toString();
    }

    /** JSON 数组字符串 → 依赖下标列表（读回 task_step.depends_on）；空/异常返回空表。 */
    private static List<Integer> depsFromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JSONArray arr = JSONUtil.parseArray(json);
            List<Integer> out = new ArrayList<>(arr.size());
            for (Object o : arr) {
                if (o instanceof Number num) out.add(num.intValue());
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 按依赖拓扑分层<b>并行</b>执行一组步骤（智能体 + 指令）：无依赖的步骤同层并发跑，依赖步骤等其
     * 前驱完成后以其输出为输入。与顺序版同一套记忆契约：中间产物不写历史、只注入最后一步近期窗口、
     * RAG 引用取最终步骤、progress 只展示不进记忆。首次执行入口（无已回填状态），逐步落库状态。
     */
    private StepsOutcome executeSteps(List<StepSpec> steps, String taskId, String firstInput, String conversationId,
                                      Conversation conv, String paramBlock, String material,
                                      Consumer<String> progress, RoundTrace trace) {
        return executeStepsWithState(steps, taskId, firstInput, conversationId, conv, paramBlock, material,
                progress, trace, null, null, null);
    }

    /**
     * 分层并行执行的统一实现：{@code preOutputs/preDone/preCitations} 为已回填状态（续跑时传入，首次执行传
     * null），其余步骤（未 done）按拓扑分层并行执行并逐步落库。
     * <ul>
     *   <li>分层推进：每轮找出「依赖均已满足」的未执行步骤，用 {@code CompletableFuture} 并行执行；</li>
     *   <li>输入构造：第 1 层（无依赖）输入 = 用户原始目标 + 附件（若有）；有依赖的步骤输入 = 其指令 +
     *       {@code dependsOn} 指向的前驱输出（多个前驱按序拼接）；</li>
     *   <li>所有步骤均用无记忆 ChatClient（中间产物不写历史）；最后一个完成层注入近期窗口历史；</li>
     *   <li>某步骤失败/返回空则其输出记为 null，依赖它的步骤回落原始目标作答；全部失败返回 null；</li>
     *   <li>防御：依赖下标非法（越界 / 指向自身或后序）按「无依赖」处理；若某层无步骤可推进（依赖环）则断环跳出。</li>
     * </ul>
     */
    private StepsOutcome executeStepsWithState(List<StepSpec> steps, String taskId, String firstInput,
                                               String conversationId, Conversation conv, String paramBlock,
                                               String material, Consumer<String> progress, RoundTrace trace,
                                               String[] preOutputs, boolean[] preDone,
                                               List<KbCitation>[] preCitations) {
        if (steps == null || steps.isEmpty()) return new StepsOutcome(null, List.of());
        // 近期窗口历史（替代记忆 Advisor 的读取）：仅注入到最后一层，避免合成输入被误写入记忆。
        String historyContext = composer.buildHistoryContextText(conversationId, 0,
                "\n\n[近期对话] 以下为本轮之前同一会话的近期上下文（仅供参考，请勿复述）：\n");
        int n = steps.size();
        String[] outputs = preOutputs != null ? preOutputs : new String[n];
        boolean[] done = preDone != null ? preDone.clone() : new boolean[n];
        @SuppressWarnings("unchecked")
        List<KbCitation>[] citations = preCitations != null ? preCitations : new List[n];
        String last = null;                      // 最后成功步骤的输出（即最终结果）
        List<KbCitation> lastCitations = List.of();
        int executed = 0;                        // 已执行/已完成的步骤数（防御依赖环导致死循环）
        for (boolean d : done) if (d) executed++;
        while (executed < n) {
            // 找出本轮可执行的步骤：依赖均已满足（前驱已 done 且产出可用）
            List<Integer> ready = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (done[i]) continue;
                StepSpec s = steps.get(i);
                List<Integer> deps = validDeps(s, n);   // 已归一化到 [0, n) 的合法前驱下标
                if (deps.stream().allMatch(d -> done[d])) {
                    ready.add(i);
                }
            }
            if (ready.isEmpty()) {
                // 依赖环或前驱永远无法满足：断环，沿用已产出结果（若有），避免死循环
                log.warn("动态规划：剩余 {} 步依赖无法满足（疑似依赖环），终止执行", n - executed);
                break;
            }
            // 同层并行：每步一个 future，全部 join 后再进下一层。
            // 历史上下文只注入最后一步（idx == n-1，即汇总步），与顺序版「最后一步注入近期窗口」语义一致。
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int idx : ready) {
                StepSpec s = steps.get(idx);
                String stepTag = "步骤 " + (idx + 1) + "/" + n + " · " + s.agent().getName();
                progress.accept("▶ " + stepTag + " 执行中…");
                boolean isLastStep = (idx == n - 1);
                try {
                    futures.add(CompletableFuture.runAsync(
                            () -> runStep(idx, s, outputs, citations, firstInput, material, paramBlock,
                                    historyContext, isLastStep, conversationId, conv, trace, progress, taskId),
                            stepExecutor));
                } catch (RuntimeException e) {
                    // 线程池队列满拒绝：降级为当前线程同步执行该步，不阻断整条流水线
                    log.warn("动态规划：并行线程池拒绝提交，步骤 {}/{} 降级同步执行：{}", idx + 1, n, e.getMessage());
                    runStep(idx, s, outputs, citations, firstInput, material, paramBlock,
                            historyContext, isLastStep, conversationId, conv, trace, progress, taskId);
                    futures.add(CompletableFuture.completedFuture(null));
                }
            }
            // 等本层全部完成（单步异常已在 runStep 内兜住，不会向外抛）
            for (CompletableFuture<Void> f : futures) {
                try {
                    f.join();
                } catch (Exception e) {
                    // runStep 已内部兜底，这里兜住 CompletionException 以防万一
                    log.error("动态规划：等待并行步骤完成时异常：{}", e.getMessage(), e);
                }
            }
            for (int idx : ready) {
                done[idx] = true;
                executed++;
            }
        }
        // 最终结果：优先取汇总步（idx = n-1）；汇总步为空（失败/未产出）则回退到最后一个成功的非汇总步。
        // 引用取「产出最终结果那一步」的 RAG（编号与正文角标一致，跨步不合并）。
        for (int i = n - 1; i >= 0; i--) {
            if (outputs[i] != null) {
                last = outputs[i];
                lastCitations = citations[i] == null ? List.of() : citations[i];
                break;
            }
        }
        // 同步任务完成步数（done_steps）：供续跑提示条展示「已完成 N/M 步」；异常中断时也把已落库终态步数写准
        if (taskId != null) taskService.refreshDoneCount(taskId);
        return new StepsOutcome(last, lastCitations);
    }

    /** 归一化依赖下标：只保留 [0, n) 内的合法前驱下标（dependsOn 在构造 StepSpec 时已重映射为连续下标并过滤掉被跳过的前驱）。 */
    private static List<Integer> validDeps(StepSpec s, int n) {
        List<Integer> deps = s.dependsOn();
        if (deps == null || deps.isEmpty()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (int d : deps) {
            if (d >= 0 && d < n) {
                out.add(d);
            }
        }
        return out;
    }

    /** 执行单个步骤：组装输入与 system，调无记忆 ChatClient，产出写回 {@code outputs[idx]} 并落库。异常/空产出内部兜底，不外抛。 */
    private void runStep(int idx, StepSpec s, String[] outputs, List<KbCitation>[] citations,
                         String firstInput, String material, String paramBlock, String historyContext,
                         boolean isLastStep, String conversationId, Conversation conv, RoundTrace trace,
                         Consumer<String> progress, String taskId) {
        int n = outputs.length;
        String stepTag = "步骤 " + (idx + 1) + "/" + n + " · " + s.agent().getName();
        // 输入：本步指令 + 依赖前驱的输出（多个按序拼接）；无前驱则用原始目标
        String userInput = buildStepInput(s, outputs, firstInput, material, n);
        if (s.instruction() != null && !s.instruction().isBlank()) {
            userInput = s.instruction() + "\n\n" + userInput;
        }
        if (taskId != null && s.stepId() != null) taskService.markStepRunning(s.stepId());
        try {
            // 上下文分三档注入：首层吃「已确认参数」，汇总步（最后一步）追加「近期窗口历史」，中间步骤默认
            // 「隔离长期记忆」。中间步的活是「拿前驱产物做自己那一段」，会话级 core_facts / summary 对它
            // 多半是噪音——摘要里可能是别的话题的内容，反而把这一步带偏（开关见 agent.planner.isolate-middle-steps）。
            // RAG 资料不参与隔离：每步 query 不同、按各自需要检索，与「会话记忆」不是一回事。
            boolean isolateMemory = !isLastStep && idx != 0 && plannerProperties.isolateMiddleStepsOn();
            KbSearchService.KbContext kb = composer.buildKbContext(composer.ragOn(conv), s.agent(), userInput);
            String system = composer.applyRealtimeRule(composer.buildSystemPrompt(s.agent())
                    + kb.text()
                    + (isolateMemory ? "" : composer.buildLongTermMemoryText(conv))
                    + (idx == 0 && paramBlock != null ? paramBlock : "")
                    + (isLastStep && !historyContext.isBlank() ? historyContext : ""));
            ChatClient.ChatClientRequestSpec spec = composer.internalChatClient()
                    .prompt().system(system).user(userInput);
            spec = composer.decorateRequest(spec, s.agent(), trace);
            String out = spec.call().content();
            if (out != null && !out.isBlank()) {
                outputs[idx] = out;
                citations[idx] = kb.citations();
                if (taskId != null && s.stepId() != null) {
                    taskService.markStepDone(s.stepId(), out, KbCitation.toJson(kb.citations()));
                }
                log.info("并行执行：步骤 {}/{}（{}）完成，输出长度={}", idx + 1, n, s.agent().getAgentCode(), out.length());
                progress.accept("✔ " + stepTag + " 完成（产出 " + out.length() + " 字）");
            } else {
                if (taskId != null && s.stepId() != null) taskService.markStepFailed(s.stepId(), "返回空");
                log.warn("并行执行：步骤 {}/{}（{}）返回空", idx + 1, n, s.agent().getAgentCode());
                progress.accept("⚠️ " + stepTag + " 未产出内容");
            }
        } catch (Exception e) {
            if (taskId != null && s.stepId() != null) {
                taskService.markStepFailed(s.stepId(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            log.error("并行执行：步骤 {}/{}（{}）执行失败：{}", idx + 1, n, s.agent().getAgentCode(), e.getMessage(), e);
            progress.accept("✖ " + stepTag + " 执行失败：" + e.getMessage());
        }
    }

    /**
     * 构造单步输入：无依赖 → 原始目标（+附件）；有依赖 → 各前驱产出按序拼接，前驱为空则回落原始目标说明。
     * <p>
     * <b>前驱产出按配额截断</b>（见 {@link PlannerProperties#perUpstreamChars}）：每个前驱最多注入配额内的字符，
     * 超额部分省略并追加显式标注 + WARN。改造前这里是全量拼接、无任何上限，多步长产出会随「步骤数 × 单步长度」
     * 无界累积、最终击穿模型上下文（静默失败：模型丢内容但不报错）。
     * <p>
     * 每段前驱产出都带来源标注：多前驱拼接后，模型要能分清「哪段来自哪一步」，省略标注才不会被误读成
     * 上一步的内容。
     */
    private String buildStepInput(StepSpec s, String[] outputs, String firstInput, String material, int n) {
        List<Integer> deps = validDeps(s, n);
        if (deps.isEmpty()) {
            String base = firstInput;
            if (material != null && !material.isBlank()) {
                base = base + "\n\n[本轮附件材料] 以下为用户本轮上传的内容（图片已识别、文档已解析为文本），仅作本次参考：\n" + material;
            }
            return base;
        }
        int quota = plannerProperties.perUpstreamChars(deps.size());
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (int d : deps) {
            String depOut = outputs[d];
            if (depOut == null || depOut.isBlank()) continue;
            sb.append("[步骤 ").append(d + 1).append(" 的产出]\n")
                    .append(truncateUpstream(depOut, quota))
                    .append("\n\n");
            any = true;
        }
        if (!any) {
            // 前驱全部失败/空：不能把空串当产物，回落原始目标并显式说明
            sb.append("（前置步骤未产出可用结果，请基于原始目标作答）\n").append(firstInput);
        }
        return sb.toString().strip();
    }

    /**
     * 按配额截断单段上游产出：未超配额原样返回；超出则保留前段 + 追加省略标注并落 WARN。
     * <p>
     * 保留<b>前段</b>而非尾段：LLM 产出通常是「结论在前、展开在后」，前段信息密度更高。
     * 截断一律留下标注与日志——静默截断会让「模型答偏」变成查不出原因的谜。
     */
    private String truncateUpstream(String text, int quota) {
        if (text.length() <= quota) return text;
        int omitted = text.length() - quota;
        log.warn("规划步骤产出超配额已截断：原长 {} 字符 → {} 字符（省略 {} 字符，配额见 agent.planner.*）",
                text.length(), quota, omitted);
        return text.substring(0, quota)
                + "\n…（该步骤产出过长已截断，后续省略 " + omitted + " 字符；不要基于被省略的内容作答）";
    }

    /** 通用助手兜底回答（无智能体绑定、不挂载工具）：用于规划回退或目标无关时。附件材料仅当轮注入 system；RAG 引用随结果带回。 */
    private PlannerOutcome answerDefault(Conversation conv, String conversationId, String message,
                                         String material, RoundTrace trace) {
        ChatComposer.ComposedRequest composed =
                composer.buildDefaultRequest(conv, conversationId, message, material, trace);
        String reply = composed.spec().call().content();
        return new PlannerOutcome(reply != null ? reply : "", false, composed.citations());
    }

    /** 计划步骤 → JSON（写进 agent_trace.plan_json，供事后回看 LLM 当时的编排决策与依赖标注）。 */
    private static String planJson(List<StepSpec> specs) {
        JSONArray arr = new JSONArray();
        for (StepSpec s : specs) {
            JSONObject o = new JSONObject();
            o.set("agentCode", s.agent().getAgentCode());
            o.set("agentName", s.agent().getName());
            o.set("instruction", s.instruction());
            o.set("dependsOn", s.dependsOn() == null ? new JSONArray() : s.dependsOn());
            arr.add(o);
        }
        return arr.toString();
    }

    /** 把规划模式一轮（用户原话 + 最终回复）写入会话记忆：执行期间各步骤走无记忆 ChatClient，故在此统一补写。 */
    private void savePlannerExchange(String conversationId, String userMessage, String assistantReply) {
        try {
            chatMemory.add(conversationId, List.of(
                    new UserMessage(userMessage),
                    new AssistantMessage(assistantReply)));
            log.debug("规划记忆写入：会话={}", conversationId);
        } catch (Exception e) {
            log.error("规划记忆写入失败：会话={}", conversationId, e);
        }
    }

    /**
     * 规划模式一轮的产出。
     *
     * @param needSaveExchange 是否需显式写「用户原话 → 最终回复」：多步执行为 true（执行期全程不写记忆）；
     *                         回退通用助手为 false（Advisor 已自动落库，重复写会出现两遍）
     * @param citations        最终回复引用的 RAG 来源（取产出该回复那一步；无则为空表）
     */
    private record PlannerOutcome(String reply, boolean needSaveExchange, List<KbCitation> citations) {
    }

    /** 顺序执行结果：最终回复 + 产出该回复那一步的 RAG 引用。 */
    private record StepsOutcome(String reply, List<KbCitation> citations) {
    }

    /**
     * 执行的一个步骤：执行哪个智能体 + 给它的补充指令 + 依赖的前序步骤（已重映射为 specs 连续下标）。
     *
     * @param stepId      该步骤落库后的 DB id（{@code task_step.id}）；null 表示未落库（仅内存执行）
     */
    private record StepSpec(Long stepId, Agent agent, String instruction, List<Integer> dependsOn) {
    }

    /** 把 PlanStep 的原始下标依赖重映射为 specs 连续下标；被跳过的前驱映射为 -1（执行时过滤）。 */
    private static List<Integer> remapDeps(List<Integer> rawDeps, int[] remap) {
        if (rawDeps == null || rawDeps.isEmpty()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (int d : rawDeps) {
            if (d >= 0 && d < remap.length && remap[d] >= 0) {
                out.add(remap[d]);
            }
        }
        return out;
    }
}
