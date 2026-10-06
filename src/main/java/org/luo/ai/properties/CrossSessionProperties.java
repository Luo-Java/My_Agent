package org.luo.ai.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 跨会话搜索配置（{@code agent.cross-session.*}）：在<b>本人其他会话</b>的历史消息里做关键词召回。
 * <p>
 * <b>为什么是关键词检索而不是向量检索</b>（本版本的核心取舍）：会话消息是<b>逐轮写入</b>的，
 * 走向量检索意味着每条消息落库时都要多一次 embedding 调用（成本翻倍）并新增一套向量副本的维护链路；
 * 而本项目已有的向量设施是围绕知识库块（{@code kb_chunk}）建的，把会话消息塞进去会让「知识库命中」
 * 与「历史回忆」两件事在同一空间里互相干扰。因此本版本用<b>关键词召回</b>：由 LLM 从当前问题抽出
 * 若干关键词，走 SQL 精确匹配并按命中数排序。代价是<b>召回质量受限于提词质量与字面匹配</b> ——
 * 换个说法就召回不到，这是明确接受的边界，不是缺陷。
 * <p>
 * <b>归属隔离</b>：{@code chat_message} 没有 {@code user_id} 列，归属靠 {@code JOIN conversation} 判定
 * （与追踪可见性、成本配额同一口径）；并且<b>强制排除当前会话</b> —— 当前会话的内容已经在记忆窗口里，
 * 再召回一遍只会重复占 token。
 * <p>
 * <b>召回为空时明确播报</b>：命中 0 条会在执行过程里播一条「未回忆到相关历史」，与「没开这个功能」
 * 区分开 —— 否则用户无法判断是「真没有」还是「功能没生效」。
 *
 * @param enabled      全局总开关（默认 true=能力可用）；真正的启用条件是「本开关开 <b>且</b> 会话开关开」
 * @param recallLimit  SQL 层候选条数上限（默认 50）：先粗拉一批再在 Java 侧定型，避免大表无界扫描
 * @param topK         最终注入上下文的条数（默认 3）：注入是为了帮模型回忆，不是为了塞满窗口
 * @param maxKeywords  从当前问题抽取的关键词个数上限（默认 5）：关键词越多，SQL 的 OR 条件越长、召回越散
 * @param snippetChars 单条召回内容注入与展示的截断长度（默认 300）：过长的历史片段意义有限且吃 token
 */
@ConfigurationProperties(prefix = "agent.cross-session")
public record CrossSessionProperties(Boolean enabled, Integer recallLimit, Integer topK,
                                     Integer maxKeywords, Integer snippetChars) {

    /** 默认 SQL 候选上限。 */
    public static final int DEFAULT_RECALL_LIMIT = 50;
    /** 默认注入条数。 */
    public static final int DEFAULT_TOP_K = 3;
    /** 默认关键词个数上限。 */
    public static final int DEFAULT_MAX_KEYWORDS = 5;
    /** 默认片段截断长度（字符）。 */
    public static final int DEFAULT_SNIPPET_CHARS = 300;

    public CrossSessionProperties {
        if (enabled == null) enabled = true;
        if (recallLimit == null || recallLimit <= 0) recallLimit = DEFAULT_RECALL_LIMIT;
        if (topK == null || topK <= 0) topK = DEFAULT_TOP_K;
        if (maxKeywords == null || maxKeywords <= 0) maxKeywords = DEFAULT_MAX_KEYWORDS;
        if (snippetChars == null || snippetChars <= 0) snippetChars = DEFAULT_SNIPPET_CHARS;
    }

    /** 能力是否可用（全局开关）。 */
    public boolean enabledOn() {
        return Boolean.TRUE.equals(enabled);
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
