package org.luo.ai.agent.handler;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.chat.ChatComposer;
import org.luo.ai.entity.Agent;
import org.luo.ai.entity.Conversation;
import org.luo.ai.properties.PromptProperties;
import org.luo.ai.properties.ReviewProperties;
import org.luo.ai.service.AgentService;
import org.luo.ai.service.CrossSessionSearchService;
import org.luo.ai.service.KbSearchService;
import org.luo.ai.trace.LlmUsageService;
import org.luo.ai.trace.RoundTrace;
import org.luo.ai.util.LlmJson;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 并行评审策略：同一问题让几个不同视角的智能体各答一遍，再由裁决者<b>综合</b>成最终答案。
 * <p>
 * 红线：与规划（{@link PlannerRoundHandler}）同属「编排形态」故会话开关互斥；候选绕开记忆 Advisor，
 * <b>不进记忆也不落库</b>，只有综合结论回写（{@link RoundResult#needSaveExchange()}）；候选不足 2 个或全部失败时
 * 播报原因并交回普通对话策略，<b>不静默退化成单模型回答、也不编一个答案</b>。成本约 (候选数 + 1) 次普通对话。
 */
@Slf4j
@Service
public class ReviewRoundHandler implements RoundHandler {

    /** 追踪用：处理方来源枚举值（与 {@code agent_trace.route_source} 列注释一致）。 */
    private static final String SRC_REVIEW = "REVIEW";

    /** 空进度回调（同步接口无事件通道）。 */
    private static final Consumer<String> NO_PROGRESS = text -> {
    };

    /** 单个候选写入 {@code review} 事件时的展示长度上限（超出截断并标注）。 */
    private static final int CANDIDATE_DISPLAY_CHARS = 2000;

    private final AgentService agentService;
    private final ChatComposer composer;
    private final PromptProperties promptProperties;
    private final ReviewProperties props;
    private final CrossSessionSearchService crossSessionSearchService;
    private final ChatMemory chatMemory;
    private final LlmUsageService llmUsageService;
    /** 候选并行作答线程池（与规划步骤共用；两者互斥，不会同时占满）。 */
    private final Executor stepExecutor;

    public ReviewRoundHandler(AgentService agentService,
                              ChatComposer composer,
                              PromptProperties promptProperties,
                              ReviewProperties props,
                              CrossSessionSearchService crossSessionSearchService,
                              ChatMemory chatMemory,
                              LlmUsageService llmUsageService,
                              @Qualifier("plannerStepExecutor") Executor stepExecutor) {
        this.agentService = agentService;
        this.composer = composer;
        this.promptProperties = promptProperties;
        this.props = props;
        this.crossSessionSearchService = crossSessionSearchService;
        this.chatMemory = chatMemory;
        this.llmUsageService = llmUsageService;
        this.stepExecutor = stepExecutor;
    }

    @Override
    public RoundResult handle(Conversation conv, String conversationId, String message, String material,
                              Consumer<String> progress, RoundTrace trace) {
        Consumer<String> p = progress == null ? NO_PROGRESS : progress;
        if (message == null || message.isBlank()) {
            return RoundResult.fallback();
        }
        List<Agent> candidates = pickCandidates(conv, message);
        if (candidates.size() < 2) {
            // 明确播报而不是悄悄变成单模型回答：用户开了评审，就得知道它这次没生效、以及为什么
            p.accept("⚖ 可用候选不足 2 个（需要库里至少 2 个相关智能体），本轮改用普通回答");
            log.info("并行评审未生效：候选 {} 个（会话={}）", candidates.size(), conversationId);
            return RoundResult.fallback();
        }
        if (trace != null) {
            trace.route(SRC_REVIEW, null);
        }
        p.accept("⚖ 已选定 " + candidates.size() + " 个候选并行作答：" + names(candidates));

        // 素材各取一次，候选与裁决共用（成本 + 公平性，见类注释）
        KbSearchService.KbContext kb = composer.buildKbContext(
                conv == null ? null : conv.getRagEnabled(), null, message);
        CrossSessionSearchService.Recall recall = conv == null
                ? CrossSessionSearchService.Recall.EMPTY
                : crossSessionSearchService.recall(conversationId, message, conv.getUserId(), p);

        List<Candidate> answers = answerInParallel(candidates, conv, conversationId, message, material, kb, recall, trace, p);
        String recallJson = recall.isEmpty() ? null : recall.json();

        if (answers.isEmpty()) {
            log.warn("并行评审：全部候选作答失败（会话={}）", conversationId);
            return RoundResult.answer("并行评审失败：所有候选智能体都没有产出有效回答，请稍后重试。");
        }
        if (answers.size() == 1) {
            // 播报而非静默：用户需要知道这次没有真正「评审」，只是拿到了唯一成功的那份
            p.accept("⚖ 仅 1 个候选成功作答，直接采用它的回答（未做综合）");
            String reply = answers.get(0).reply();
            saveExchange(conversationId, message, reply);
            return RoundResult.reviewed(reply, kb.citations(), reviewJson(answers, false)).withRecall(recallJson);
        }

        p.accept("⚖ 正在综合 " + answers.size() + " 份作答…");
        String finalReply;
        boolean judged = true;
        try {
            finalReply = judge(answers, message, conversationId, kb, recall);
        } catch (Exception e) {
            // 裁决失败不让整轮白跑：播报后采用第一份作答（信息没丢，用户也知道发生了什么）
            judged = false;
            log.warn("并行评审：裁决失败，改用第一份候选作答：{}", e.getMessage());
            p.accept("⚖ 综合失败，采用第 1 份作答");
            finalReply = answers.get(0).reply();
        }
        saveExchange(conversationId, message, finalReply);
        return RoundResult.reviewed(finalReply, kb.citations(), reviewJson(answers, judged)).withRecall(recallJson);
    }

    // ==================== ① 选候选 ====================

    /**
     * 选出本轮候选：显式绑定的智能体固定入选，其余由 LLM 按相关性挑。
     * <p>
     * 库里不足 2 个智能体时直接返回空 —— 不需要调模型（一个候选构不成「评审」）。
     */
    private List<Agent> pickCandidates(Conversation conv, String message) {
        List<Agent> all = agentService.listAgents();
        if (all == null || all.size() < 2) return List.of();

        LinkedHashMap<String, Agent> picked = new LinkedHashMap<>();
        if (conv != null && conv.getAgentId() != null) {
            Agent bound = agentService.getAgent(conv.getAgentId());
            if (bound != null && bound.getAgentCode() != null) {
                picked.put(bound.getAgentCode(), bound);
            }
        }
        int need = props.candidateCount() - picked.size();
        if (need > 0) {
            List<Agent> pool = new ArrayList<>();
            for (Agent a : all) {
                if (a != null && a.getAgentCode() != null && !picked.containsKey(a.getAgentCode())) {
                    pool.add(a);
                }
            }
            for (Agent a : chooseByLlm(pool, message, need)) {
                picked.putIfAbsent(a.getAgentCode(), a);
            }
        }
        return new ArrayList<>(picked.values());
    }

    /** 一次 LLM 调用挑候选（失败/解析不出即返回空表：候选不足会在上层明确播报，不在此处编造名单）。 */
    private List<Agent> chooseByLlm(List<Agent> pool, String message, int need) {
        if (pool.isEmpty()) return List.of();
        String template = promptProperties.reviewPickerSystem();
        if (template == null || template.isBlank()) {
            log.warn("并行评审的选角模板为空（请检查 prompts.yaml 的 agent.prompt.review-picker-system）");
            return List.of();
        }
        String system = PromptProperties.render(template, Map.of(
                "count", String.valueOf(need),
                "agentList", agentService.buildAgentListText(pool)));
        try {
            ChatResponse response = composer.internalChatClient().prompt()
                    .system(system)
                    .user(message)
                    .call().chatResponse();
            llmUsageService.recordAsync("REVIEW", null, null, response);
            String text = text(response);
            return parseCodes(text, pool);
        } catch (Exception e) {
            log.warn("并行评审：候选挑选调用失败，本轮不评审：{}", e.getMessage());
            return List.of();
        }
    }

    /** 解析 {@code {"agentCodes":[...]}}，只保留确实在候选池里的编码（模型可能编出不存在的编码）。 */
    private static List<Agent> parseCodes(String text, List<Agent> pool) {
        if (text == null || text.isBlank()) return List.of();
        String json = LlmJson.extractObject(text);
        if (json == null) return List.of();
        try {
            JSONArray arr = JSONUtil.parseObj(json).getJSONArray("agentCodes");
            if (arr == null || arr.isEmpty()) return List.of();
            Map<String, Agent> byCode = new LinkedHashMap<>();
            for (Agent a : pool) {
                byCode.put(a.getAgentCode(), a);
            }
            List<Agent> out = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                Agent a = byCode.get(arr.getStr(i));
                if (a != null) out.add(a);   // 不存在的编码静默丢弃（不是错误，模型偶尔会写错）
            }
            return out;
        } catch (Exception e) {
            log.warn("并行评审：候选挑选结果不是合法 JSON，本轮不评审：{}", e.getMessage());
            return List.of();
        }
    }

    // 从可能被 ``` 包裹 / 带前后缀的文本里截出 JSON 对象的逻辑已抽到 LlmJson.extractObject
    // （自评也用它，两处各写一份迟早会出现细微差异，而差异表现为「某个功能偶尔解析失败」）。

    // ==================== ② 并行作答 ====================

    /** 一个候选一次模型往返；成功且非空才计入结果（空回答与失败同级处理）。 */
    private record Pending(Agent agent, CompletableFuture<Candidate> future) {
    }

    /** 一个候选的作答（只保留成功的）。 */
    private record Candidate(Agent agent, String reply) {
    }

    /**
     * 并行让各候选作答，按 {@code agent.review.timeout-seconds} 逐条等待。
     * <p>
     * 超时/失败只让<b>该候选</b>弃权并记 WARN，不拖垮整轮；「成功不足 2 个」由调用方播报处理。
     */
    private List<Candidate> answerInParallel(List<Agent> candidates, Conversation conv, String conversationId,
                                             String message, String material, KbSearchService.KbContext kb,
                                             CrossSessionSearchService.Recall recall, RoundTrace trace,
                                             Consumer<String> p) {
        List<Pending> pending = new ArrayList<>(candidates.size());
        for (Agent agent : candidates) {
            try {
                pending.add(new Pending(agent, CompletableFuture.supplyAsync(
                        () -> new Candidate(agent, answer(agent, conv, conversationId, message, material, kb, recall, trace)),
                        stepExecutor)));
            } catch (RuntimeException e) {
                // 线程池拒绝：该候选直接弃权（其余候选不受影响）
                log.warn("并行评审：候选「{}」提交失败，视为弃权：{}", agent.getName(), e.getMessage());
            }
        }
        List<Candidate> done = new ArrayList<>(pending.size());
        for (Pending item : pending) {
            try {
                Candidate c = item.future().get(props.timeout(), TimeUnit.SECONDS);
                if (c != null && c.reply() != null && !c.reply().isBlank()) {
                    done.add(c);
                } else {
                    log.warn("并行评审：候选「{}」返回空内容，视为弃权", item.agent().getName());
                    p.accept("⚖ 候选「" + item.agent().getName() + "」未产出内容，已跳过");
                }
            } catch (TimeoutException e) {
                item.future().cancel(true);
                log.warn("并行评审：候选「{}」作答超时（{}s），已弃权", item.agent().getName(), props.timeout());
                p.accept("⚖ 候选「" + item.agent().getName() + "」超时，已跳过");
            } catch (Exception e) {
                log.warn("并行评审：候选「{}」作答失败，已弃权：{}", item.agent().getName(), rootMessage(e));
                p.accept("⚖ 候选「" + item.agent().getName() + "」作答失败，已跳过");
            }
        }
        return done;
    }

    /**
     * 单个候选作答：它自己的人设 + 共用素材（长期记忆 / 知识库 / 跨会话回忆 / 附件）+ 本轮问题。
     * <p>
     * 走<b>无记忆</b>客户端（{@link ChatComposer#internalChatClient()}）：候选的草稿不该进会话历史。
     * 工具与模型参数经 {@link ChatComposer#decorateRequest} 装配 —— 与普通对话同一套，故候选也能查库画图。
     */
    private String answer(Agent agent, Conversation conv, String conversationId, String message, String material,
                          KbSearchService.KbContext kb, CrossSessionSearchService.Recall recall, RoundTrace trace) {
        String system = composer.buildRoundSystemPrompt(agent, conv, kb.text(), recall.text(), material, null);
        ChatClient.ChatClientRequestSpec spec = composer.internalChatClient().prompt()
                .system(system)
                .user(message);
        spec = composer.decorateRequest(spec, agent, trace, conversationId);
        ChatResponse response = spec.call().chatResponse();
        llmUsageService.recordAsync("REVIEW", conversationId, null, response);
        return text(response);
    }

    // ==================== ③ 裁决综合 ====================

    /** 把各候选作答（带署名）交给裁决者综合；调用失败由调用方兜底（不在此处编答案）。 */
    private String judge(List<Candidate> answers, String message, String conversationId,
                         KbSearchService.KbContext kb, CrossSessionSearchService.Recall recall) {
        String template = promptProperties.reviewJudgeSystem();
        if (template == null || template.isBlank()) {
            throw new IllegalStateException("裁决模板为空（请检查 prompts.yaml 的 agent.prompt.review-judge-system）");
        }
        // 裁决者同样拿到共用素材：它需要基于同一份资料判断哪一家的说法站得住
        String system = template + kb.text() + recall.text();
        StringBuilder user = new StringBuilder(1024);
        user.append("用户的问题：\n").append(message).append("\n\n各智能体的作答：\n\n");
        for (int i = 0; i < answers.size(); i++) {
            Candidate c = answers.get(i);
            user.append("【候选 ").append(i + 1).append("｜").append(c.agent().getName()).append("】\n")
                    .append(c.reply()).append("\n\n");
        }
        user.append("请综合以上作答，给出最终答案。");
        ChatResponse response = composer.internalChatClient().prompt()
                .system(system)
                .user(user.toString())
                .call().chatResponse();
        llmUsageService.recordAsync("REVIEW", conversationId, null, response);
        String out = text(response);
        if (out == null || out.isBlank()) {
            throw new IllegalStateException("裁决者未返回内容");
        }
        return out;
    }

    // ==================== 收尾 ====================

    /**
     * 显式补写「用户原话 → 最终回复」：整条评审链路走无记忆客户端，Advisor 不会自动落库。
     * 失败只记日志（记忆少一轮不影响本轮答案已经送达）。
     */
    private void saveExchange(String conversationId, String userMessage, String assistantReply) {
        if (assistantReply == null || assistantReply.isBlank()) return;
        try {
            chatMemory.add(conversationId, List.of(
                    new UserMessage(userMessage),
                    new AssistantMessage(assistantReply)));
            log.debug("并行评审记忆写入：会话={}", conversationId);
        } catch (Exception e) {
            log.error("并行评审记忆写入失败：会话={}", conversationId, e);
        }
    }

    /** 候选作答的展示载荷（供前端渲染候选卡片）；单条超长时截断并标注。 */
    private static String reviewJson(List<Candidate> answers, boolean judged) {
        JSONArray arr = new JSONArray(answers.size());
        for (int i = 0; i < answers.size(); i++) {
            Candidate c = answers.get(i);
            JSONObject o = new JSONObject();
            o.set("index", i + 1);
            o.set("agentCode", c.agent().getAgentCode());
            o.set("agentName", c.agent().getName());
            o.set("reply", truncate(c.reply()));
            arr.add(o);
        }
        JSONObject root = new JSONObject();
        root.set("candidates", arr);
        root.set("judged", judged);
        return root.toString();
    }

    /** 单条候选的展示截断：保留前段 + 显式省略号（静默截断会让「答案被吃掉」变成查不出原因的谜）。 */
    private static String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= CANDIDATE_DISPLAY_CHARS) return text;
        return text.substring(0, CANDIDATE_DISPLAY_CHARS) + "\n…（候选内容过长，此处已截断）";
    }

    /** 取模型输出文本（空/缺结果一律 null，由调用方按「失败」处理）。 */
    private static String text(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    /** 候选名拼接（进度播报用）。 */
    private static String names(List<Agent> agents) {
        StringBuilder sb = new StringBuilder();
        for (Agent a : agents) {
            if (!sb.isEmpty()) sb.append(" / ");
            sb.append(a.getName());
        }
        return sb.toString();
    }

    /** 取根因消息（{@code ExecutionException} 会包一层，直接取 message 只看得到包装类名）。 */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
