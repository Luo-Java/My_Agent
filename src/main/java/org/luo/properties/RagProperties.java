package org.luo.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.luo.infrastructure.rerank.RerankService;

/**
 * RAG 检索与精排配置（{@code agent.rag.*}）：三段式「粗排召回 → 精排 → 截断」的可调参数。
 * <p>
 * 召回下限故意放宽，把「可能相关」的都交给精排判断；精排不可用（无 key / 超时 / 报错）时整体降级为
 * 按向量分截断，不影响对话。注意 {@code rerankMinScore}（0~1）与 {@code minScore}（余弦）是两套
 * <b>独立尺度</b>，不可互相换算。精排复用 {@code spring.ai.openai.api-key}，故本类不含 key 字段。
 * 紧凑构造器统一兜底默认值，访问器返回的包装类型实际保证非空。
 *
 * @param rerankEnabled           是否启用精排；false=只用向量分（退化为改造前行为）
 * @param rerankModel             DashScope 精排模型，默认 gte-rerank-v2
 * @param rerankUrl               DashScope 原生 text-rerank 端点（注意：不在 OpenAI 兼容路径下）
 * @param rerankMinScore          精排相关度下限（0~1），低于该值的候选在精排后丢弃
 * @param recallK                 粗排召回候选条数（交给精排的候选量，建议 3~5 倍于 topK）
 * @param topK                    最终注入系统提示词的知识块条数
 * @param recallMinScore          召回阶段的向量余弦下限（放宽用，明显低于 minScore）
 * @param minScore                精排不可用时沿用的向量余弦下限（严格档）
 * @param timeoutSeconds          单次精排调用的编排层超时（秒），超时降级为向量分
 * @param queryRewriteEnabled     是否启用多轮查询改写（指代消解）；首轮无历史时自动跳过，不产生调用
 * @param queryRewriteHistorySize 参与改写的最近历史条数上限（控制改写调用的 token 成本）
 * @param fallbackMaxChunks       回退 MySQL 余弦检索时单次扫描的块数上限（有界化，防 OOM）
 */
@ConfigurationProperties(prefix = "agent.rag")
public record RagProperties(Boolean rerankEnabled, String rerankModel, String rerankUrl, Double rerankMinScore,
                            Integer recallK, Integer topK, Double recallMinScore, Double minScore,
                            Integer timeoutSeconds, Boolean queryRewriteEnabled, Integer queryRewriteHistorySize,
                            Integer fallbackMaxChunks) {

    public RagProperties {
        if (rerankEnabled == null) rerankEnabled = true;
        if (rerankModel == null || rerankModel.isBlank()) rerankModel = "gte-rerank-v2";
        if (rerankUrl == null || rerankUrl.isBlank()) {
            rerankUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
        }
        if (rerankMinScore == null || rerankMinScore < 0) rerankMinScore = 0.20;
        if (recallK == null || recallK <= 0) recallK = 20;
        if (topK == null || topK <= 0) topK = 3;
        if (recallMinScore == null || recallMinScore < 0) recallMinScore = 0.10;
        if (minScore == null || minScore < 0) minScore = 0.25;
        if (timeoutSeconds == null || timeoutSeconds <= 0) timeoutSeconds = 5;
        if (queryRewriteEnabled == null) queryRewriteEnabled = true;
        if (queryRewriteHistorySize == null || queryRewriteHistorySize <= 0) queryRewriteHistorySize = 6;
        if (fallbackMaxChunks == null || fallbackMaxChunks <= 0) fallbackMaxChunks = 2000;
    }

    /** 是否启用多轮查询改写（静态开关；「首轮无历史」由调用方按历史是否为空再跳过一次）。 */
    public boolean queryRewriteOn() {
        return Boolean.TRUE.equals(queryRewriteEnabled);
    }

    /** 单次精排超时毫秒数（Hutool HttpRequest.timeout 用）。 */
    public int timeoutMillis() {
        return timeoutSeconds * 1000;
    }

    /**
     * 静态配置层面是否需要走精排（开关打开且端点非空）。
     * 「本机是否有可用 api-key / 连接是否可用」由 {@link RerankService#available()} 判断，
     * 拆开是为了让日志能区分「未开启精排」与「未配置 key 而跳过」。
     */
    public boolean rerankOn() {
        return Boolean.TRUE.equals(rerankEnabled) && rerankUrl != null && !rerankUrl.isBlank();
    }
}
