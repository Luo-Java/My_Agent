package org.luo.ai.dto;

/**
 * 流式对话的一个事件，用 {@code type} 区分五类内容：
 * <ul>
 *   <li>{@link #TYPE_TOKEN token} —— 正文分片，前端累加进消息气泡，且是<b>唯一写入会话记忆</b>的内容；</li>
 *   <li>{@link #TYPE_PROGRESS progress} —— 执行过程（规划步骤、每步进展），仅运行期展示、
 *       <b>绝不写入会话记忆</b>（刷新或重开会话后不再出现）；</li>
 *   <li>{@link #TYPE_CITATIONS citations} —— 本轮 RAG 引用来源（JSON 数组字符串），正文推完后发一次，
 *       前端渲染 [n] 角标与「引用来源」列表；不写入记忆（另落库在 chat_message.citations_json）；</li>
 *   <li>{@link #TYPE_ERROR error} —— 本轮出错（LLM 调用失败等），前端红字展示，<b>不进正文、不进记忆</b>；</li>
 *   <li>{@link #TYPE_PLAN plan} —— 规划模式「先看计划」开关开启时，规划产出的待确认计划（JSON 对象字符串，
 *       含任务 id 与步骤清单），前端渲染成可点「执行计划」的卡片；<b>不进正文、不进记忆</b>——计划明细
 *       同时以文本形式跟着 token 推一遍，刷新后仍可回看（本事件只负责「可交互」这一层）；</li>
 *   <li>{@link #TYPE_APPROVAL approval} —— 执行到「需审批」步骤时暂停，携带待批步骤信息（JSON 对象字符串：
 *       任务 id + 步骤下标 + 智能体 + 指令），前端渲染审批卡片（「批准并继续」/「终止计划」）；
 *       与 plan 同理，本事件只负责可交互那一层，暂停说明另以正文推一遍；</li>
 *   <li>{@link #TYPE_REVIEW review} —— 并行评审会话：多个候选智能体的作答（JSON 对象字符串：候选数组），
 *       前端渲染成候选卡片；<b>必须先于 token</b>——候选是「最终答案怎么来的」，正文是结论，
 *       顺序反了用户会先看到一个没有出处的答案；候选<b>不进正文、不进记忆</b>（进记忆的只有综合后的结论）；</li>
 *   <li>{@link #TYPE_RECALL recall} —— 跨会话搜索：从本人其他会话召回的历史片段（JSON 数组字符串），
 *       前端渲染「回忆到的历史」列表；不进正文、不进记忆；</li>
 *   <li>{@link #TYPE_PING ping} —— <b>心跳</b>，无业务语义，前端直接忽略。存在的理由：前置链
 *       （路由→参数抽取→改写→检索）完全静默（秒级到十几秒无字节流出），反向代理会当空闲切断长连接，
 *       周期发一个无用事件即可保活。</li>
 * </ul>
 *
 * @param type 事件类型：{@code token} / {@code progress} / {@code citations} / {@code error} / {@code plan} / {@code approval} / {@code review} / {@code recall} / {@code ping}
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
    /** 待确认计划：规划暂停时推送，前端渲染计划卡片 + 「执行计划」按钮；不进正文、不进记忆。 */
    public static final String TYPE_PLAN = "plan";
    /** 待审批步骤：执行到审批关卡暂停时推送，前端渲染审批卡片 + 「批准并继续」按钮；不进正文、不进记忆。 */
    public static final String TYPE_APPROVAL = "approval";
    /** 并行评审候选：推送各候选智能体的作答（先于正文），前端渲染候选卡片；不进正文、不进记忆。 */
    public static final String TYPE_REVIEW = "review";
    /** 跨会话召回：推送本人其他会话命中片段，前端渲染「回忆到的历史」；不进正文、不进记忆。 */
    public static final String TYPE_RECALL = "recall";
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

    /** 构造待确认计划事件（json 为计划对象字符串：任务 id + 步骤清单）。 */
    public static StreamEvent plan(String json) {
        return new StreamEvent(TYPE_PLAN, json);
    }

    /** 构造待审批步骤事件（json 为待批步骤对象字符串：任务 id + 步骤下标 + 智能体 + 指令）。 */
    public static StreamEvent approval(String json) {
        return new StreamEvent(TYPE_APPROVAL, json);
    }

    /** 构造并行评审候选事件（json 为候选对象字符串：{candidates:[{agentCode,agentName,reply}]}）。必须先于正文推送。 */
    public static StreamEvent review(String json) {
        return new StreamEvent(TYPE_REVIEW, json);
    }

    /** 构造跨会话召回事件（json 为命中数组字符串：[{index,conversationId,conversationTitle,role,createdAt,snippet}]）。 */
    public static StreamEvent recall(String json) {
        return new StreamEvent(TYPE_RECALL, json);
    }

    /** 构造心跳事件（保活专用；前端不识别该类型即自动忽略）。 */
    public static StreamEvent ping() {
        return new StreamEvent(TYPE_PING, "1");
    }
}
