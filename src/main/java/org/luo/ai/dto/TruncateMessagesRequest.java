package org.luo.ai.dto;

/**
 * 截断会话历史请求体（服务「重新生成 / 编辑重发」）。
 * <p>
 * 语义是「<b>只保留正序前 {@code keepCount} 条消息</b>，其余全部删除」。
 * <p>
 * 为什么用「条数」而不是 message id：截断点由前端决定，而<b>刚发出去那一轮的消息前端手里没有 id</b>
 * ——SSE 只回内容，不回落库主键。让前端为了拿 id 再查一次库，只为传回一个「第几条」的等价信息，
 * 是白绕一圈。而「保留前几条」前端天然就知道（消息数组下标），且 id 单调递增保证了两种表达完全等价。
 *
 * @param keepCount 保留的消息条数（按时间正序计数）；{@code 0} 或负数 = 全部删除。
 *                  大于实际条数时无事发生，不报错。
 */
public record TruncateMessagesRequest(int keepCount) {
}
