package org.luo.properties;

import org.luo.memory.DbChatMemory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话记忆窗口配置（{@code agent.memory.*}）。原先这三项是 {@link DbChatMemory} 里的 static final 常量，
 * 改一次要重新编译；且其中「窗口无下限保护」是一个真实的静默缺陷——单条消息自己就超过预算时，窗口起点被
 * 推到末尾、<b>整个历史窗口塌缩为空</b>（见 {@link DbChatMemory#computeWindowStart}）。故抽成本配置类，
 * 把「保留条数下限」与「单条截断」做成可调 + 可关闭。
 * <p>
 * 三个参数共同决定 {@code DbChatMemory.get()} 返回哪一段历史，而 {@code MemoryMergeService} 用<b>同一个
 * 函数、同一份配置</b>计算合并边界——两处必须一致，否则「被摘要掉的区间」与「真正不进的上下文区间」会错位
 * （记忆重复或静默丢失）。故本类被这两处共同注入，是唯一的参数来源。
 *
 * @param recentTokens    维持上下文的最大原文 token 预算（近似值，按字符数估算）；超出部分由滚动摘要覆盖
 * @param minKeepMessages 窗口<b>至少</b>保留的最近消息条数（不受预算约束）——防塌缩下限；0=关闭
 * @param maxMessageChars 单条消息进上下文前的字符上限，超出截断并标注；0=不截断
 */
@ConfigurationProperties(prefix = "agent.memory")
public record MemoryProperties(Integer recentTokens, Integer minKeepMessages, Integer maxMessageChars) {

    /**
     * 默认上下文预算（字符近似）。数据类回复（分布表 / 成绩表）可达 1~3k 字符，1000 会把上一轮整体切掉、
     * 导致模型看不到已有数据而重复查库；4000 可在成本可控前提下保住 1~2 轮数据型对话。
     */
    public static final int DEFAULT_RECENT_TOKENS = 4000;

    /**
     * 默认最少保留条数 = 1 组「用户提问 + 助手回答」。保留 2 条而非 1 条：只有 1 条时窗口里可能只剩半轮
     * （最坏只剩一条巨大的助手回答），模型看不到对应提问，反而更容易答偏。
     */
    public static final int DEFAULT_MIN_KEEP_MESSAGES = 2;

    public MemoryProperties {
        if (recentTokens == null || recentTokens <= 0) recentTokens = DEFAULT_RECENT_TOKENS;
        if (minKeepMessages == null || minKeepMessages < 0) minKeepMessages = DEFAULT_MIN_KEEP_MESSAGES;
        if (maxMessageChars == null || maxMessageChars < 0) maxMessageChars = DEFAULT_RECENT_TOKENS;
    }

    /** 是否对超长单条消息做截断（{@code max-message-chars: 0} 可关闭）。 */
    public boolean truncateLongMessageOn() {
        return maxMessageChars != null && maxMessageChars > 0;
    }
}
