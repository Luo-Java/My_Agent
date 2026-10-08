package org.luo.ai.controller;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.luo.ai.agent.handler.PlannerRoundHandler;
import org.luo.ai.dto.ApplyTaskTemplateRequest;
import org.luo.ai.dto.ApproveStepRequest;
import org.luo.ai.dto.ChatAttachment;
import org.luo.ai.dto.ChatRequest;
import org.luo.ai.dto.ResumeTaskRequest;
import org.luo.ai.dto.RunningTaskView;
import org.luo.ai.dto.SaveTaskTemplateRequest;
import org.luo.ai.dto.SkipStepRequest;
import org.luo.ai.dto.StreamEvent;
import org.luo.ai.dto.TaskTemplateSummary;
import org.luo.ai.dto.UpdateTaskStepRequest;
import org.luo.ai.entity.Agent;
import org.luo.ai.entity.Task;
import org.luo.ai.entity.TaskStep;
import org.luo.ai.entity.TaskTemplate;
import org.luo.ai.service.AgentService;
import org.luo.ai.service.ChatService;
import org.luo.ai.service.ContentSafetyService;
import org.luo.ai.service.ConversationService;
import org.luo.ai.service.QuotaService;
import org.luo.ai.service.TaskService;
import org.luo.ai.service.TaskTemplateService;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.system.constant.SysRoleCode;
import org.luo.system.security.AuthContext;
import org.luo.system.security.LoginUser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话接口（会话管理见 ConversationController）。端点：{@code /send} 同步、{@code /stream} SSE 流式、
 * {@code /task/{resume,running,step,step/skip,approve,cancel,pause,replan}} 与 {@code /task/template/**} 规划任务运维。
 * <p>
 * 红线：{@code /send}、{@code /stream}、{@code /task/resume} 三个花钱入口先过当日配额闸门
 * （{@link org.luo.ai.service.QuotaService}）与输入侧护栏（{@link org.luo.ai.service.ContentSafetyService#checkInput}），
 * 超限 / 命中<b>明确报错、不静默降级</b>。SSE 必须有有限超时（<b>不要 0L 永不超时</b>：上游卡死会让连接与异步线程
 * 永久泄漏）+ 心跳保活前置链静默期。身份在 <b>HTTP 线程</b>取出（{@link AuthContext#require()}）后当参数往下传，
 * 越权会话在此处就被 {@link ConversationService#checkAccess} 挡成 404，而不是退化成 SSE 里的一条 error 事件。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ConversationService conversationService;
    /** 智能体表：仅用于把步骤的 {@code agentCode} 翻成可读名（{@code /task/running}）。 */
    private final AgentService agentService;
    private final TaskService taskService;
    private final TaskTemplateService templateService;
    /** 成本配额闸门：/send、/stream、/task/resume 三个花 token 的入口在开跑前查一次。 */
    private final QuotaService quotaService;
    /** 内容安全护栏（输入侧）：命中即拒绝，本轮不产生任何模型调用。 */
    private final ContentSafetyService safetyService;

    /** SSE 连接最长存活时间（秒）。必须有限，默认 300s，足够覆盖最长的规划 + 多轮工具调用。 */
    private final long sseTimeoutSeconds;

    /** 心跳间隔（秒），{@code <=0} 表示关闭心跳。 */
    private final long heartbeatSeconds;

    /**
     * 心跳专用调度器（bean {@code sseHeartbeatScheduler}）。
     * <p>
     * 刻意<b>不复用</b> Reactor {@code Schedulers.parallel()}：心跳要调 {@code SseEmitter.send()}，
     * 对慢客户端是<b>阻塞</b>调用；而 {@code parallel()} 同时被打字机与 Reactor 内部使用，
     * 几个卡住的连接就能把它占满、连带全站心跳停摆。用独立调度器隔离风险（见 ExecutorConfig）。
     */
    private final ScheduledExecutorService heartbeatScheduler;

    public ChatController(ChatService chatService, ConversationService conversationService,
                          AgentService agentService,
                          TaskService taskService, TaskTemplateService templateService, QuotaService quotaService,
                          ContentSafetyService safetyService,
                          @Value("${app.sse.timeout-seconds:300}") long sseTimeoutSeconds,
                          @Value("${app.sse.heartbeat-seconds:15}") long heartbeatSeconds,
                          @Qualifier("sseHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler) {
        this.chatService = chatService;
        this.conversationService = conversationService;
        this.agentService = agentService;
        this.taskService = taskService;
        this.templateService = templateService;
        this.quotaService = quotaService;
        this.safetyService = safetyService;
        this.sseTimeoutSeconds = sseTimeoutSeconds;
        this.heartbeatSeconds = heartbeatSeconds;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    /** 同步对话：等待完整回复后一次性返回 {@code {"content": "..."}}。 */
    @PostMapping("/send")
    public Map<String, String> send(@RequestBody ChatRequest request) {
        LoginUser user = AuthContext.require();
        Long userId = user.id();
        // 内容安全（输入侧）：命中即拒绝，本轮不产生任何模型调用。与配额同一位置、同一「明确拒绝」口径
        String blocked = safetyService.checkInput(request.message());
        if (blocked != null) {
            throw new AiBusinessException(AiErrorCode.CONTENT_BLOCKED, blocked);
        }
        QuotaService.QuotaStatus quota = quotaService.status(userId, isAdmin(user));
        if (quota.exhausted()) {
            // 同步接口没有事件通道，只能抛业务异常：429 + 说明文案（已用/上限/重置时间），不静默降级
            throw new AiBusinessException(AiErrorCode.QUOTA_EXCEEDED, quota.exhaustedMessage());
        }
        String conversationId = resolveConversationId(request.conversationId(), userId);
        conversationService.checkAccess(conversationId, userId);
        // 一次遍历同时抽出「当轮材料」与「展示元数据」：纯提问走记忆/路由，两者分别注入 system / 落库，互不影响
        AttachmentBundle bundle = extractAttachments(request.attachments());
        String reply = chatService.chat(conversationId, request.message(),
                bundle.material(), bundle.metaJson(), request.planner(), userId, request.branch());
        // Map.of 拒绝 null 值：出口再兜一道，避免上游漏判空把一次 200 变成 500
        return Map.of("content", reply == null ? "" : reply);
    }

    /**
     * 流式对话：以 SSE 推送事件，前端逐字渲染。每条事件 data 为 JSON，字段名即事件类型：
     * {@code {"token":"..."}} 正文分片（唯一写入会话记忆的内容）；{@code {"progress":"..."}} 执行过程
     * （规划步骤与进展，不写入记忆、刷新后消失）。
     * <p>
     * 请求体可带 {@code branchGroupId} / {@code branchVersion}（来自 {@code POST …/{id}/branch}），
     * 表示本轮要落成某一轮的新版本：服务端在消息确实落库之后才打标并让旧版本失效，
     * 打标失败会补推一条 {@code progress} 明说 —— 不让用户以为「重发生效了」其实没有。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        // 有限超时（而非 0L=永不超时）：上游卡死时连接与异步线程能自动释放，不会永久泄漏
        SseEmitter emitter = new SseEmitter(sseTimeoutSeconds > 0 ? sseTimeoutSeconds * 1000L : 0L);
        // 身份在 HTTP 线程取出后向下传：进入异步（boundedElastic）后 ThreadLocal 失效
        LoginUser user = AuthContext.require();
        Long userId = user.id();
        String conversationId = resolveConversationId(request.conversationId(), userId);
        conversationService.checkAccess(conversationId, userId);   // 越权会话在建立 SSE 之前就 404
        // 一次遍历同时抽出「当轮材料」与「展示元数据」：纯提问走记忆/路由，两者分别注入 system / 落库，互不影响
        AttachmentBundle bundle = extractAttachments(request.attachments());
        // 内容安全（输入侧）：命中只推一条 error 事件、不订阅执行体 —— 与配额超限同一处理方式，本轮零模型调用
        String blocked = safetyService.checkInput(request.message());
        // 配额闸门：超限只推一条 error（不订阅执行体，本轮不会有任何模型调用）；接近上限则先播提示再照常执行
        Flux<StreamEvent> flux = blocked != null
                ? Flux.just(StreamEvent.error(blocked))
                : guarded(quotaService.status(userId, isAdmin(user)),
                        chatService.stream(conversationId, request.message(),
                                bundle.material(), bundle.metaJson(), request.planner(), userId, request.branch()));

        // 流结束标记：心跳任务据此自停；同时保证「流结束后不再往已完成的 emitter 写」
        AtomicBoolean finished = new AtomicBoolean(false);

        Disposable subscription = flux.doOnNext(event -> {
                    try {
                        // 用 JSON 包裹文本：内容里的换行会被转义，避免 \n\n 被前端误判为 SSE 事件边界而丢字。
                        // 字段名即事件类型（token / progress / error），前端据此决定渲染到正文还是执行过程区。
                        emitter.send(Map.of(event.type(), event.text() == null ? "" : event.text()));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                })
                .doOnComplete(() -> {
                    finished.set(true);
                    emitter.complete();
                })
                .doOnError(e -> {
                    finished.set(true);
                    emitter.completeWithError(e);
                })
                .subscribe();

        // 心跳：前置链期间无字节流出，易被反向代理判为空闲切断。用独立周期任务推 ping 保活，与业务流完全解耦
        // （不动流的终止语义——若改用 Flux.merge/interval，无限流会让 emitter 永不 complete）。
        // 调度器为独立线程池（非 Reactor parallel），且用 scheduleWithFixedDelay：下一次从上次执行完成才开始计时，
        // 慢客户端只让该连接的心跳顺延，不在身上堆任务。
        ScheduledFuture<?> heartbeat = heartbeatSeconds > 0
                ? heartbeatScheduler.scheduleWithFixedDelay(() -> {
                    if (finished.get()) {
                        return;
                    }
                    try {
                        emitter.send(Map.of(StreamEvent.TYPE_PING, "1"));
                    } catch (Exception e) {
                        // 客户端已断开：连接即将因 onCompletion 被清理，无需额外处理
                        finished.set(true);
                    }
                }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS)
                : null;

        // 客户端断开（或超时）时取消订阅与心跳：停止后续推送与延迟任务，避免 LLM 侧已排队的调用继续空烧 token。
        Runnable cleanup = () -> {
            finished.set(true);
            subscription.dispose();
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        return emitter;
    }

    /**
     * 显式续跑未完成任务（SSE 流式）：按会话定位唯一 RUNNING 任务，回填已完成步骤、只跑剩余步骤。
     * 事件类型与 stream 一致（progress 播报 / token 正文 / citations 引用 / error 错误）。
     */
    @PostMapping(value = "/task/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(@RequestBody ResumeTaskRequest request) {
        SseEmitter emitter = new SseEmitter(sseTimeoutSeconds > 0 ? sseTimeoutSeconds * 1000L : 0L);
        LoginUser user = AuthContext.require();
        Long userId = user.id();
        String conversationId = resolveConversationId(request.conversationId(), userId);
        conversationService.checkAccess(conversationId, userId);
        Flux<StreamEvent> flux = guarded(quotaService.status(userId, isAdmin(user)),
                chatService.resume(conversationId, userId));

        AtomicBoolean finished = new AtomicBoolean(false);
        Disposable subscription = flux.doOnNext(event -> {
                    try {
                        emitter.send(Map.of(event.type(), event.text() == null ? "" : event.text()));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                })
                .doOnComplete(() -> {
                    finished.set(true);
                    emitter.complete();
                })
                .doOnError(e -> {
                    finished.set(true);
                    emitter.completeWithError(e);
                })
                .subscribe();

        // 心跳逻辑与 stream 一致（见 stream 方法内注释）：续跑剩余步骤期间无字节流出，易被反向代理判空闲切断。
        ScheduledFuture<?> heartbeat = heartbeatSeconds > 0
                ? heartbeatScheduler.scheduleWithFixedDelay(() -> {
                    if (finished.get()) {
                        return;
                    }
                    try {
                        emitter.send(Map.of(StreamEvent.TYPE_PING, "1"));
                    } catch (Exception e) {
                        finished.set(true);
                    }
                }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS)
                : null;

        Runnable cleanup = () -> {
            finished.set(true);
            subscription.dispose();
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        return emitter;
    }

    /**
     * 查询当前会话的未完成任务（前端「继续执行」提示条用）；无 RUNNING 任务返回 null。仅本人会话可查。
     * <p>
     * 带步骤明细：提示条不仅要显示进度，还要指出<b>哪一步卡住了</b>并提供「跳过该步」入口（见
     * {@link RunningTaskView}）。智能体名现查、不落库快照。
     */
    @GetMapping("/task/running")
    public RunningTaskView running(@RequestParam String conversationId) {
        Long userId = AuthContext.require().id();
        String scopedId = resolveConversationId(conversationId, userId);
        conversationService.checkAccess(scopedId, userId);
        Task task = taskService.findRunning(scopedId);
        if (task == null) return null;
        // 一次查全 agent 表建映射，不逐步骤查库：该端点在每轮对话结束后都会被调用
        Map<String, String> names = new HashMap<>();
        for (Agent a : agentService.listAgents()) {
            names.put(a.getAgentCode(), a.getName());
        }
        List<RunningTaskView.StepView> steps = new ArrayList<>();
        for (TaskStep s : taskService.listSteps(task.getId())) {
            String st = s.getStatus();
            boolean exhausted = TaskStep.STATUS_FAILED.equals(st)
                    && s.getRetryCount() != null && s.getRetryCount() >= TaskStep.MAX_RETRY;
            steps.add(new RunningTaskView.StepView(
                    s.getStepIndex() == null ? 0 : s.getStepIndex(),
                    s.getAgentCode(),
                    names.getOrDefault(s.getAgentCode(), s.getAgentCode()),
                    st, s.getError(), exhausted));
        }
        return new RunningTaskView(task.getId(), task.getConversationId(), task.getUserGoal(), task.getStatus(),
                task.getTotalSteps() == null ? 0 : task.getTotalSteps(),
                task.getDoneSteps() == null ? 0 : task.getDoneSteps(),
                Boolean.TRUE.equals(task.getPauseRequested()), steps);
    }

    /**
     * 查当前登录用户的成本配额状态（前端输入区展示「本日已用 / 上限」）。
     * <p>
     * 返回的是<b>本人</b>用量，不是全站数据，因此不要求 ADMIN —— 与成本看板（跨会话全站聚合，仅 ADMIN）是
     * 两个不同的东西。配额未启用时 {@code enabled=false}，前端据此隐藏提示条。
     */
    @GetMapping("/quota")
    public Map<String, Object> quota() {
        LoginUser user = AuthContext.require();
        QuotaService.QuotaStatus s = quotaService.status(user.id(), isAdmin(user));
        return Map.of("enabled", s.enabled(), "exempt", s.exempt(), "used", s.used(),
                "limit", s.limit(), "remaining", s.remaining());
    }

    /**
     * 就地编辑「先看计划」待确认任务中的某一步（智能体 / 指令 / 依赖前驱）。
     * <p>
     * 同步接口（非 SSE）：改动落库后由前端重新点「执行计划」触发续跑。之所以不做成「改完直接跑」，
     * 是因为「先看计划」的语义就是「逐条审阅、想清楚再跑」——连续改几步不该中途启动执行。
     * <p>
     * 越权与不存在的会话统一由 {@link ConversationService#checkAccess} 判 404；无 RUNNING 任务说明
     * 计划已被执行或取消，返回 404 而非静默成功。
     */
    @PutMapping("/task/step")
    public Map<String, Object> updateStep(@RequestBody UpdateTaskStepRequest request) {
        Long userId = AuthContext.require().id();
        String scopedId = resolveConversationId(request.conversationId(), userId);
        conversationService.checkAccess(scopedId, userId);
        Task task = requireRunning(scopedId);
        if (request.stepIndex() == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "缺少步骤下标");
        }
        String rejected = taskService.updatePendingStep(task.getId(), request.stepIndex(),
                request.agentCode(), request.instruction(), request.dependsOn(), request.approvalRequired());
        if (rejected != null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, rejected);
        }
        return Map.of("ok", true, "stepIndex", request.stepIndex());
    }

    /**
     * 批准「待审批步骤」：写 {@code approved=1}，使该步可被放行执行。
     * <p>
     * 同步接口（非 SSE），<b>只写标记不执行</b>：前端紧接着调 {@link #resume} 那条续跑通路继续跑。
     * 这样「批准」与「执行」各自职责单一，执行逻辑仍只有 {@code resumeTask} 一处。
     */
    @PostMapping("/task/approve")
    public Map<String, Object> approveStep(@RequestBody ApproveStepRequest request) {
        Long userId = AuthContext.require().id();
        String scopedId = resolveConversationId(request.conversationId(), userId);
        conversationService.checkAccess(scopedId, userId);
        Task task = requireRunning(scopedId);
        if (request.stepIndex() == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "缺少步骤下标");
        }
        String rejected = taskService.approveStep(task.getId(), request.stepIndex());
        if (rejected != null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, rejected);
        }
        return Map.of("ok", true, "stepIndex", request.stepIndex());
    }

    /**
     * 终止当前会话未完成的任务（把 RUNNING 结为 CANCELLED），剩余步骤不再执行。
     * <p>
     * 审批卡片的「终止计划」走这里，也是「一条跑歪的计划不想再要了」的通用出口——此前只有切换执行入口
     * 才能摆脱它。已完成的步骤与其产出保留在库里（不影响会话消息），只是任务不再处于待续跑状态。
     */
    @PostMapping("/task/cancel")
    public Map<String, Object> cancelTask(@RequestBody ResumeTaskRequest request) {
        Long userId = AuthContext.require().id();
        String scopedId = resolveConversationId(request.conversationId(), userId);
        conversationService.checkAccess(scopedId, userId);
        requireRunning(scopedId);   // 没有 RUNNING 任务时明确 404，不静默成功
        taskService.cancelRunning(scopedId);
        return Map.of("ok", true);
    }

    /**
     * 请求暂停正在执行的计划：置 {@code task.pause_requested=1}，执行循环在下一个「层边界」停止推进。
     * <p>
     * <b>措辞刻意不说「已暂停」</b>：这一层可能还在跑（同层是并行 join，硬中断只会留下半截产出），
     * 真正停下要等执行循环在层边界读到该标志。回执里把这点写进 {@code message}，前端原样展示 ——
     * 说「已暂停」而后台还在跑，是比不提供暂停更糟的体验。
     * <p>
     * 停止后任务仍是 RUNNING、剩余步骤仍是 PENDING，用户可改步 / 跳步，再点「继续执行」走续跑通路；
     * 暂停位由续跑入口负责清零，所以不会把用户永久挡在门外。
     */
    @PostMapping("/task/pause")
    public Map<String, Object> pauseTask(@RequestBody ResumeTaskRequest request) {
        Long userId = AuthContext.require().id();
        String scopedId = resolveConversationId(request.conversationId(), userId);
        conversationService.checkAccess(scopedId, userId);
        Task task = requireRunning(scopedId);   // 没有在跑的任务时明确 404：暂停一个不存在的执行没有意义
        boolean recorded = taskService.requestPause(task.getId());
        return Map.of("ok", true,
                "pauseRequested", recorded,
                "message", "已请求暂停：当前正在执行的那一层跑完后停止推进，已完成的步骤产出都会保留");
    }

    /**
     * 人工跳过某一步：{@code PENDING / FAILED → SKIPPED}，随后可点「继续执行」越过它往下跑。
     * <p>
     * 没有这个入口时，一个反复失败的步骤会把整个任务<b>永久卡死</b>：重试次数一旦用尽，执行侧只把它当作
     * 「前驱失败」，而后续依赖它的步骤永远凑不齐前驱、每一轮续跑都在同一处空转。
     * <p>
     * 同样只改库、<b>不自动开跑</b>（与局部重规划、步骤编辑同节奏）：改完由用户决定何时继续。
     * 回执带上跳过后重新统计的进度，前端据此更新提示条。
     */
    @PostMapping("/task/step/skip")
    public Map<String, Object> skipStep(@RequestBody SkipStepRequest request) {
        Long userId = AuthContext.require().id();
        String scopedId = resolveConversationId(request.conversationId(), userId);
        conversationService.checkAccess(scopedId, userId);
        Task task = requireRunning(scopedId);
        if (request.stepIndex() == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "缺少步骤下标");
        }
        String rejected = taskService.skipStep(task.getId(), request.stepIndex());
        if (rejected != null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, rejected);
        }
        Task fresh = taskService.findById(task.getId());
        return Map.of("ok", true,
                "stepIndex", request.stepIndex(),
                "doneSteps", fresh == null || fresh.getDoneSteps() == null ? 0 : fresh.getDoneSteps(),
                "totalSteps", fresh == null || fresh.getTotalSteps() == null ? 0 : fresh.getTotalSteps());
    }

    /** 取当前会话的 RUNNING 任务；不存在则 404（计划已被执行完 / 已取消）。 */
    private Task requireRunning(String conversationId) {
        Task task = taskService.findRunning(conversationId);
        if (task == null) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "当前会话没有待确认或未完成的计划");
        }
        return task;
    }

    /**
     * 局部重规划未完成任务：只重排「第一个未成功步骤及其之后」的一段，已完成步骤与其产出保持不动。
     * <p>
     * 同步接口（一次模型往返），返回重排后的<b>完整</b>计划，与 {@code plan} 事件同构，前端按同一套渲染。
     * 刻意不自动执行：与「先看计划」保持同一节奏——改动落库后由用户确认，再点「执行计划」走续跑通路。
     */
    @PostMapping("/task/replan")
    public Map<String, Object> replan(@RequestBody ResumeTaskRequest request) {
        Long userId = AuthContext.require().id();
        String scopedId = resolveConversationId(request.conversationId(), userId);
        conversationService.checkAccess(scopedId, userId);
        PlannerRoundHandler.ReplanOutcome outcome = chatService.replan(scopedId);
        if (outcome == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "没有可重新规划的步骤（任务不存在、已全部完成，或模型未能产出新方案）");
        }
        return Map.of("plan", outcome.planJson(), "text", outcome.text());
    }

    // ===== 规划模板：把跑顺的规划步骤骨架沉淀为可复用资产 =====
    // 模板只存「怎么排」（智能体 + 指令 + 依赖），不含「做什么」；套用时由用户填本次目标，
    // 落库成新的 task 后仍走 /task/resume 执行 —— 与「先看计划」「局部重规划」同一条执行通路。

    /** 列出当前用户的规划模板（创建时间倒序）。模板按用户隔离，只回本人的。 */
    @GetMapping("/task/template/list")
    public List<TaskTemplateSummary> listTemplates() {
        return templateService.list(AuthContext.require().id());
    }

    /**
     * 把某次任务的步骤骨架存为模板。
     * <p>
     * 归属校验走「先按 taskId 取任务、再用任务的 conversationId 过会话校验」—— 不传 conversationId 是因为
     * 任务跑完后状态已是 DONE/FAILED，而 {@code findRunning} 只认 RUNNING，按会话定位反而取不到想存的那次。
     * 步骤以<b>库里的当前形态</b>为准（局部重规划会改库），不采信前端传来的快照。
     */
    @PostMapping("/task/template/save")
    public Map<String, Object> saveTemplate(@RequestBody SaveTaskTemplateRequest request) {
        Long userId = AuthContext.require().id();
        Task task = taskService.findById(request.taskId());
        if (task == null) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "任务不存在");
        }
        conversationService.checkAccess(task.getConversationId(), userId);   // 越权会话在这里 404
        TaskTemplate tpl = templateService.saveFromTask(task.getId(), userId, request.name(),
                request.description(), taskService.listSteps(task.getId()));
        if (tpl == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "模板名称不能为空，且任务需要有可保存的步骤");
        }
        return Map.of("id", tpl.getId(), "name", tpl.getName());
    }

    /**
     * 套用模板：按骨架在当前会话落库一个新任务（<b>不自动执行</b>），返回与 {@code plan} 事件同构的计划 JSON，
     * 前端按同一套渲染成计划卡片，用户确认（或逐条改）后点「执行计划」。
     * <p>
     * 与「先看计划」同一节奏的理由：套用后立刻执行等于把「模板对不对」和「这套步骤跑不跑得通」两件事
     * 捆在一次操作里，出错时用户分不清是哪一层的问题；先看再跑，中间还能用就地编辑调。
     */
    @PostMapping("/task/template/apply")
    public Map<String, Object> applyTemplate(@RequestBody ApplyTaskTemplateRequest request) {
        Long userId = AuthContext.require().id();
        String scopedId = resolveConversationId(request.conversationId(), userId);
        conversationService.checkAccess(scopedId, userId);
        TaskTemplate tpl = templateService.findOwned(request.templateId(), userId);
        if (tpl == null) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "模板不存在");
        }
        if (request.goal() == null || request.goal().isBlank()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "请填写本次目标");
        }
        PlannerRoundHandler.ApplyOutcome outcome = chatService.applyTemplate(scopedId, tpl, request.goal().strip());
        if (outcome == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "该模板没有可用步骤");
        }
        templateService.markUsed(tpl.getId());
        return Map.of("plan", outcome.planJson(), "text", outcome.text());
    }

    /** 删除本人的模板；不存在或非本人一律 404（与查询同一口径，防拿 ID 探测他人模板）。 */
    @DeleteMapping("/task/template/{id}")
    public Map<String, Object> deleteTemplate(@PathVariable Long id) {
        if (templateService.delete(id, AuthContext.require().id()) == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "模板不存在");
        }
        return Map.of("ok", true);
    }

    /**
     * 配额闸门：给流式执行体套一层入口检查。
     * <ul>
     *   <li><b>超限</b>：只推一条 {@code error} 事件、<b>不订阅</b>执行体 —— 本轮不会有任何模型调用，
     *       前端按既有错误渲染逻辑红字展示「已用多少 / 上限多少 / 何时重置」，不是静默降级；</li>
     *   <li><b>接近上限</b>：先插一条 {@code progress} 提示再照常执行（只提示、不拦截）；</li>
     *   <li><b>未启用 / 已豁免 / 正常</b>：原样返回执行体。</li>
     * </ul>
     * 注意 {@code body} 是惰性的（{@code Flux.create} 的 lambda 在订阅时才跑），所以「只推 error」这条路上
     * 传进来的执行体不会被启动，不存在白跑一次对话的可能。
     */
    private static Flux<StreamEvent> guarded(QuotaService.QuotaStatus quota, Flux<StreamEvent> body) {
        if (quota.exhausted()) {
            return Flux.just(StreamEvent.error(quota.exhaustedMessage()));
        }
        if (quota.warn()) {
            return Flux.concat(Flux.just(StreamEvent.progress(quota.warnMessage())), body);
        }
        return body;
    }

    /** 是否 ADMIN（配额豁免判定用；角色由 HTTP 线程从登录态读取）。 */
    private static boolean isAdmin(LoginUser user) {
        return user.hasRole(SysRoleCode.ADMIN);
    }

    /**
     * 一次性抽取附件的两类产物（单次遍历）：
     * <ul>
     *   <li>{@code material} —— 解析文本（图片 caption / 文档正文），由 ChatComposer 注入本轮 system prompt，
     *       <b>仅当轮可见、不进会话记忆</b>；</li>
     *   <li>{@code metaJson} —— 展示元数据 JSON（type/filename/storedName/size），写独立列
     *       {@code chat_message.attachments_json}，仅供历史回看，<b>不含正文、不进 LLM</b>。</li>
     * </ul>
     * 无附件时两者均为空串。
     */
    private AttachmentBundle extractAttachments(List<ChatAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return AttachmentBundle.EMPTY;
        StringBuilder material = new StringBuilder(512);
        JSONArray meta = new JSONArray();
        for (int i = 0; i < attachments.size(); i++) {
            ChatAttachment a = attachments.get(i);
            // 产物 1：当轮材料（逐条带序号与文件名标注，便于模型区分多份附件）
            material.append(attachmentLabel(a.type())).append(i + 1);
            if (a.filename() != null && !a.filename().isBlank()) {
                material.append("（").append(a.filename()).append("）");
            }
            material.append("：\n").append(a.content() == null ? "" : a.content()).append("\n\n");
            // 产物 2：展示元数据（不含正文，仅历史渲染所需的最小字段）
            JSONObject o = new JSONObject();
            o.set("type", a.type() == null ? "file" : a.type());
            o.set("filename", a.filename());
            o.set("storedName", a.storedName());
            o.set("size", a.size());
            meta.add(o);
        }
        return new AttachmentBundle(material.toString().strip(), meta.toString());
    }

    /** 附件抽取结果：{@code material}=当轮注入模型的解析文本；{@code metaJson}=落库供历史回看的展示元数据。 */
    private record AttachmentBundle(String material, String metaJson) {
        static final AttachmentBundle EMPTY = new AttachmentBundle("", "");
    }

    /** 附件类型 → 中文标签（图片 / 文档 / 文件）。 */
    private static String attachmentLabel(String type) {
        return switch (type == null ? "" : type) {
            case "image" -> "图片";
            case "text" -> "文档";
            default -> "文件";
        };
    }

    /**
     * 会话 ID 为空时回退到「当前用户的默认会话」。<b>必须带 userId</b>：早先固定退化成公共的
     * {@code "default"}，会话按用户隔离后两个用户会撞进同一个 ID（后者被判 404），故按用户派生。
     */
    private String resolveConversationId(String id, Long userId) {
        return (id == null || id.isBlank()) ? ("default-" + userId) : id;
    }
}
