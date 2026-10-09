package org.luo.ai.agent.handler;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.dto.KbCitation;
import org.luo.ai.entity.Agent;
import org.luo.ai.entity.Conversation;
import org.luo.ai.entity.Task;
import org.luo.ai.entity.TaskStep;
import org.luo.ai.entity.TaskTemplate;
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
 * 规划模式会话策略：动态规划器（PlannerService）运行时按用户目标产出多智能体步骤，再顺序执行
 * （前一步输出作后一步输入）；不预配置步骤、不追问参数。
 * <p>
 * 红线：全程用无记忆的 {@link ChatComposer#internalChatClient()}（避免「指令+上一步输出」这类合成串污染历史），
 * 回复后由 {@link #savePlannerExchange} 显式补写「用户原话 → 最终回复」整对；{@code progress} 只展示、不写记忆。
 * RAG 引用<b>只取最后一步</b>（每步各自从 [1] 编号，跨步合并会出现重复序号、与最终回答角标对不上）。
 * 一轮规划落库为一条 {@code task} + 若干 {@code task_step}，每步完即增量提交，重启后可经 {@link #resumeTask} 续跑
 * （<b>不重新规划</b>）；单会话单 RUNNING，开新规划前自动结旧（{@link TaskService#cancelRunning}）。
 * 审批关卡：{@code approvalRequired=1} 且未批准时整条流水线暂停（本层其余也不跑，剩余 PENDING、task 仍 RUNNING），
 * 批准后走的还是 {@link #resumeTask} —— 与「先看计划」「局部重规划」「套用模板」共用同一条通路，<b>无第二套执行逻辑</b>。
 */
@Slf4j
@Service
public class PlannerRoundHandler implements RoundHandler {

    /** 追踪用：处理方来源枚举值（与 agent_trace.route_source 列注释一致）。 */
    private static final String SRC_PLAN = "PLAN";
    private static final String SRC_NONE = "NONE";

    /**
     * 暂停原因 · 用户中途喊停（{@code task.pause_requested}）。
     * <p>
     * 只有这一个值：{@code pausedAt >= 0} 时若原因不是它，就必然是「审批闸门拦下」（另一处暂停点）。
     * 刻意不给审批也造一个常量 —— 用不上的对称常量只会让人以为还有第三种暂停原因。
     */
    private static final String PAUSE_USER = "USER";

    /**
     * 局部重规划时，喂给模型的「上游已完成步骤产出」摘要长度上限（每步）。
     * <p>
     * 重规划只需要知道上游「做到了什么」才能决定下一步，不需要原文；不设限会让「已完成步骤数 × 产出长度」
     * 无界拼进一次规划请求。
     */
    private static final int REPLAN_SUMMARY_CHARS = 500;

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
        // 「先看计划」暂停：把待确认计划一并交给调用方（流式接口推 plan 事件供前端渲染卡片）
        if (po.planJson() != null) return RoundResult.planned(po.reply(), po.planJson());
        // 执行到审批关卡暂停：把待批步骤交给调用方（流式接口推 approval 事件供前端渲染审批卡片）
        if (po.approvalJson() != null) return RoundResult.approval(po.reply(), po.approvalJson());
        return RoundResult.answer(po.reply(), po.citations());
    }

    /**
     * 规划模式会话处理：规划为空或计划中的智能体全不存在 → 回退通用助手直接回答（走带记忆的 chatClient，记忆由
     * Advisor 自动落库）；否则顺序执行 spec，记忆由 {@link #savePlannerExchange} 统一补写。
     * <p>
     * 红线：① 本方法<b>不写记忆</b> —— 是否需显式落库由返回值的 {@code needSaveExchange} 告知调用方。
     * ② 有可执行计划时，先把计划落库为 task + task_step（开新任务前自动结旧），再执行并逐步提交状态。
     * ③ 会话开启「先看计划」（{@code conversation.planner_confirm}）时<b>只落库、不执行</b>：本轮返回计划清单，
     * 计划留在 RUNNING/PENDING，由用户在卡片上确认后走续跑通路执行（见 {@link #resumeTask}）。
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
        String planText = planText(specs);
        progress.accept(planText);
        // 「先看计划」：计划已落库（task=RUNNING、步骤全 PENDING）但本轮不执行，把决定权交还用户。
        // 执行入口复用断点续跑（ChatService.resume → resumeTask），因此这里不需要第二套执行逻辑，
        // 也不必在内存里挂起等待（SSE 连接不该为一个可能永远不来的确认长时间占着）。
        if (Boolean.TRUE.equals(conv.getPlannerConfirm())) {
            log.info("动态规划暂停：会话={}，任务={}，等待用户确认后执行", conversationId, task.getId());
            progress.accept("⏸ 计划已生成，等待你确认后开始执行");
            return new PlannerOutcome(confirmReply(specs), true, List.of(), planEventJson(task.getId(), specs));
        }
        StepsOutcome executed = executeSteps(specs, task.getId(), message, conversationId, conv, null, material,
                progress, trace);
        // 暂停：本轮到此为止，task 保持 RUNNING、剩余步骤保持 PENDING，等用户处理完后走续跑通路继续。
        // 注意这里<b>不能</b> finish 任务，也不能当作失败——尚未执行的步骤没有任何执行事实，只是被闸门挡住。
        // 两种暂停的后续动作不同：审批要等用户批准（推 approval 事件渲染审批卡片），用户喊停只需说明状态。
        if (executed.pausedAt() >= 0) {
            int idx = executed.pausedAt();
            if (PAUSE_USER.equals(executed.pauseReason())) {
                log.info("动态规划被用户暂停：会话={}，任务={}，下一步={}/{}",
                        conversationId, task.getId(), idx + 1, specs.size());
                progress.accept("⏸ 已暂停（第 " + (idx + 1) + " 步及之后未执行）");
                return new PlannerOutcome(userPausedReply(idx + 1, specs.size()), true, List.of());
            }
            log.info("动态规划暂停于审批关卡：会话={}，任务={}，步骤={}/{}",
                    conversationId, task.getId(), idx + 1, specs.size());
            progress.accept("⏸ 第 " + (idx + 1) + " 步需要审批，已暂停（剩余步骤未执行）");
            return new PlannerOutcome(approvalReply(idx + 1, specs.size(), specs.get(idx).agent().getName(),
                    specs.get(idx).instruction()), true, List.of(), null,
                    approvalEventJson(task.getId(), idx, specs.size(), specs.get(idx).agent().getAgentCode(),
                            specs.get(idx).agent().getName(), specs.get(idx).instruction()));
        }
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
        // 走到这里 = 用户明确要跑（点「继续执行」/「执行计划」/ 批准后继续）⇒ 先清掉暂停位。
        // 不清的话：① 点了继续会立刻又被自己的暂停请求拦住；② 标志留着，之后每一轮都一进去就停。
        taskService.clearPause(task.getId());
        List<TaskStep> rows = taskService.listSteps(task.getId());
        log.info("续跑任务：会话={}，任务={}，步骤={}", conversationId, task.getId(), rows.size());
        // 文案按进度分流：确认执行（一步未跑）与真正的中断续跑（跑过一半）是两种场景，措辞不该混用
        int doneSteps = task.getDoneSteps() == null ? 0 : task.getDoneSteps();
        progress.accept(doneSteps == 0
                ? "▶ 开始执行计划（共 " + task.getTotalSteps() + " 步）…"
                : "⏸ 继续执行剩余步骤（已完成 " + doneSteps + "/" + task.getTotalSteps() + "）…");

        // 从持久化的步骤重建 specs（agent 可能已被删除，删除者标 SKIPPED 不执行、产出记 null）
        int n = rows.size();
        List<StepSpec> specs = new ArrayList<>(n);
        int[] remap = new int[n];   // task_step.step_index(=首次 specs 下标) → 续跑 specs 下标
        java.util.Arrays.fill(remap, -1);
        List<Long> skippedIds = new ArrayList<>();
        // 审批关卡按 specs 连续下标收集（与 spec 同序），并把「specs 下标 → step_index」留存：
        // 授权/审批接口按 step_index 落库，而暂停点是 specs 下标，跳过了被删智能体时两者不相等。
        List<Boolean> approvalRequired = new ArrayList<>(n);
        List<Boolean> approvedFlags = new ArrayList<>(n);
        List<Integer> specStepIndex = new ArrayList<>(n);
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
            specs.add(new StepSpec(row.getId(), a, row.getInstruction(), TaskStep.depsFromJson(row.getDependsOn())));
            approvalRequired.add(Boolean.TRUE.equals(row.getApprovalRequired()));
            approvedFlags.add(Boolean.TRUE.equals(row.getApproved()));
            specStepIndex.add(row.getStepIndex());
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
                null, null, progress, trace, preOutputs, preDone, preCitations,
                ApprovalGate.of(approvalRequired, approvedFlags));
        // 暂停：任务不 finish（RUNNING 保持）、剩余步骤保持 PENDING，用户处理后再走本方法继续。
        // 暂停提示按「真实助手回复」落记忆（与「先看计划」同一口径）：用户确实收到了这条回复，不落库刷新就没了。
        if (executed.pausedAt() >= 0) {
            int idx = executed.pausedAt();
            int stepIndex = specStepIndex.get(idx);
            if (PAUSE_USER.equals(executed.pauseReason())) {
                log.info("续跑被用户暂停：会话={}，任务={}，下一步={}", conversationId, task.getId(), stepIndex + 1);
                progress.accept("⏸ 已暂停（第 " + (stepIndex + 1) + " 步及之后未执行）");
                String txt = userPausedReply(stepIndex + 1, task.getTotalSteps());
                savePlannerExchange(conversationId, task.getUserGoal(), txt);
                return RoundResult.answer(txt, List.of());
            }
            StepSpec s = specs.get(idx);
            log.info("续跑暂停于审批关卡：会话={}，任务={}，步骤={}",
                    conversationId, task.getId(), stepIndex + 1);
            progress.accept("⏸ 第 " + (stepIndex + 1) + " 步需要审批，已暂停（剩余步骤未执行）");
            String txt = approvalReply(stepIndex + 1, task.getTotalSteps(), s.agent().getName(), s.instruction());
            savePlannerExchange(conversationId, task.getUserGoal(), txt);
            return RoundResult.approval(txt, approvalEventJson(task.getId(), stepIndex, task.getTotalSteps(),
                    s.agent().getAgentCode(), s.agent().getName(), s.instruction()));
        }
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

    /**
     * 局部重规划：只重排「第一个未成功步骤及其之后」的一段，已完成的步骤与其产出保持不动。
     * <p>
     * 与 {@link #resumeTask} 的分工：resume 是「按原计划把没跑完的步再跑一遍」，重规划是「原计划这一段
     * 本身行不通，换一套」。两者并不冲突——重规划只改库（替换未完成尾部），用户随后点「执行计划」走的仍是
     * 续跑通路，所以这里不需要第二套执行逻辑，<b>改完不自动开跑</b>（由用户决定何时执行）。
     *
     * @return 重排后的计划 JSON + 变更说明；无可重排步骤或模型未产出可用步骤时返回 null（不改库）
     */
    public ReplanOutcome replanTask(String conversationId) {
        Task task = taskService.findRunning(conversationId);
        if (task == null) return null;
        List<TaskStep> rows = taskService.listSteps(task.getId());
        if (rows.isEmpty()) return null;
        // 起点 = 第一个尚未成功的步骤（PENDING / RUNNING / 未用尽重试的 FAILED 都算未成功）
        int fromIndex = -1;
        for (TaskStep r : rows) {
            if (!TaskService.isSettled(r)) {
                fromIndex = r.getStepIndex();
                break;
            }
        }
        if (fromIndex < 0) {
            log.info("局部重规划：任务 {} 的步骤均已完成，无可重排内容", task.getId());
            return null;
        }
        List<String> upstream = new ArrayList<>();
        List<String> tail = new ArrayList<>();
        for (TaskStep r : rows) {
            String name = agentName(r.getAgentCode());
            if (TaskService.isSettled(r)) {
                upstream.add("第 " + (r.getStepIndex() + 1) + " 步 · " + name
                        + "：" + summarize(r.getOutput()));
            } else {
                StringBuilder line = new StringBuilder("第 ").append(r.getStepIndex() + 1).append(" 步 · ")
                        .append(name).append("：")
                        .append(r.getInstruction() == null ? "（无指令）" : r.getInstruction());
                if (TaskStep.STATUS_FAILED.equals(r.getStatus()) && r.getError() != null) {
                    line.append("（上次执行失败：").append(r.getError()).append("）");
                }
                tail.add(line.toString());
            }
        }
        List<PlanStep> replanned = plannerService.replan(task.getUserGoal(), upstream, tail);
        if (replanned.isEmpty()) {
            log.warn("局部重规划：模型未产出可用步骤，任务={}", task.getId());
            return null;
        }
        // 过滤不存在的智能体 + 依赖重映射（与首次规划同一口径：被跳过的下标映射 -1，执行时按无依赖处理）
        int[] remap = new int[replanned.size()];
        java.util.Arrays.fill(remap, -1);
        List<TaskStep.Def> accepted = new ArrayList<>();
        for (int i = 0; i < replanned.size(); i++) {
            PlanStep s = replanned.get(i);
            if (agentService.getByCode(s.agentCode()) == null) {
                log.warn("局部重规划：智能体编码 {} 不存在，跳过该步骤", s.agentCode());
                continue;
            }
            remap[i] = accepted.size();
            accepted.add(new TaskStep.Def(s.agentCode(), s.instruction(), remapDeps(s.dependsOn(), remap)));
        }
        if (accepted.isEmpty()) {
            log.warn("局部重规划：新步骤涉及的智能体均不存在，放弃重规划");
            return null;
        }
        List<TaskStep> after = taskService.replanTail(task.getId(), fromIndex, accepted);
        int keptCount = after.size() - accepted.size();
        log.info("局部重规划完成：会话={}，任务={}，保留 {} 步，第 {} 步起重排为 {} 步",
                conversationId, task.getId(), keptCount, fromIndex + 1, accepted.size());
        return new ReplanOutcome(planJsonFromRows(task.getId(), after),
                "已保留前 " + keptCount + " 步（含已有产出），第 " + (fromIndex + 1) + " 步起重新规划为 "
                        + accepted.size() + " 步。确认后再点「执行计划」运行。");
    }

    /**
     * 套用规划模板：把模板的步骤骨架落库成该会话的新任务，<b>不执行</b>。
     * 三条通路的关系说清楚，避免以后各写一套：套用<b>只做「落库」这一件事</b>，用户随后点「执行计划」走的仍是
     * {@link #resumeTask}（落库的步骤就是执行侧要读的那份，所以不需要第二套执行逻辑）；与首次规划（→
     * {@code persistPlan}）的唯一区别是<b>步骤从哪来</b> —— 一个来自模型，一个来自模板。
     * <p>
     * 红线：<b>刻意不校验智能体是否存在</b> —— 模板记的是 {@code agentCode}，智能体可能事后被改名或删除；这里照落即可，
     * 执行侧对「agent 不存在」已有明确处理（标 {@code SKIPPED} 并向用户播报）。在此再加一道校验只会多出一处会漏的
     * 边界，也拦不住「套用时尚在、执行时已删」的竞态。
     *
     * @param tpl 模板（归属校验已由调用方完成）
     * @return 计划 JSON（与 {@code plan} 事件同构，前端按同一套渲染）+ 文案；模板无可用步骤时返回 null
     */
    public ApplyOutcome applyTemplate(String conversationId, TaskTemplate tpl, String goal) {
        List<TaskStep.Def> defs = TaskTemplate.stepsFromJson(tpl.getStepsJson());
        if (defs.isEmpty()) {
            log.warn("套用模板：模板 {} 没有可用步骤（steps_json 为空或数据损坏）", tpl.getId());
            return null;
        }
        taskService.cancelRunning(conversationId);   // 单会话单 RUNNING：套用也算开新任务，先结旧
        Task task = taskService.create(conversationId, goal, defs.size());
        boolean sanitized = false;
        for (int i = 0; i < defs.size(); i++) {
            TaskStep.Def d = defs.get(i);
            final int self = i;   // lambda 只能捕获 effectively final 的局部变量，循环变量不行 —— 取一份别名
            List<Integer> raw = d.dependsOn() == null ? List.of() : d.dependsOn();
            // 只留严格前序（约束与理由见 TaskStep#strictPriorDeps）：模板是持久化资产，可能被手改 SQL、
            // 或由更早的版本写入，这道净化不能省。丢弃时记 WARN —— 静默改掉依赖会让「执行顺序和计划对不上」
            // 变成查不出的谜。
            List<Integer> deps = TaskStep.strictPriorDeps(raw, self);
            if (deps.size() != raw.size()) {
                sanitized = true;
                log.warn("套用模板：模板 {} 第 {} 步的依赖 {} 含越界/后向下标，已丢弃并改写为 {}",
                        tpl.getId(), i + 1, raw, deps);
            }
            taskService.createStep(task.getId(), i, d.agentCode(), d.instruction(), TaskStep.depsToJson(deps));
        }
        List<TaskStep> rows = taskService.listSteps(task.getId());
        log.info("套用模板完成：会话={}，模板={}，任务={}，步骤={}",
                conversationId, tpl.getId(), task.getId(), rows.size());
        String text = "已按模板「" + tpl.getName() + "」生成 " + rows.size() + " 步计划，确认后点「执行计划」开始运行。"
                + (sanitized ? "\n（部分步骤的依赖下标不合法，已自动忽略，请核对计划后再执行）" : "");
        return new ApplyOutcome(planJsonFromRows(task.getId(), rows), text);
    }

    /** 智能体编码 → 展示名（已被删除的智能体回落为编码本身，不让展示层因缺数据而报错）。 */
    private String agentName(String agentCode) {
        Agent a = agentService.getByCode(agentCode);
        return a == null ? agentCode : a.getName();
    }

    /** 上游产出摘要：截前 {@value #REPLAN_SUMMARY_CHARS} 字符，重规划只需知道「这步做到了什么」。 */
    private static String summarize(String text) {
        if (text == null || text.isBlank()) return "（无产出）";
        String t = text.strip();
        return t.length() <= REPLAN_SUMMARY_CHARS ? t : t.substring(0, REPLAN_SUMMARY_CHARS) + "…";
    }

    /** 从落库的步骤行构造计划 JSON（与 {@link #planEventJson} 同构，前端可直接按同一套渲染）。 */
    private String planJsonFromRows(String taskId, List<TaskStep> rows) {
        JSONObject root = new JSONObject();
        root.set("taskId", taskId);
        JSONArray arr = new JSONArray();
        for (int i = 0; i < rows.size(); i++) {
            TaskStep r = rows.get(i);
            JSONObject o = new JSONObject();
            o.set("index", i + 1);
            o.set("agentCode", r.getAgentCode());
            o.set("agentName", agentName(r.getAgentCode()));
            o.set("instruction", r.getInstruction());
            o.set("dependsOn", TaskStep.depsFromJson(r.getDependsOn()));
            // 审批标记随计划下发，计划卡片的「需审批」勾选框据此回显
            o.set("approvalRequired", Boolean.TRUE.equals(r.getApprovalRequired()));
            arr.add(o);
        }
        root.set("steps", arr);
        return root.toString();
    }

    /** 把计划落库为 task + task_step（开新规划前先结旧），返回 task 且回填每步 DB id 到 specs。 */
    private Task persistPlan(String conversationId, String userGoal, List<StepSpec> specs) {
        taskService.cancelRunning(conversationId);
        Task task = taskService.create(conversationId, userGoal, specs.size());
        for (int i = 0; i < specs.size(); i++) {
            StepSpec s = specs.get(i);
            // createStep 落库后自增 id 已回填到返回的实体，直接回填进 specs 供逐步提交状态用
            TaskStep row = taskService.createStep(task.getId(), i, s.agent().getAgentCode(), s.instruction(),
                    TaskStep.depsToJson(s.dependsOn()));
            specs.set(i, new StepSpec(row.getId(), s.agent(), s.instruction(), s.dependsOn()));
        }
        return task;
    }

    /**
     * 按依赖拓扑分层<b>并行</b>执行一组步骤（智能体 + 指令）：无依赖的步骤同层并发跑，依赖步骤等其
     * 前驱完成后以其输出为输入。与顺序版同一套记忆契约：中间产物不写历史、只注入最后一步近期窗口、
     * RAG 引用取最终步骤、progress 只展示不进记忆。首次执行入口（无已回填状态），逐步落库状态。
     */
    private StepsOutcome executeSteps(List<StepSpec> steps, String taskId, String firstInput, String conversationId,
                                      Conversation conv, String paramBlock, String material,
                                      Consumer<String> progress, RoundTrace trace) {
        // 首次执行不传审批关卡：步骤刚落库、审批标记尚未被用户勾选（要勾只能在「先看计划」/重规划/套用模板
        // 的计划卡片上勾，那三条通路之后都走 resumeTask）。
        return executeStepsWithState(steps, taskId, firstInput, conversationId, conv, paramBlock, material,
                progress, trace, null, null, null, null);
    }

    /**
     * 分层并行执行的统一实现：{@code preOutputs/preDone/preCitations} 为已回填状态（续跑时传入，首次执行传 null），
     * 其余未 done 的步骤按拓扑分层并行执行并逐步落库。
     * 每轮找出「依赖均已满足」的未执行步骤，用 {@code CompletableFuture} 并行执行；第 1 层（无依赖）输入 = 用户原始
     * 目标 + 附件，有依赖的步骤输入 = 其指令 + {@code dependsOn} 指向的前驱输出（多个按序拼接）；全程用无记忆
     * ChatClient（中间产物不写历史），最后一个完成层注入近期窗口历史；某步失败 / 返回空则其输出记为 null、依赖它的
     * 步骤回落原始目标作答，全部失败返回 null。
     * <p>
     * 红线：① 依赖下标非法（越界 / 指向自身或后序）按「无依赖」处理；某层无步骤可推进（依赖环）则断环跳出。
     * ② <b>审批闸门</b>：本层只要有一步「{@code approvalRequired} 且未 {@code approved}」，<b>整条流水线</b>在此暂停
     * （本层其余可执行步骤也不跑），返回 {@code pausedAt} 指向该步，剩余步骤保持未执行。
     *
     * @param gate 审批闸门（null = 全部步骤都无需审批）；暂停判定的唯一依据
     */
    private StepsOutcome executeStepsWithState(List<StepSpec> steps, String taskId, String firstInput,
                                               String conversationId, Conversation conv, String paramBlock,
                                               String material, Consumer<String> progress, RoundTrace trace,
                                               String[] preOutputs, boolean[] preDone,
                                               List<KbCitation>[] preCitations, ApprovalGate gate) {
        if (steps == null || steps.isEmpty()) return new StepsOutcome(null, List.of(), -1, null);
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
        int pausedAt = -1;                       // 暂停点（specs 连续下标）：审批闸门拦下 或 用户喊停；-1=未暂停
        String pauseReason = null;               // 非 null 时恒为 PAUSE_USER；null + pausedAt>=0 = 审批闸门拦下
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
            // 用户中途喊停：与审批闸门落在同一处（层边界）—— 同层是并行 join，硬中断只会留下半截产出、
            // 还得从头再问，所以只停「还没开跑的层」，正在跑的那一层照常跑完。
            // 放在审批检查之前：用户明确要求停，优先于「本层恰好有一步要审批」。
            if (taskService.isPauseRequested(taskId)) {
                pausedAt = ready.get(0);
                pauseReason = PAUSE_USER;
                break;
            }
            // 审批闸门：本层若含未批准的审批步，整条流水线在此暂停（不推进本层任何步骤，含未受管制的兄弟步）。
            // 取舍说明：逐层检查而非「只挡该步、其余照跑」——后者会让用户看到「卡住的那一步后面的步骤先出了结果」，
            // 流水线的因果顺序变得难解释；「到此为止」与「审批点」的直觉一致。ready 按下标升序，故取到的是最靠前的那步。
            int blocked = -1;
            for (int i : ready) {
                if (gate != null && gate.blocked(i)) {
                    blocked = i;
                    break;
                }
            }
            if (blocked >= 0) {
                pausedAt = blocked;
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
        return new StepsOutcome(last, lastCitations, pausedAt, pauseReason);
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
            // 审批闸门：规划步骤同样会调工具（查库/画图），过同一把闸门；用户原话取「原始目标」——
            // 批准后前端据它重跑整轮，而不是拿某一步的合成输入去重跑
            spec = composer.decorateRequest(spec, s.agent(), trace, conversationId, null,
                    composer.approvalContext(conversationId, s.agent(), firstInput));
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

    /** 计划清单文本（第 1 步…第 N 步），既作 progress 播报，也作「先看计划」时的回复正文。 */
    private static String planText(List<StepSpec> specs) {
        StringBuilder sb = new StringBuilder("📋 规划完成，共 " + specs.size() + " 步：");
        for (int i = 0; i < specs.size(); i++) {
            StepSpec s = specs.get(i);
            sb.append("\n").append(i + 1).append(". ").append(s.agent().getName());
            if (s.instruction() != null && !s.instruction().isBlank()) {
                sb.append(" —— ").append(s.instruction());
            }
        }
        return sb.toString();
    }

    /**
     * 「先看计划」暂停时的回复正文：计划清单 + 明确告知「尚未执行」。
     * <p>
     * 计划明细写进正文（而非只走 plan 事件）是刻意的：plan 事件不落库，刷新页面后卡片就没了，
     * 用户至少还能从这条消息里看到当初打算怎么做。
     */
    private static String confirmReply(List<StepSpec> specs) {
        return planText(specs)
                + "\n\n（本计划尚未执行。确认无误后点上方卡片里的「执行计划」开始运行。）";
    }

    /**
     * 执行到审批关卡暂停时的回复正文：说清「卡在第几步、要跑什么、批了会怎样、不批怎么办」。
     * <p>
     * 与 {@link #confirmReply} 同理写进正文而非只走 approval 事件：事件不落库，刷新后审批卡片就没了，
     * 但这条消息还在（暂停提示按真实一轮写入会话记忆），用户能据此回忆自己停在哪。
     */
    private static String approvalReply(int stepNo, int totalSteps, String agentName, String instruction) {
        return "⏸ 执行到第 " + stepNo + "/" + totalSteps + " 步（" + agentName + "）时需要你确认。\n\n"
                + (instruction == null || instruction.isBlank() ? "" : "本步指令：" + instruction + "\n\n")
                + "点「批准并继续」后从这里接着往下跑（前面步骤的产出会保留）；不想继续可点「终止计划」。";
    }

    /**
     * 用户中途喊停时的回复文案：说清「停在哪、已跑完的还在、下一步怎么继续」。
     * <p>
     * 刻意不提「本层还要跑完」：那句话在暂停时点是过去式（执行循环已经在层边界 break 了），写在回复里
     * 反而让人以为还在跑。真正需要它是 {@code /task/pause} 的即时回执 —— 那一刻本层确实可能还没跑完。
     */
    private static String userPausedReply(int nextStepNo, int totalSteps) {
        return "⏸ 已按你的要求暂停。\n\n"
                + "第 " + nextStepNo + " 步及之后（共 " + totalSteps + " 步）尚未执行；前面已完成的步骤产出都保留着，"
                + "本轮不算失败。\n\n"
                + "你可以调整剩余步骤（改智能体 / 指令 / 依赖前驱，或跳过某一步），改完点「继续执行」接着跑 —— "
                + "走的仍是断点续跑，不会重新规划。";
    }

    /**
     * 待审批步骤事件体（推给前端渲染审批卡片）：
     * {@code {"taskId":"...","stepIndex":2,"index":3,"totalSteps":5,"agentCode":"...","agentName":"...","instruction":"..."}}。
     * <p>
     * {@code stepIndex} 是 <b>0 基</b>、{@code index} 是 <b>1 基</b>（与计划卡片的步骤序号同口径，给人看）；
     * 批准接口按 {@code stepIndex} 定位，故两个都带上，避免前端各算一次「±1」。
     * <p>
     * 带 taskId 与计划事件保持一致，便于前端把审批卡片和同一份计划对上；批准动作本身按会话定位 RUNNING
     * 任务（单会话单 RUNNING 是 {@link TaskService} 的不变量），不依赖该字段。
     */
    private static String approvalEventJson(String taskId, int stepIndex, int totalSteps, String agentCode,
                                            String agentName, String instruction) {
        JSONObject root = new JSONObject();
        root.set("taskId", taskId);
        root.set("stepIndex", stepIndex);
        root.set("index", stepIndex + 1);
        root.set("totalSteps", totalSteps);
        root.set("agentCode", agentCode);
        root.set("agentName", agentName);
        root.set("instruction", instruction);
        return root.toString();
    }

    /**
     * 待确认计划事件体（推给前端渲染卡片）：
     * {@code {"taskId":"...","steps":[{index,agentCode,agentName,instruction,dependsOn}]}}。
     * <p>
     * 带 taskId 便于前端与 {@code GET /api/chat/task/running} 的返回对齐；执行动作本身只按 conversationId
     * 定位 RUNNING 任务（单会话单 RUNNING 是 {@link TaskService} 的不变量），不依赖该字段。
     * <p>
     * {@code dependsOn} 带的是<b>已经重映射过的连续下标</b>（跳过不存在的智能体后压缩过），与库中
     * {@code task_step.depends_on} 完全一致——前端编辑依赖时按同一口径回传，才不会出现「显示 3 实际写 4」。
     * <p>
     * {@code index} 是<b>1 基</b>（给人看），而 {@code dependsOn} 是 0 基（给执行用）。
     */
    private static String planEventJson(String taskId, List<StepSpec> specs) {
        JSONObject root = new JSONObject();
        root.set("taskId", taskId);
        JSONArray arr = new JSONArray();
        for (int i = 0; i < specs.size(); i++) {
            StepSpec s = specs.get(i);
            JSONObject o = new JSONObject();
            o.set("index", i + 1);
            o.set("agentCode", s.agent().getAgentCode());
            o.set("agentName", s.agent().getName());
            o.set("instruction", s.instruction());
            o.set("dependsOn", s.dependsOn() == null ? new JSONArray() : s.dependsOn());
            // 首次规划落库的步骤一律不带审批关卡（createStep 写死 false）；带上该字段只为与 planJsonFromRows
            // 同构 —— 前端一套渲染代码吃两种来源，字段缺失会让勾选框显示成未定义。
            o.set("approvalRequired", false);
            arr.add(o);
        }
        root.set("steps", arr);
        return root.toString();
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
     * @param planJson         「先看计划」暂停时的待确认计划 JSON；未暂停为 null
     * @param approvalJson     执行到审批关卡暂停时的待批步骤 JSON；未暂停为 null
     */
    /**
     * 局部重规划结果。
     *
     * @param planJson 重排后的<b>完整</b>计划（保留段 + 新步骤，与 {@code plan} 事件同构），前端可直接渲染卡片
     * @param text     变更说明（保留了几步、从第几步起被重排），作为消息正文展示
     */
    public record ReplanOutcome(String planJson, String text) {
    }

    /**
     * 套用模板的结果：{@code planJson} 为按骨架落库后的计划（与 {@code plan} 事件同构）；
     * {@code text} 为给用户看的说明（含依赖被净化时的提示）。
     */
    public record ApplyOutcome(String planJson, String text) {
    }

    private record PlannerOutcome(String reply, boolean needSaveExchange, List<KbCitation> citations, String planJson,
                                 String approvalJson) {

        /** 常规产出（无待确认计划、无待审批步骤）。 */
        PlannerOutcome(String reply, boolean needSaveExchange, List<KbCitation> citations) {
            this(reply, needSaveExchange, citations, null, null);
        }

        /** 「先看计划」暂停产出。 */
        PlannerOutcome(String reply, boolean needSaveExchange, List<KbCitation> citations, String planJson) {
            this(reply, needSaveExchange, citations, planJson, null);
        }
    }

    /**
     * 顺序执行结果：最终回复 + 产出该回复那一步的 RAG 引用 + 审批暂停点。
     *
     * @param pausedAt 被审批闸门拦下的步骤下标（specs 连续下标）；-1 表示未暂停（全部可执行步骤已跑完）
     */
    private record StepsOutcome(String reply, List<KbCitation> citations, int pausedAt, String pauseReason) {
    }

    /**
     * 审批闸门：哪些步骤需要执行前审批、哪些已批准（下标与 specs 连续下标同口径）。
     * <p>
     * 用两个定长数组而非 {@code Set<Integer>}：判定在每层执行前对每个 ready 步骤各做一次，
     * 数组下标访问无装箱、无哈希，且构造时就能保证与 specs 等长——长度不匹配会在这里立刻暴露，
     * 而不是在运行到某步时才越界。
     */
    private record ApprovalGate(boolean[] required, boolean[] approved) {

        /** 由步骤列表构造（null 安全）；全部步骤都无需审批时返回 null，让执行循环完全不必判闸门。 */
        static ApprovalGate of(List<Boolean> required, List<Boolean> approved) {
            int n = required.size();
            if (n == 0) return null;
            boolean any = false;
            boolean[] req = new boolean[n];
            boolean[] appr = new boolean[n];
            for (int i = 0; i < n; i++) {
                req[i] = Boolean.TRUE.equals(required.get(i));
                appr[i] = Boolean.TRUE.equals(approved.get(i));
                if (req[i]) any = true;
            }
            return any ? new ApprovalGate(req, appr) : null;
        }

        /** 该步是否被挡（需审批且未批准）。 */
        boolean blocked(int idx) {
            return idx >= 0 && idx < required.length && required[idx] && !approved[idx];
        }
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
