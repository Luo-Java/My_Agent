package org.luo.ai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@TableName("conversation")
public class Conversation {

    /** 会话 ID，业务层生成 UUID 后写入（INPUT 表示自行赋值）。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /**
     * 所属用户 ID，关联 sys_user.id。<b>会话按用户隔离</b>：列表/读取/改名/开关/删除一律带该条件，
     * 非本人会话按「不存在」处理（不泄漏他人会话是否存在）。为空表示历史遗留数据，不归属任何用户。
     */
    private Long userId;

    private String title;

    /** 绑定的智能体 ID，关联 agent.id（自增主键）；为空表示使用默认助手。 */
    private Long agentId;

    /**
     * 是否规划模式会话：1=动态规划器（运行时由 LLM 根据用户目标动态规划多智能体步骤并执行），
     * 0=普通对话 / 单智能体会话。与 agent_id 互斥（但数据库层不做强约束，由业务层保证）。
     */
    private Boolean planner;

    /** 智能体绑定来源：EXPLICIT=用户显式选择（保持粘住，不因话题切换解绑）；CLARIFY=追问流程临时绑定（用户转向别的话题时自动解绑）；为空表示未绑定。 */
    private String agentBindSource;

    /**
     * 会话级 RAG 开关（纯开关，不选库）：true=每轮对话自动检索资料库——目标库 =「通用知识库（全局）」
     * + 本轮路由/绑定到的智能体的专属库（存在才查），多库命中合并注入；false = 不检索（不使用 RAG）。
     * 由前端输入框「📚 RAG」开关维护，发送时随请求覆盖写回。库/智能体被删除后检索侧查不到库自动降级。
     */
    private Boolean ragEnabled;

    /**
     * 规划模式「先看计划」开关：true=规划只产出计划并暂停，用户在计划卡片上确认后才执行；
     * false=规划后直接执行（默认，与改造前行为一致）。
     * <p>
     * 仅在 {@link #planner} 为 true 时有意义：暂停期间计划已落库为 RUNNING 任务（步骤全 PENDING），
     * 因此「确认执行」复用的就是断点续跑那条通路，没有第二套执行入口。
     */
    private Boolean plannerConfirm;

    /**
     * 并行评审开关：true=本轮由多个候选智能体并行作答、再由裁决者综合成最终回答；false=普通单智能体回答（默认）。
     * <p>
     * 与 {@link #planner} <b>互斥</b>：两者都是「编排形态」（一个决定怎么拆、一个决定谁来答），同时开启语义会打架，
     * 由业务层保证（数据库层不做强约束，与 planner/agent_id 的互斥同一处理）。
     * 候选答案只走 SSE 展示、<b>不进会话记忆</b>（进记忆的只有综合后的最终回答）。
     */
    private Boolean reviewEnabled;

    /**
     * 跨会话搜索开关：true=每轮先用 LLM 抽取检索关键词，在本用户<b>其他会话</b>的历史消息里做关键词召回
     * 并注入当前上下文；false=不检索（默认）。
     * <p>
     * 只查本人会话（`chat_message` 没有 `user_id` 列，归属靠 {@code JOIN conversation} 判定），
     * 且<b>天然排除当前会话</b>——当前会话的内容已经在记忆窗口里，再召回一遍只会重复占 token。
     */
    private Boolean crossSession;

    /** 较早对话的滚动摘要（长期记忆），超出最近窗口的历史会被 LLM 压缩进这里。 */
    private String summary;

    /** 已被摘要覆盖的最旧消息条数（与 chat_message 按时间正序的索引对齐）。 */
    private Integer summarizedCount;

    /** 用户核心信息（长期关键事实：姓名/身份/偏好/待办等），随摘要一起由 LLM 提取更新。 */
    private String coreFacts;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
