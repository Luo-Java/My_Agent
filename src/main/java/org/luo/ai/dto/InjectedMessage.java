package org.luo.ai.dto;

/**
 * 一条「即将注入模型上下文」的历史消息的摘要视图 —— 那个黑盒的开口：窗口里每条都列出来（角色 + 前若干字 + 长度），
 * 两处共用同一结构（记忆面板的「当前窗口构成」与追踪弹窗的「本轮注入」），回答「它怎么忘了上一轮说的」。
 * <p>
 * <b>刻意只带预览、不带全文</b>：用途是「让人认出是哪一条」，不是「再读一遍历史」；全文会让面板与追踪记录一起
 * 膨胀（agent_trace 每列都是 TEXT，几十轮全文叠加没必要）。
 *
 * @param role    消息角色：user / assistant
 * @param preview 内容预览（已截断并拼省略号）
 * @param chars   该条<b>实际进上下文</b>的字符数 —— 已经过单条超长截断（见 {@code DbChatMemory#truncateForContext}），
 *                故与模型真正收到的长度一致，而不是库里的原始长度；按原始长度统计会在长消息那轮显示虚高数字
 */
public record InjectedMessage(String role, String preview, int chars) {

    /** 预览保留的字符数：够认出是哪条，又不至于把面板撑爆。 */
    public static final int PREVIEW_CHARS = 60;

    /**
     * 由角色 + <b>已按上下文规则截断</b>的文本构造。
     * 调用方负责先过 {@code DbChatMemory#truncateForContext}，本记录不重复实现截断规则。
     */
    public static InjectedMessage of(String role, String injectedText) {
        String text = injectedText == null ? "" : injectedText.strip();
        String preview = text.length() > PREVIEW_CHARS ? text.substring(0, PREVIEW_CHARS) + "…" : text;
        return new InjectedMessage(role, preview, text.length());
    }
}
