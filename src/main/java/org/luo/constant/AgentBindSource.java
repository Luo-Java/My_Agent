package org.luo.constant;

/**
 * 会话-智能体绑定来源（conversation.agent_bind_source 列的取值常量）。
 * <p>
 * 用途：会话上绑定的智能体可能来自两种途径，解绑策略不同：
 * <ul>
 *   <li>{@link #EXPLICIT} —— 用户显式绑定（创建会话时选择 / 前端主动绑定）：保持粘住，
 *       不因话题切换而解绑；</li>
 *   <li>{@link #CLARIFY} —— 追问流程临时绑定（参数补全中）：用户转向别的话题时由编排层自动解绑，
 *       恢复正常的智能路由。</li>
 * </ul>
 * 字符串常量集中在此处，避免散落在各 Service 里出现魔法字符串（改列名/取值时一处维护）。
 */
public final class AgentBindSource {

    /** 用户显式绑定：保持粘住，不因话题切换解绑。 */
    public static final String EXPLICIT = "EXPLICIT";

    /** 追问流程临时绑定：用户转向别的话题时由编排层自动解绑。 */
    public static final String CLARIFY = "CLARIFY";

    private AgentBindSource() {
    }
}
