package org.luo.dto;

/**
 * 流式对话的一个事件，用 {@code type} 区分五类内容：
 * <ul>
 *   <li>{@link #TYPE_TOKEN token} —— 正文分片，前端累加进消息气泡，且是<b>唯一写入会话记忆</b>的内容；</li>
 *   <li>{@link #TYPE_PROGRESS progress} —— 执行过程（规划步骤、每步进展），仅运行期展示、
 *       <b>绝不写入会话记忆</b>（刷新或重开会话后不再出现）；</li>
 *   <li>{@link #TYPE_CITATIONS citations} —— 本轮 RAG 引用来源（JSON 数组字符串），正文推完后发一次，
 *       前端渲染 [n] 角标与「引用来源」列表；不写入记忆（另落库在 chat_message.citations_json）；</li>
 *   <li>{@link #TYPE_ERROR error} —— 本轮出错（LLM 调用失败等），前端红字展示，<b>不进正文、不进记忆</b>；</li>
 *   <li>{@link #TYPE_PING ping} —— <b>心跳</b>，无业务语义，前端直接忽略。存在的理由：前置链
 *       （路由→参数抽取→改写→检索）完全静默（秒级到十几秒无字节流出），反向代理会当空闲切断长连接，
 *       周期发一个无用事件即可保活。</li>
 * </ul>
 *
 * @param type 事件类型：{@code token} / {@code progress} / {@code citations} / {@code error} / {@code ping}
 * @param text 事件文本
 */
public record StreamEvent(String type, String text) {

    /** 正文分片：进入消息气泡，并计入会话记忆。 */
    public static final String TYPE_TOKEN = "token";
    /** 执行过程：仅运行期展示，不计入会话记忆。 */
    public static final String TYPE_PROGRESS = "progress";
    /** RAG 引用来源：仅运行期展示（另落库供历史回看），不进正文、不进记忆。 */
    public static final String TYPE_CITATIONS = "citations";
    /** 错误：红色提示展示，不进正文、不进记忆。 */
    public static final String TYPE_ERROR = "error";
    /** 心跳：保活专用，无业务语义，前端忽略未知事件类型即可。 */
    public static final String TYPE_PING = "ping";

    public static StreamEvent token(String text) {
        return new StreamEvent(TYPE_TOKEN, text);
    }

    public static StreamEvent progress(String text) {
        return new StreamEvent(TYPE_PROGRESS, text);
    }

    /** 构造 RAG 引用来源事件（json 为 JSON 数组字符串）。 */
    public static StreamEvent citations(String json) {
        return new StreamEvent(TYPE_CITATIONS, json);
    }

    public static StreamEvent error(String text) {
        return new StreamEvent(TYPE_ERROR, text);
    }

    /** 构造心跳事件（保活专用；前端不识别该类型即自动忽略）。 */
    public static StreamEvent ping() {
        return new StreamEvent(TYPE_PING, "1");
    }
}
