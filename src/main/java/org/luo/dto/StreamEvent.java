package org.luo.dto;

/**
 * 流式对话的一个事件。
 * <p>
 * 用 {@code type} 区分两类内容，二者语义完全不同：
 * <ul>
 *   <li>{@link #TYPE_TOKEN token} —— 正文分片（AI 的最终回复），前端累加进消息气泡，
 *       并且是<b>唯一会被写入会话记忆</b>的内容；</li>
 *   <li>{@link #TYPE_PROGRESS progress} —— 执行过程（规划步骤、每步的开始/完成/失败等），
 *       仅在本次运行期间实时展示给用户，<b>绝不写入会话记忆</b>：刷新或重新打开会话后不会出现。</li>
 * </ul>
 *
 * @param type 事件类型：{@code token} / {@code progress}
 * @param text 事件文本
 */
public record StreamEvent(String type, String text) {

    /** 正文分片：进入消息气泡，并计入会话记忆。 */
    public static final String TYPE_TOKEN = "token";
    /** 执行过程：仅运行期展示，不计入会话记忆。 */
    public static final String TYPE_PROGRESS = "progress";

    /** 构造正文分片事件。 */
    public static StreamEvent token(String text) {
        return new StreamEvent(TYPE_TOKEN, text);
    }

    /** 构造执行过程事件。 */
    public static StreamEvent progress(String text) {
        return new StreamEvent(TYPE_PROGRESS, text);
    }
}
