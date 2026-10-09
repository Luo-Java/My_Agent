package org.luo.common.util;

/**
 * 字符串截断：<b>保证结果长度 ≤ max</b>。
 *
 * <h2>为什么要有这个类（而不在各处各写一个）</h2>
 * 截断本身有三行代码，但它有两个<b>反复踩中的陷阱</b>，而分散在各处时必然有一处漏：
 * <ol>
 *   <li><b>标记后缀算在预算内</b>：写成 {@code substring(0, max) + "…"} 会产出 max+1 字符 ——
 *       目标列是 {@code VARCHAR(n)} 时就撞 MySQL 1406。本项目真的踩过：
 *       {@code ToolApprovalService} 写的是 {@code + "…（已截断）"}（后缀 9 字符），
 *       {@code ScheduledTaskService.summarize} 写的是 {@code + "…"}（后缀 1 字符），
 *       后者溢出会被 {@code execute} 的 catch 吞掉 ⇒ 定时任务状态<b>永久停在 RUNNING</b>且不再通知。</li>
 *   <li><b>max 小于标记长度</b>：{@code max - suffix.length()} 为负时，靠 {@code max(1, …)} 兜底仍会超长。
 *       三个真实调用点（1000/255/4000）都够大，但 max 是参数，<b>边界必须由方法自己守住</b>，
 *       不能指望调用方永远传大值。</li>
 * </ol>
 * 统一到一处后，「结果 ≤ max」是这一个类的性质，调用方只需声明预算。
 *
 * <h2>关于「不静默截断」</h2>
 * 有几处刻意<b>不</b>用本类（超长时抛 400 而不是替用户丢字，见
 * {@code ConversationFactService.requireFact}、{@code ScheduledTaskService} 的名称校验）：
 * 那是产品决策，不是截断实现，故不混进这里。
 */
public final class TextClip {

    /** 默认截断标记（1 字符）。 */
    public static final String ELLIPSIS = "…";

    /** 带说明的截断标记（9 字符），用于「明确告诉使用者这段被截过」的场景。 */
    public static final String MARKED = "…（已截断）";

    private TextClip() {
    }

    /** 截断并加默认省略号；{@code max <= 0} 视为「不限制」。 */
    public static String clip(String s, int max) {
        return clip(s, max, ELLIPSIS);
    }

    /**
     * 截断到 {@code max}（含标记），<b>结果长度必定 ≤ max</b>。
     * <p>
     * {@code max} 容不下标记时退化为「只截字、不加标记」—— 此时标记本身就是噪声，
     * 而「长度仍然守住」比「看起来像截过」更重要。
     *
     * @param s 原文，可为 null
     * @param max 长度上限（字符数）；{@code <= 0} 表示不限制，原样返回
     * @param marker 截断标记，可为 null/空（等价于不加标记）
     */
    public static String clip(String s, int max, String marker) {
        if (s == null || max <= 0 || s.length() <= max) {
            return s;
        }
        String suffix = (marker == null || marker.isEmpty()) ? "" : marker;
        if (suffix.length() >= max) {
            return s.substring(0, max);
        }
        return s.substring(0, max - suffix.length()) + suffix;
    }
}
