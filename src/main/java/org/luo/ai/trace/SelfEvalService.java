package org.luo.ai.trace;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.entity.AgentTrace;
import org.luo.ai.properties.PromptProperties;
import org.luo.ai.properties.SelfEvalProperties;
import org.luo.ai.util.LlmJson;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 线上回答自评（元认知）：让模型对刚产出的那一轮回答打个分，结果补写到 {@code agent_trace.self_eval_*}。
 * <p>
 * <b>补的是哪个缺口</b>：可观测面板此前只能回答「跑成没跑成、快不快」（status / elapsed_ms / token），
 * 回答<b>对不对、有没有答到点上</b>在线上没有任何信号 —— 只有人工翻对话才知道。自评把「运行质量」
 * 从「跑得动吗」扩到「答得对吗」，并让低分轮次可以按会话回看。
 * <p>
 * 两条触发通路，语义不同，不要混：
 * <ul>
 *   <li><b>按比例采样</b>（{@link #maybeEvaluate}）：回复产出后按 {@code sample-rate} 抽检，默认关闭。
 *       它回答的是「这一批对话整体怎么样」——统计意义上的抽样。</li>
 *   <li><b>点踩强制</b>（{@link #evaluateByFeedback}）：用户对某条回复点了 👎 之后对该轮必评，
 *       <b>不看 enabled、不看采样率</b>。它回答的是「用户说这条有问题，模型自己怎么看」——
 *       两个信号对着看才有价值：都对上，说明问题确实可自检；对不上，说明自评本身有盲区。</li>
 * </ul>
 * <p>
 * <b>全部走旁路</b>：独立线程池、失败只记日志、结果 UPDATE 到已落库的追踪行上（不拖慢追踪落库本身）。
 * 自评失败不影响对话、不影响追踪、不影响反馈提交 —— 它只是「可能缺席的一份附加数据」。
 */
@Slf4j
@Service
public class SelfEvalService {

    /** 触发来源：按比例采样。 */
    public static final String TRIGGER_SAMPLE = "SAMPLE";
    /** 触发来源：用户点踩（不看开关与采样率）。 */
    public static final String TRIGGER_FEEDBACK = "FEEDBACK";

    /** 合法分值区间（锚点写在提示词里；这里只做「模型吐了越界值」的兜底）。 */
    private static final int MIN_SCORE = 1;
    private static final int MAX_SCORE = 5;

    /** 问题短语条数上限（防止模型列出十几条把 JSON 列撑爆）。 */
    private static final int MAX_ISSUES = 3;

    /** 单条 issue 与 comment 的字符上限（与 self_eval_json 的用途匹配：够人读懂即可）。 */
    private static final int ISSUE_CHARS = 120;
    private static final int COMMENT_CHARS = 300;

    /** 证据摘要里最多列几个工具名 —— 自评只需要知道「调没调工具、调了什么」，不需要全部明细。 */
    private static final int EVIDENCE_TOOLS = 5;

    private final SelfEvalProperties props;
    private final PromptProperties promptProperties;
    private final ChatModel chatModel;
    private final Executor selfEvalExecutor;
    private final TraceService traceService;
    private final LlmUsageService llmUsageService;

    public SelfEvalService(SelfEvalProperties props, PromptProperties promptProperties, ChatModel chatModel,
                           @Qualifier("selfEvalExecutor") Executor selfEvalExecutor,
                           TraceService traceService, LlmUsageService llmUsageService) {
        this.props = props;
        this.promptProperties = promptProperties;
        this.chatModel = chatModel;
        this.selfEvalExecutor = selfEvalExecutor;
        this.traceService = traceService;
        this.llmUsageService = llmUsageService;
    }

    // ==================================================================
    // 通路一：按比例采样（回复产出后调用，立即返回）
    // ==================================================================

    /**
     * 对刚产出的一轮按比例抽检自评，<b>不阻塞调用方</b>。
     * <p>
     * 三道门槛按「先便宜后昂贵」的顺序判：开关 → 回答长度 → 采样，避免为了抽检去查库/建对象。
     * 任一道不过就直接返回 —— 这是<b>设计如此</b>（抽样本来就该漏掉大部分轮次），不是降级；
     * {@code self_eval_score} 留 NULL，面板会把它计进「未自评」，两者在界面上是分开的。
     * <p>
     * <b>必须等追踪落库完成再提交</b>：自评结果是对 {@code agent_trace} 那一行的 UPDATE，
     * 而追踪是异步落库的 —— 不等的话 UPDATE 会打在还不存在的行上，表现是「自评随机丢失、
     * 日志只有一句未命中」。故这里把自评任务挂在传进来的 future 之后（而不是自己 sleep 硬等）。
     *
     * @param trace      本轮追踪上下文（提供 traceId / 会话 / 用户输入 / 路由结论）
     * @param answer     本轮回复正文（用户实际看到的那一份，未切片前）
     * @param traceSaved 追踪落库完成的凭证（{@code TraceService#saveAsync} 的返回值）；null 视为已完成
     */
    public void maybeEvaluate(RoundTrace trace, String answer, CompletableFuture<Void> traceSaved) {
        if (trace == null || !props.sampleEnabled()) {
            return;
        }
        if (answer == null || answer.strip().length() < props.minAnswerChars()) {
            return;   // 寒暄 / 短确认没有可评的内容，评了只会给面板灌 5 分噪声
        }
        if (!sampled(trace.getTraceId())) {
            return;
        }
        CompletableFuture<Void> gate = traceSaved == null ? CompletableFuture.completedFuture(null) : traceSaved;
        // whenCompleteAsync 指定执行器：动作不能在「完成 future 的那个线程」（追踪池）上跑，
        // 否则一次秒级模型往返会占住追踪落库线程 —— 那正是把追踪推迟的路径。
        gate.whenCompleteAsync((v, ex) ->
                evaluate(trace.getTraceId(), trace.getConversationId(), trace.getUserMessage(), answer,
                        evidenceOf(trace), TRIGGER_SAMPLE), selfEvalExecutor);
    }

    /**
     * 采样判定：由 {@code traceId} 的哈希决定，<b>同一轮永远得到同一结论</b>。
     * <p>
     * 不用 {@code ThreadLocalRandom}：随机采样下「这一轮为什么没被评到」无法解释也无法复现，
     * 而排查采样类问题恰恰要能重放。哈希取模按千分位（0.001 精度足够，且避开浮点比较）。
     */
    private boolean sampled(String traceId) {
        double rate = props.sampleRate();
        if (rate <= 0) return false;
        if (rate >= 1) return true;
        if (traceId == null || traceId.isBlank()) {
            // 没有 traceId 就没有稳定的采样依据：此时用随机数会让同一轮前后两次判定不同，
            // 反而不如按「本次随机」处理 —— 但必须出声，因为这属于不该发生的情况。
            log.warn("自评采样：追踪缺少 traceId，改用随机判定（本次）");
            return ThreadLocalRandom.current().nextDouble() < rate;
        }
        int threshold = (int) Math.round(rate * 1000);
        return Math.floorMod(traceId.hashCode(), 1000) < threshold;
    }

    // ==================================================================
    // 通路二：点踩强制自评（不看开关与采样率）
    // ==================================================================

    /**
     * 用户对某条回复点踩后，强制对那一轮自评。
     * <p>
     * <b>为什么不受 {@code enabled} 约束</b>：这时候花的那一次模型调用有明确目的（用户刚说这条有问题），
     * 且是低频事件。关掉采样是为了省掉「无人关心时的自动开销」，不是为了在用户主动标记后仍然视而不见。
     * 这一点在配置类注释里也写了一遍 —— 「默认关闭」不能被读成「零成本」。
     * <p>
     * 追踪按「会话 + 用户输入快照」匹配（{@link TraceService#findLatestByUserMessage}）。匹配不到就放弃
     * 并记 WARN：追踪是旁路数据，可能因队列满被丢弃，也可能因超长输入截断而无法精确匹配。
     * <b>不因此让反馈提交失败</b> —— 反馈本身是用户表达，不该被一条附加数据拖住。
     *
     * @param conversationId 会话 ID
     * @param userInput      那一轮的用户输入快照（反馈行上的 {@code user_input}）
     * @param answer         被评价的那条助手回复正文
     */
    public void evaluateByFeedback(String conversationId, String userInput, String answer) {
        if (conversationId == null || conversationId.isBlank()) return;
        if (answer == null || answer.strip().isEmpty()) return;
        AgentTrace trace = traceService.findLatestByUserMessage(conversationId, userInput);
        if (trace == null) {
            log.warn("点踩自评：匹配不到那一轮的追踪记录，跳过（反馈本身已提交）：会话={}", conversationId);
            return;
        }
        submit(trace.getTraceId(), conversationId, trace.getUserMessage(), answer,
                evidenceOf(trace), TRIGGER_FEEDBACK);
    }

    // ==================================================================
    // 执行
    // ==================================================================

    /** 提交到自评专用线程池（队列满则放弃本次 —— 自评可缺席，不抢占对话资源）。 */
    private void submit(String traceId, String conversationId, String question, String answer,
                        String evidence, String trigger) {
        try {
            selfEvalExecutor.execute(() -> evaluate(traceId, conversationId, question, answer, evidence, trigger));
        } catch (RejectedExecutionException e) {
            log.warn("自评任务提交被拒绝，本次跳过：traceId={}，原因={}", traceId, e.getMessage());
        }
    }

    /** 一次自评往返：渲染提示词 → 调用模型 → 解析 → 补写追踪。任何异常都只记日志。 */
    private void evaluate(String traceId, String conversationId, String question, String answer,
                          String evidence, String trigger) {
        try {
            String template = promptProperties.selfEvalSystem();
            if (template == null || template.isBlank()) {
                log.warn("自评提示词为空（prompts.yaml 的 self-eval-system），跳过本轮自评");
                return;
            }
            String prompt = PromptProperties.render(template, Map.of(
                    "question", orDash(question),
                    "answer", chop(answer, props.maxAnswerChars()),
                    "evidence", orDash(evidence)));
            // 整段（指令 + 内容）作为一条 user 消息发送：模板本身就含内容槽位，
            // 再拆成 system + user 反而要把同一份文本切两半，得不偿失。
            ChatResponse response = chatModel.call(new Prompt(List.of(new UserMessage(prompt))));
            llmUsageService.recordAsync("SELF_EVAL", conversationId, traceId, response);
            String text = textOf(response);
            Eval eval = parse(text);
            if (eval == null) {
                log.warn("自评未产出可解析结果，跳过：traceId={}，原始返回={}", traceId, chop(text, 200));
                return;
            }
            traceService.updateSelfEval(traceId, eval.score, toJson(eval, trigger));
        } catch (Exception e) {
            log.warn("自评失败（忽略）：traceId={}，原因={}", traceId, e.getMessage());
        }
    }

    /**
     * 组装「本轮可核对的依据摘要」。
     * <p>
     * 只给<b>摘要</b>（工具名、引用条数），不给原文 —— 两个原因：一是全文进 prompt 会让自评的 token 成本
     * 追上正式回答本身；二是自评提示词已明确告知「你拿不到依据原文」，让它据此保守判断，
     * 好过给它一大堆原文却让它误判「我看过了」。
     */
    private static String evidenceOf(RoundTrace trace) {
        StringBuilder sb = new StringBuilder();
        sb.append("处理方来源：").append(routeLabel(trace.getRouteSource()));
        sb.append("\n本轮状态：").append("error".equals(trace.getStatus()) ? "执行出错" : "正常产出");
        int tools = trace.toolCallCount();
        if (tools > 0) {
            sb.append("\n工具调用：共 ").append(tools).append(" 次");
            List<RoundTrace.ToolCall> calls = trace.getToolCalls();
            List<String> names = calls.stream().limit(EVIDENCE_TOOLS).map(RoundTrace.ToolCall::name).toList();
            sb.append("（").append(String.join("、", names));
            if (tools > EVIDENCE_TOOLS) sb.append(" 等");
            sb.append("）");
        } else {
            sb.append("\n工具调用：无");
        }
        sb.append("\n知识库引用：");
        sb.append(trace.citationCount() > 0 ? trace.citationCount() + " 条已标注来源" : "无");
        return sb.toString();
    }

    /** 依据摘要里给点踩自评用的版本：从已落库的追踪行还原（拿不到 RoundTrace 对象时）。 */
    private static String evidenceOf(AgentTrace trace) {
        StringBuilder sb = new StringBuilder();
        sb.append("处理方来源：").append(routeLabel(trace.getRouteSource()));
        sb.append("\n本轮状态：").append("error".equals(trace.getStatus()) ? "执行出错" : "正常产出");
        sb.append("\n工具调用：").append(toolNamesOf(trace));
        sb.append("\n知识库引用：");
        Integer hits = trace.getKbHitCount();
        sb.append(hits != null && hits > 0 ? hits + " 条已标注来源" : "无");
        return sb.toString();
    }

    /** 从追踪行里的工具调用 JSON 取工具名摘要（解析失败按「无法确认」处理，不假装没有调用）。 */
    private static String toolNamesOf(AgentTrace trace) {
        String json = trace.getToolCalls();
        if (json == null || json.isBlank()) {
            return "无";
        }
        try {
            JSONArray arr = JSONUtil.parseArray(json);
            if (arr.isEmpty()) return "无";
            List<String> names = arr.stream().limit(EVIDENCE_TOOLS)
                    .map(o -> String.valueOf(((JSONObject) o).getStr("name", "未知"))).toList();
            String suffix = arr.size() > EVIDENCE_TOOLS ? " 等" : "";
            return "共 " + arr.size() + " 次（" + String.join("、", names) + suffix + "）";
        } catch (Exception e) {
            return "无法确认（明细解析失败）";
        }
    }

    /** route_source 的中文标签（未知值原样返回，避免显示成空白）。 */
    private static String routeLabel(String routeSource) {
        if (routeSource == null || routeSource.isBlank()) return "未知";
        return switch (routeSource) {
            case "BOUND" -> "会话绑定的智能体";
            case "ROUTE" -> "智能路由命中";
            case "NONE" -> "通用助手";
            case "PLAN" -> "规划编排";
            case "REVIEW" -> "并行评审";
            default -> routeSource;
        };
    }

    /** 取模型返回正文（结构缺任一环都返回 null，由调用方按「没产出」处理）。 */
    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    /**
     * 解析自评结果；任何一处不合规都返回 {@code null}（调用方按「本次没评出来」处理）。
     * <p>
     * <b>越界分值夹到区间内而不是丢弃</b>：模型偶尔会吐 0 或 6，那是「打分体系没对齐」而不是「这条无法评价」，
     * 丢掉整份结果会连 comment 一起损失。但夹值会记 WARN —— 否则「模型总在打 0 分」这件事永远看不见。
     */
    private static Eval parse(String text) {
        String json = LlmJson.extractObject(text);
        if (json == null) {
            return null;
        }
        try {
            JSONObject o = JSONUtil.parseObj(json);
            Integer raw = o.getInt("score");
            if (raw == null) {
                log.warn("自评结果缺少 score 字段，跳过");
                return null;
            }
            int score = raw;
            if (score < MIN_SCORE || score > MAX_SCORE) {
                log.warn("自评分数越界（{}），已夹到 [{},{}] 区间", score, MIN_SCORE, MAX_SCORE);
                score = Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
            }
            boolean answered = Boolean.TRUE.equals(o.getBool("answered", Boolean.TRUE));
            boolean grounded = Boolean.TRUE.equals(o.getBool("grounded", Boolean.FALSE));
            return new Eval(score, answered, grounded, issuesOf(o), chop(o.getStr("comment"), COMMENT_CHARS));
        } catch (Exception e) {
            log.warn("自评结果不是合法 JSON，跳过：{}", e.getMessage());
            return null;
        }
    }

    /** issues 数组 → 短语列表（非数组 / 空元素一律忽略；条数与单条长度都设上限）。 */
    private static List<String> issuesOf(JSONObject o) {
        JSONArray arr = o.getJSONArray("issues");
        if (arr == null || arr.isEmpty()) {
            return List.of();
        }
        return arr.stream()
                .map(String::valueOf)
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .limit(MAX_ISSUES)
                .map(s -> chop(s, ISSUE_CHARS))
                .toList();
    }

    /** 自评结果 → 落库 JSON；{@code at} 用于面板上区分「什么时候评的」（相对那一轮本身是后补的）。 */
    private static String toJson(Eval eval, String trigger) {
        JSONObject o = new JSONObject();
        o.set("score", eval.score);
        o.set("answered", eval.answered);
        o.set("grounded", eval.grounded);
        o.set("issues", eval.issues);
        o.set("comment", eval.comment);
        o.set("trigger", trigger);
        o.set("at", LocalDateTime.now().toString());
        return o.toString();
    }

    private static String orDash(String s) {
        return (s == null || s.isBlank()) ? "（无）" : s;
    }

    private static String chop(String s, int limit) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= limit ? t : t.substring(0, limit) + "…";
    }

    /** 一次自评的结构化结果。 */
    private record Eval(int score, boolean answered, boolean grounded, List<String> issues, String comment) {
    }
}
