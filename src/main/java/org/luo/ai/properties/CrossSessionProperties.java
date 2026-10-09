package org.luo.ai.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 跨会话搜索配置（{@code agent.cross-session.*}）：在<b>本人其他会话</b>的历史消息里做召回。
 * <p>
 * 核心取舍：<b>不走向量副本</b> —— 会话消息逐轮写入，给每条存向量意味着每轮多一次 embedding（成本翻倍）
 * 且要新增一套向量副本维护链路；而现有向量设施围绕 {@code kb_chunk} 建，混进会话消息会让「知识库命中」与
 * 「历史回忆」互相干扰。语义能力改由「提词扩展（召回侧，见 prompts.yaml 的 cross-session-query-system）
 * + 精排重排（排序侧，{@code semanticRerank}）」两头承担。代价是召回仍以字面驱动：用户当时若用了完全不同的
 * 措辞、且该措辞未被提词模型想到，仍会漏 —— 要突破得先解决上面那条副本链路的成本与一致性。
 * 归属隔离：{@code chat_message} 没有 {@code user_id}，靠 {@code JOIN conversation} 判定，且<b>强制排除当前会话</b>
 * （当前内容已在记忆窗口里，再召回只会重复占 token）。命中 0 条时<b>明确播报</b>「未回忆到相关历史」，与「没开功能」区分开。
 *
 * @param enabled      全局总开关（默认 true）；真正启用条件是「本开关开 <b>且</b> 会话开关开」
 * @param recallLimit  SQL 层候选条数上限（默认 50）：先粗拉再在 Java 侧定型，避免大表无界扫描
 * @param topK         最终注入上下文的条数（默认 3）
 * @param maxKeywords  检索词个数上限（默认 8）：越多则 SQL 的 OR 条件越长、召回越散
 * @param snippetChars 单条召回内容的截断长度（默认 300）
 * @param semanticRerank 是否用精排对召回候选做语义重排（默认 true）；false 或精排不可用时退回「命中词数」排序
 */
@ConfigurationProperties(prefix = "agent.cross-session")
public record CrossSessionProperties(Boolean enabled, Integer recallLimit, Integer topK,
                                     Integer maxKeywords, Integer snippetChars, Boolean semanticRerank) {

    /** 默认 SQL 候选上限。 */
    public static final int DEFAULT_RECALL_LIMIT = 50;
    /** 默认注入条数。 */
    public static final int DEFAULT_TOP_K = 3;
    /** 默认检索词个数上限。 */
    public static final int DEFAULT_MAX_KEYWORDS = 8;
    /** 默认片段截断长度（字符）。 */
    public static final int DEFAULT_SNIPPET_CHARS = 300;

    public CrossSessionProperties {
        if (enabled == null) enabled = true;
        if (recallLimit == null || recallLimit <= 0) recallLimit = DEFAULT_RECALL_LIMIT;
        if (topK == null || topK <= 0) topK = DEFAULT_TOP_K;
        if (maxKeywords == null || maxKeywords <= 0) maxKeywords = DEFAULT_MAX_KEYWORDS;
        if (snippetChars == null || snippetChars <= 0) snippetChars = DEFAULT_SNIPPET_CHARS;
        if (semanticRerank == null) semanticRerank = true;
    }

    /** 能力是否可用（全局开关）。 */
    public boolean enabledOn() {
        return Boolean.TRUE.equals(enabled);
    }

    /** 是否启用语义重排（静态开关；真正是否重排还取决于精排服务是否可用）。 */
    public boolean semanticRerankOn() {
        return Boolean.TRUE.equals(semanticRerank);
    }

    public int recall() {
        return recallLimit == null ? DEFAULT_RECALL_LIMIT : recallLimit;
    }

    public int k() {
        return topK == null ? DEFAULT_TOP_K : topK;
    }

    public int keywords() {
        return maxKeywords == null ? DEFAULT_MAX_KEYWORDS : maxKeywords;
    }

    public int snippet() {
        return snippetChars == null ? DEFAULT_SNIPPET_CHARS : snippetChars;
    }
}
