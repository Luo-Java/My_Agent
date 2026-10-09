package org.luo.ai.constant;

/**
 * 会话-智能体绑定来源（conversation.agent_bind_source 列的取值常量）。
 * <p>
 * 用途：会话上绑定的智能体可能来自两种途径，解绑策略不同：
 * <ul>
 *   <li>{@link #EXPLICIT} —— 用户显式绑定（创建会话时选择 / 前端主动绑定）：保持粘住，
 *       不因话题切换而解绑；</li>
 *   <li>{@link #CLARIFY} —— 追问流程临时绑定（参数补全中）：用户转向别的话题时由编排层自动解绑，
 *       恢复正常的智能路由；</li>
 *   <li>{@link #HANDOFF} —— 智能体主动转交（handoff）：当前智能体判断该问题更适合另一个智能体，
 *       调用转交工具把会话交出去。<b>与 EXPLICIT 同样粘住</b>（用户没有推翻这次交接的理由），
 *       但来源可区分出来，便于追踪「这轮为什么是它在答」。</li>
 * </ul>
 * 字符串常量集中在此处，避免散落在各 Service 里出现魔法字符串（改列名/取值时一处维护）。
 */
public final class AgentBindSource {

    /** 用户显式绑定：保持粘住，不因话题切换解绑。 */
    public static final String EXPLICIT = "EXPLICIT";

    /** 追问流程临时绑定：用户转向别的话题时由编排层自动解绑。 */
    public static final String CLARIFY = "CLARIFY";

    /** 智能体主动转交（handoff）：与 EXPLICIT 一样粘住，直到用户另行指定。 */
    public static final String HANDOFF = "HANDOFF";

    private AgentBindSource() {
    }
}
