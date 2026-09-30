package org.luo.ai.trace;

import lombok.extern.slf4j.Slf4j;
import org.luo.ai.entity.LlmUsage;
import org.luo.ai.mapper.LlmUsageMapper;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;

/**
 * 裸 LLM 调用成本采集器：把<b>不经 Advisor</b>的 {@code ChatModel.call()}（路由判定 / 参数抽取 /
 * 查询改写 / 视觉识别 / 记忆合并）的 token 用量记一条 {@link LlmUsage} 流水，补全「全量成本」口径。
 * <p>
 * 与 {@link TraceService} 同款旁路语义（三条原则一致）：
 * ① <b>异步</b>——走 {@code traceExecutor}，绝不挡在调用返回之前；
 * ② <b>旁路</b>——不参与任何对话逻辑，删掉整张表对话照常运行；
 * ③ <b>不抛错</b>——提交与落库全链路 try/catch，失败只记日志（宁可丢成本流水也不影响主流程）。
 * <p>
 * <b>为什么单独一张表</b>：agent_trace 的 token 只覆盖「正式回答 + 工具循环」（由 RoundTraceAdvisor 采集）；
 * 路由 / 参数抽取 / 查询改写发生在正式回答之前，视觉识别甚至是独立请求（早于 trace 建立）、记忆合并是
 * afterReply 异步——它们都拿不到当轮 RoundTrace，硬塞进 agent_trace 会导致时序混乱。故单列一张流水表，
 * 用 {@code purpose} 维度区分用途，成本看板据此做「真正全量」的聚合与拆解。
 */
@Slf4j
@Service
public class LlmUsageService {

    private final LlmUsageMapper mapper;
    private final Executor traceExecutor;

    public LlmUsageService(LlmUsageMapper mapper, @Qualifier("traceExecutor") Executor traceExecutor) {
        this.mapper = mapper;
        this.traceExecutor = traceExecutor;
    }

    /**
     * 异步记录一次裸调用的成本。调用方在拿到 {@link ChatResponse} 之后调用，本方法立即返回。
     *
     * @param purpose        用途（ROUTE/CLARIFY/REWRITE/PLAN/VISION/MEMORY_MERGE）
     * @param conversationId 所属会话（可空，如视觉识别独立请求）
     * @param traceId        本轮追踪 ID（可空，如视觉识别早于 trace 建立）
     * @param model          实际模型名（可空，未显式指定时取默认）
     * @param response       本次调用响应（为 null 或 usage 缺失时跳过，不落空流水）
     */
    public void recordAsync(String purpose, String conversationId, String traceId, String model,
                            ChatResponse response) {
        if (purpose == null || purpose.isBlank() || response == null) return;
        var metadata = response.getMetadata();
        var usage = metadata == null ? null : metadata.getUsage();
        if (usage == null) return;
        Integer prompt = usage.getPromptTokens();
        Integer completion = usage.getCompletionTokens();
        Integer total = usage.getTotalTokens();
        try {
            traceExecutor.execute(() -> persist(purpose, conversationId, traceId, model,
                    prompt, completion, total));
        } catch (Exception e) {
            log.debug("成本流水提交失败（忽略）：purpose={}，原因={}", purpose, e.getMessage());
        }
    }

    /**
     * 便捷重载：模型名从响应元数据自动取，避免各调用点重复解析。conversationId / traceId 拿不到传 null。
     */
    public void recordAsync(String purpose, String conversationId, String traceId, ChatResponse response) {
        if (response == null) return;
        String model = null;
        try {
            var metadata = response.getMetadata();
            if (metadata != null && metadata.getModel() != null && !metadata.getModel().isBlank()) {
                model = metadata.getModel();
            }
        } catch (Exception e) {
            // 取不到模型名不影响成本记录（model 列本就可空）
        }
        recordAsync(purpose, conversationId, traceId, model, response);
    }

    /** 真正落库（traceExecutor 线程上执行，任何异常只记日志）。 */
    private void persist(String purpose, String conversationId, String traceId, String model,
                         Integer prompt, Integer completion, Integer total) {
        try {
            LlmUsage u = new LlmUsage();
            u.setTraceId(traceId);
            u.setConversationId(conversationId);
            u.setPurpose(purpose);
            u.setModel(model);
            u.setPromptTokens(prompt == null ? 0 : prompt);
            u.setCompletionTokens(completion == null ? 0 : completion);
            u.setTotalTokens(total == null ? 0 : total);
            u.setCreatedAt(LocalDateTime.now());
            mapper.insert(u);
            log.debug("成本流水落库：purpose={}，会话={}，token={}", purpose, conversationId, total);
        } catch (Exception e) {
            log.warn("成本流水落库失败（忽略）：purpose={}，原因={}", purpose, e.getMessage());
        }
    }
}
