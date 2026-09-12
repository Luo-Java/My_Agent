package org.luo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.luo.infrastructure.rerank.RerankService;
import org.luo.service.KbSearchService;

/**
 * RAG 检索与精排配置（{@code agent.rag.*}）。
 * <p>
 * 把「召回 → 精排 → 截断」三段式检索的可调参数集中在此，避免散落在 {@link KbSearchService}
 * 的常量里（改效果要重新编译）。分三组：
 * <ul>
 *   <li><b>召回（粗排）</b>：{@code recallK} 候选数、{@code recallMinScore} 召回下限。
 *       召回故意放宽（下限低于精排阈值），把「可能相关」的都捞进来交给精排判断；</li>
 *   <li><b>精排</b>：{@code rerankEnabled} 开关、{@code rerankModel} 模型、{@code rerankUrl} 端点、
 *       {@code rerankMinScore} 精排阈值（DashScope rerank 输出 0~1 的 relevance_score）。
 *       精排不可用（未配置 key / 超时 / 报错）时整体降级为按向量分截断，不影响对话；</li>
 *   <li><b>兜底</b>：{@code topK} 最终注入条数、{@code minScore} 精排不可用时沿用的向量余弦下限
 *       （与精排上线前一致，避免降级后资料质量骤降）；{@code fallbackMaxChunks} 限制
 *       「Chroma 不可用时回退 MySQL 余弦检索」这条降级路径一次最多扫描多少块
 *       （MySQL 里向量以 JSON 文本存储，无界扫描是 OOM 隐患）；</li>
 *   <li><b>查询改写</b>：{@code queryRewriteEnabled} 开关、{@code queryRewriteHistorySize} 参与改写的历史条数。
 *       多轮追问（「那它呢」）直接拿去检索会因指代不清而召回错误内容——<b>精排只能重排已召回的候选，
 *       救不回查错的东西</b>，故在检索前先把最近若干轮历史拼进 LLM 做一次指代消解，产出可独立检索的问题。</li>
 * </ul>
 * 精排复用 {@code spring.ai.openai.api-key}（DashScope 同一把 key），故本类不含 key 字段。
 * 紧凑构造器统一兜底默认值，故访问器返回的包装类型实际保证非空（调用处可直接拆箱）。
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
