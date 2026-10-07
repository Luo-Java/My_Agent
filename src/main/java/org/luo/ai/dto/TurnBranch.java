package org.luo.ai.dto;

/**
 * 本轮要落的「对话分支版本」——编辑重发 / 重新生成时由前端带上，表示这次回答要作为某一轮的新版本。
 * <p>
 * 一次完整的分支是<b>两步</b>，这个对象只承担第二步的输入：
 * <ol>
 *   <li>{@code POST …/{id}/branch} 先给目标轮分组并算出新版本号（无破坏性，见
 *       {@code ConversationService.prepareBranch}）；</li>
 *   <li>{@code POST /api/chat/stream} 带上本对象，等服务端跑完、消息确实落库后再打标并让旧版本失效
 *       （见 {@code ConversationService.markRoundBranch}）。</li>
 * </ol>
 * 之所以不在第一步就把旧版本置为 inactive：本轮有可能根本发不出去（附件处理失败、配额超限、内容安全
 * 拒绝），那时旧版本必须原样可见 —— 否则用户会看到那一轮凭空消失且没有任何报错。
 *
 * @param groupId 分支组 ID（由 {@code /branch} 返回）
 * @param version 本轮要落的版本号（由 {@code /branch} 返回）
 */
public record TurnBranch(String groupId, int version) {

    /**
     * 归一化前端入参：{@code groupId} 为空即「本轮不是分叉」，返回 {@code null}（调用方据此整个跳过打标）；
     * 版本号缺失或非法时退化为 1，而不是抛错 —— 版本号只影响组内序号，退化成 1 最多让切换器序号不准，
     * 而抛错会让一条本来能答的问题发不出去。
     */
    public static TurnBranch of(String groupId, Integer version) {
        if (groupId == null || groupId.isBlank()) {
            return null;
        }
        return new TurnBranch(groupId, version == null || version <= 0 ? 1 : version);
    }
}
