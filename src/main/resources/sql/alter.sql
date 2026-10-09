-- ===========================================================================
-- alter.sql —— 存量库补丁脚本。建库权威定义是 schema.sql；存量库不跑它，只跑本脚本补表/补列/补索引。
--
-- 三条约定：
--   1. 幂等。新增表用 CREATE TABLE IF NOT EXISTS，重复执行安全；ADD COLUMN / ADD INDEX 没有
--      IF NOT EXISTS 语法，重复执行报 1060 (Duplicate column name) / 1061 (Duplicate key name)，忽略即可。
--   2. 顺序。新建表必须放文件最前 —— 后面有 ALTER TABLE task / task_step，表不存在会先在那里失败。
--   3. 双写。本脚本新建或改动的表结构必须与 schema.sql 逐字一致（同一张表不能有两种定义）。
--
-- ⚠ 第 0 段「补齐可能缺失的表」不是可选项：这些表在 schema.sql 里有、存量库却没有（历史欠账），
--    缺任何一张都会让对应功能整块 500（规划持久化 / 评测跑批 / 反馈转用例 / 长期事实 / 追踪 / 成本统计）。
--    全部 IF NOT EXISTS，库里已有则是 no-op。
--    自 2026-10-08 起本段**覆盖 schema.sql 的全部 20 张表**（此前只补 7 张功能表，agent / conversation /
--    chat_message / agent_trace / llm_usage / kb 系 8 张核心表缺失 —— 一旦库里真没有，本文件后面的
--    ALTER TABLE 会先失败在「表不存在」）。核对口径：两脚本的 CREATE 段必须逐字一致，用
--    `.workbuddy/tools/` 下的建表比对脚本过一遍，**按整张表清单过，不要抽查**。
-- ===========================================================================

-- ===========================================================================
-- 第 0 段 · 补齐存量库可能缺失的表（与 schema.sql 逐字一致；共 20 张 = schema.sql 全部）
-- ===========================================================================

-- 管理操作审计表：ADMIN 的敏感动作（给谁加了什么角色、停用了谁、删了哪个角色）逐条留痕，只增不改。
-- 为什么需要：权限变更此前只在业务表上留下「结果」（sys_user_role 换了一行），留不下「谁、什么时候、
-- 把谁的什么权限改成了什么」。事后追查「这个管理员是谁授权的」时无从下手 —— 这正是审计要回答的问题。
-- 用户名快照：用户被改名或删除后，只留 user_id 会让记录变成一串读不懂的数字，审计记录必须自解释。
-- detail 落库前经 PII 脱敏（见 PiiMasker）：审计表本身不该成为新的敏感信息聚集地。
CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '审计ID',
    user_id     BIGINT        DEFAULT NULL            COMMENT '操作者用户ID（关联 sys_user.id；为空=系统自身/无身份上下文）',
    username    VARCHAR(64)   DEFAULT NULL            COMMENT '操作者用户名快照（改名/删除后仍可追溯）',
    action      VARCHAR(64)   NOT NULL                COMMENT '动作编码（取值见 AuditAction：CREATE/UPDATE/DELETE_USER、UPDATE_PASSWORD、CREATE/UPDATE/DELETE_ROLE）',
    target_type VARCHAR(32)   DEFAULT NULL            COMMENT '对象类型（USER / ROLE 等）',
    target_id   VARCHAR(64)   DEFAULT NULL            COMMENT '对象ID',
    detail      VARCHAR(1000) DEFAULT NULL            COMMENT '明细（已 PII 脱敏）',
    ip          VARCHAR(64)   DEFAULT NULL            COMMENT '请求来源 IP',
    created_at  DATETIME                              COMMENT '发生时间',
    PRIMARY KEY (id),
    INDEX idx_audit_created (created_at),
    INDEX idx_audit_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '管理操作审计表：ADMIN 敏感动作留痕（只增不改）';

-- 工具审批闸门表：给「智能体要调用敏感工具」加一道人工确认，回答「它要干什么、我同意了吗」。
-- 为什么需要：工具调用发生在模型内部，此前一路绿灯 —— 查库 / 画图 / 转交一键就执行，用户既看不到
-- 「这一步要做什么」，也没有喊停的机会。本表就是闸门的落点：命中拦截时落一行，用户在前端点批准。
-- 粒度为「会话 × 工具」一行（uk_conv_tool），语义是「本会话内该工具是否放行」：
-- 批准 = 本会话后续调用不再逐次打断（一次授权、用起来才不烦）；拒绝 = 本会话一直挡住（不反复弹窗）。
-- 授权有有效期（见 agent.tool-approval.expire-minutes）：过期后自动回到「待确认」，不留下长期敞口。
-- 归属不落 user_id 列：加了就得把身份一路传进工具回调链路，归属一律查询侧 JOIN conversation 判定，
-- 与 agent_trace 同一口径（见 AgentTraceMapper.xml 的说明）。
CREATE TABLE IF NOT EXISTS tool_approval (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    conversation_id VARCHAR(64)   NOT NULL                COMMENT '所属会话，关联 conversation.id',
    agent_id        BIGINT        DEFAULT NULL            COMMENT '发起调用的智能体ID（可空：通用助手/转交场景）',
    tool_name       VARCHAR(128)  NOT NULL                COMMENT '工具名（与 agent.tools_json 白名单里的名字一致）',
    input_json      TEXT          DEFAULT NULL            COMMENT '触发拦截的入参原样留档（供用户判断「它到底要干什么」）',
    user_message    VARCHAR(1000) DEFAULT NULL            COMMENT '触发该调用的用户原话（批准后据此重跑同一轮）',
    status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING=待确认 / APPROVED=已批准 / REJECTED=已拒绝',
    note            VARCHAR(255)  DEFAULT NULL            COMMENT '用户决断时的备注',
    decided_by      BIGINT        DEFAULT NULL            COMMENT '决断人ID，关联 sys_user.id（留痕）',
    decided_at      DATETIME      DEFAULT NULL            COMMENT '决断时间',
    used_count      INT           NOT NULL DEFAULT 0      COMMENT '批准后实际放行执行次数（回答「批了之后真的用了吗」）',
    last_used_at    DATETIME      DEFAULT NULL            COMMENT '最近一次放行执行时间',
    created_at      DATETIME                              COMMENT '首次拦截时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_tool (conversation_id, tool_name),
    INDEX idx_approval_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '工具审批闸门表：会话级「该工具是否放行」的决断留痕';

CREATE TABLE IF NOT EXISTS task (
    id              VARCHAR(64)  NOT NULL                COMMENT '任务ID（业务层生成UUID）',
    conversation_id VARCHAR(64)  NOT NULL                COMMENT '所属会话ID，关联 conversation.id',
    user_goal       TEXT         DEFAULT NULL            COMMENT '用户原始目标（触发规划的那句原话）',
    status          VARCHAR(16)  NOT NULL DEFAULT 'RUNNING' COMMENT '任务状态：RUNNING/DONE/FAILED/CANCELLED',
    total_steps     INT          NOT NULL DEFAULT 0      COMMENT '步骤总数（快照，避免反复数）',
    done_steps      INT          NOT NULL DEFAULT 0      COMMENT '已完成步骤数（冗余，供列表快速展示进度）',
    pause_requested TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '用户中途喊停的信号位：1=执行循环在下一个「层边界」停止推进（正在跑的那一层照常跑完），剩余步骤保持 PENDING、任务保持 RUNNING；由续跑入口（resumeTask）负责清零，故不会把用户永久挡在门外',
    result          LONGTEXT     DEFAULT NULL            COMMENT '最终汇总结果（汇总步产出/最后一个成功步骤产出）',
    created_at      DATETIME                             COMMENT '创建时间',
    updated_at      DATETIME                             COMMENT '最后更新时间',
    PRIMARY KEY (id),
    INDEX idx_task_conv (conversation_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '规划任务表：一轮多智能体规划任务的落库与断点续跑';

CREATE TABLE IF NOT EXISTS task_step (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    task_id        VARCHAR(64)  NOT NULL                COMMENT '所属任务ID，关联 task.id',
    step_index     INT          NOT NULL                COMMENT '步骤下标（0基，对应规划 steps 数组位置，即重映射后的 specs 连续下标）',
    agent_code     VARCHAR(64)  NOT NULL                COMMENT '本步执行的智能体编码',
    instruction    TEXT         DEFAULT NULL            COMMENT '给该智能体的指令',
    depends_on     VARCHAR(500) DEFAULT '[]'            COMMENT '依赖的前序步骤下标（JSON数组，如 [0,1]；空表=无依赖）',
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '步骤状态：PENDING/RUNNING/DONE/SKIPPED/FAILED',
    retry_count    INT          NOT NULL DEFAULT 0      COMMENT '重试次数（0=未重试；续跑对 FAILED 重试一次，累计>=2 判确定性失败）',
    approval_required TINYINT(1) NOT NULL DEFAULT 0     COMMENT '该步执行前是否需要用户审批：1=执行到此步先暂停等待批准（计划阶段可改），0=直接执行',
    approved       TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '审批是否已通过：1=已批准可执行；0=未批准。仅当 approval_required=1 时有约束意义',
    output         LONGTEXT     DEFAULT NULL            COMMENT '本步产出文本（成功时写入；失败/空为 NULL）',
    error          VARCHAR(1000) DEFAULT NULL           COMMENT '失败原因（FAILED 时）',
    citations_json TEXT         DEFAULT NULL            COMMENT '本步 RAG 引用（与 chat_message.citations_json 同构）',
    started_at     DATETIME                             COMMENT '开始执行时间',
    finished_at    DATETIME                             COMMENT '完成时间',
    PRIMARY KEY (id),
    INDEX idx_step_task (task_id, step_index)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '规划任务步骤表：任务每一步的产出与状态落库（断点续跑的最小粒度）';

CREATE TABLE IF NOT EXISTS task_template (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    user_id        BIGINT       NOT NULL                COMMENT '创建者用户ID，关联 sys_user.id；模板按用户隔离，仅本人可见',
    name           VARCHAR(100) NOT NULL                COMMENT '模板名称（用户填写，如「周报生成」）',
    description    VARCHAR(500) DEFAULT NULL            COMMENT '备注：适用场景、套用时该填什么目标',
    steps_json     TEXT         NOT NULL                COMMENT '步骤骨架 JSON 数组：[{agentCode,instruction,dependsOn:[0基下标]}]；刻意不含智能体展示名（改名不该让模板失效），展示时现查',
    source_task_id VARCHAR(64)  DEFAULT NULL            COMMENT '来源任务ID（从哪次规划存下来的，仅供追溯；源任务被删不影响模板）',
    use_count      INT          NOT NULL DEFAULT 0      COMMENT '被套用次数（统计用，帮用户判断哪个模板值得留）',
    created_at     DATETIME                             COMMENT '创建时间',
    updated_at     DATETIME                             COMMENT '最后更新时间',
    PRIMARY KEY (id),
    -- 列表查询是「WHERE user_id = ? ORDER BY created_at DESC」，复合索引让过滤与排序一趟走完（免 filesort）
    INDEX idx_tpl_user (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '规划模板表：把跑顺的规划步骤骨架沉淀为可复用资产';

CREATE TABLE IF NOT EXISTS eval_result (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    batch_id     VARCHAR(32)   NOT NULL                COMMENT '批次ID：同一次跑批的所有用例共用，跨批次对比按它取数',
    scenario     VARCHAR(16)   NOT NULL                COMMENT '用例场景：ROUTE=智能路由 / PLAN=动态规划',
    case_name    VARCHAR(128)  NOT NULL                COMMENT '用例名（来自 eval-cases.yaml）；批次间以它对齐身份，改名会被视作一增一删',
    input        VARCHAR(500)  NOT NULL                COMMENT '用例输入',
    expected     VARCHAR(500)  DEFAULT NULL            COMMENT '期望值摘要（用例 expect 的文本化）',
    actual       VARCHAR(500)  DEFAULT NULL            COMMENT '实际值摘要（被测组件真实产出的决策）',
    passed       TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '是否通过',
    config_error TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '用例自身配置错误（如引用了不存在的 agentCode）：计入本列而非失败，避免「用例写错」污染提示词质量判断',
    failure      VARCHAR(1000) DEFAULT NULL            COMMENT '失败原因（逐条断言的差异说明）',
    detail       TEXT          DEFAULT NULL            COMMENT '被测组件的完整原始输出，排查用',
    cost_ms      INT           NOT NULL DEFAULT 0      COMMENT '该用例耗时（毫秒）',
    created_at   DATETIME                              COMMENT '记录时间',
    PRIMARY KEY (id),
    INDEX idx_eval_batch (batch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '提示词回归评测结果表：一次跑批每个用例一行，按 batch_id 分组';

CREATE TABLE IF NOT EXISTS message_feedback (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    message_id      BIGINT       NOT NULL                COMMENT '被评价的消息ID，关联 chat_message.id（恒为助手回复）',
    conversation_id VARCHAR(64)  NOT NULL                COMMENT '所属会话ID，关联 conversation.id（归属校验与按会话查询用）',
    user_id         BIGINT       NOT NULL                COMMENT '评价人用户ID，关联 sys_user.id；一人对一条消息一票',
    rating          VARCHAR(8)   NOT NULL                COMMENT '评价：UP=有用 / DOWN=有问题',
    reason          VARCHAR(32)  DEFAULT NULL            COMMENT '问题分类（仅 DOWN 时有意义）：ANSWERS_OFF=答非所问 / FABRICATED=编造 / ROUTING=路由或规划不对 / OTHER=其他',
    comment         VARCHAR(500) DEFAULT NULL            COMMENT '补充说明（用户填的原文，供人工排查；不进任何断言）',
    user_input      TEXT         DEFAULT NULL            COMMENT '那一轮的用户输入快照：转回归用例时作为用例 input。存快照而非联查——反馈是「对那一轮的评价」，会话被删、消息被改都不该让它失效（与 agent_trace 存 user_message 同一取舍）',
    eval_case_id    BIGINT       DEFAULT NULL            COMMENT '转成的库内回归用例ID，关联 eval_case.id；NULL=尚未转为用例',
    created_at      DATETIME                             COMMENT '首次评价时间',
    updated_at      DATETIME                             COMMENT '最后改票时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_fb_msg_user (message_id, user_id),
    INDEX idx_fb_conv (conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '消息反馈表：对助手回复的显式评价，是「线上翻车 → 回归用例」的入口';

CREATE TABLE IF NOT EXISTS eval_case (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    name             VARCHAR(128) NOT NULL                COMMENT '用例名（批次对比的身份标识，改名会被视作一增一删）',
    scenario         VARCHAR(16)  NOT NULL                COMMENT '场景：ROUTE=智能路由 / PLAN=动态规划',
    input            VARCHAR(500) NOT NULL                COMMENT '用例输入（路由场景=用户消息；规划场景=用户目标）',
    pending_question VARCHAR(500) DEFAULT NULL            COMMENT '路由场景的「待回答追问」上下文（可空）',
    expect_json      VARCHAR(500) NOT NULL                COMMENT '期望断言（JSON，键与 eval-cases.yaml 的 expect 同构）',
    source           VARCHAR(16)  NOT NULL DEFAULT 'FEEDBACK' COMMENT '来源：YAML=用例集文件（本项目不写库，仅预留）/ FEEDBACK=由用户反馈转入',
    feedback_id      BIGINT       DEFAULT NULL            COMMENT '来源反馈ID，关联 message_feedback.id（source=FEEDBACK 时）',
    enabled          TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否参与跑批：0=停用（留在库里但不再计入批次与对比）',
    created_at       DATETIME                             COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_case_name (name),
    INDEX idx_case_enabled (enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '回归用例表（库内用例）：承接运行时新增的用例，与 eval-cases.yaml 合并参与跑批';

CREATE TABLE IF NOT EXISTS conversation_fact (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    conversation_id VARCHAR(64)  NOT NULL                COMMENT '所属会话ID，关联 conversation.id',
    topic           VARCHAR(32)  NOT NULL                COMMENT '主题标签（身份 / 偏好 / 待办 / 背景 / 其它），供面板分组与人工扫读',
    fact            VARCHAR(500) NOT NULL                COMMENT '事实内容（单条，不含主题前缀）',
    fact_hash       CHAR(32)     NOT NULL                COMMENT 'topic+fact 的 MD5，用于同会话内去重（见建表注释）',
    source          VARCHAR(16)  NOT NULL DEFAULT 'MERGE' COMMENT '来源：MERGE=自动合并产出（每次合并按 diff 重写）/ USER=用户手动添加（合并绝不覆盖或删除）',
    confidence      TINYINT      NOT NULL DEFAULT 3      COMMENT '置信度1~5：用户手写起始5、模型整理起始3，此后每次合并里仍被列出就+1（封顶5）；面板据此排序',
    expires_at      DATETIME     DEFAULT NULL            COMMENT '有效期：NULL=永不过期；过期的条目不再注入但仍留在面板（标「已过期」），删不删由用户定',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE=生效中（参与注入与合并）/ SUPERSEDED=已被同主题的新说法取代（不注入，留档可查）',
    superseded_by   BIGINT       DEFAULT NULL            COMMENT '被哪一条取代（status=SUPERSEDED 时有值），用于面板「被……取代」回链',
    created_at      DATETIME                             COMMENT '首次写入时间',
    updated_at      DATETIME                             COMMENT '最后被确认/改写时间（合并里仍然有效即刷新，故它等于「这条最近还在被维护」）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_fact (conversation_id, fact_hash),
    INDEX idx_fact_conv (conversation_id, updated_at),
    INDEX idx_fact_status (conversation_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '会话长期事实条目表：把 core_facts 那一段文本拆成逐条可管理的行';


-- ---------------------------------------------------------------------------
-- 以下为核心表（agent / conversation / chat_message / agent_trace / llm_usage / kb 系）。
-- 它们建库时就在 schema.sql 里，但 alter.sql 一旦缺了它们，本文件后面的 ALTER TABLE
-- （conversation 加列、agent_trace 加列、llm_usage MODIFY 等）会直接失败在「表不存在」。
-- 全部 IF NOT EXISTS：库里已有则是 no-op；补上只为让本脚本对存量库自足。
-- ---------------------------------------------------------------------------

-- 智能体表：可在页面创建的各类 AI 角色，绑定到会话后决定对话人设与模型参数
CREATE TABLE IF NOT EXISTS agent (
    id            BIGINT       NOT NULL AUTO_INCREMENT    COMMENT '智能体ID（数据库自增主键）',
    name          VARCHAR(128) NOT NULL                   COMMENT '智能体名称，如“翻译官”“Python老师”',
    agent_code    VARCHAR(64)  NOT NULL                   COMMENT '智能体唯一编码（agent_code），创建后不可修改，多智能体协作时用于路由到具体智能体，如 translator、python-teacher',
    icon          VARCHAR(32)  DEFAULT NULL               COMMENT '图标（可选），通常是 emoji，如 🤖',
    description   VARCHAR(512) NOT NULL                   COMMENT '智能体描述（可选）',
    system_prompt TEXT         NOT NULL                   COMMENT '系统提示词/人设，作为该智能体对话的 SystemMessage 注入',
    param_schema  TEXT         DEFAULT NULL               COMMENT '参数清单（JSON）：声明执行所需参数，用于对话中的追问/参数补全；为空表示不启用',
    tools_json    VARCHAR(1000) DEFAULT NULL              COMMENT '工具装配（JSON数组）：NULL=不限制（挂载全部工具，向后兼容）；[]=不挂任何工具；["工具名",...]=仅挂白名单内工具',
    model         VARCHAR(128) DEFAULT NULL               COMMENT '模型名称覆盖（可选），如 gpt-4o、deepseek-chat',
    temperature   DOUBLE        DEFAULT NULL              COMMENT '温度（可选），控制回复随机性，通常 0~2',
    avatar_color  VARCHAR(32)  DEFAULT NULL               COMMENT '主题色（可选），前端展示用，如 #3b82f6',
    created_at    DATETIME                                 COMMENT '创建时间',
    updated_at    DATETIME                                 COMMENT '最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_code (agent_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE=utf8mb4_general_ci COMMENT = '智能体表：可创建的各类 AI 角色，绑定到会话后决定对话人设';

-- 会话表：每个对话会话对应一条记录
CREATE TABLE IF NOT EXISTS conversation (
    id         VARCHAR(64)  NOT NULL                   COMMENT '会话ID（业务层生成的UUID）',
    user_id    BIGINT       DEFAULT NULL               COMMENT '所属用户ID，关联 sys_user.id；会话按用户隔离，仅本人可见（NULL=历史遗留，不归属任何用户）',
    title      VARCHAR(255) DEFAULT '新对话'           COMMENT '会话标题，默认“新对话”，由首条用户消息派生（最多20字）',
    agent_id   BIGINT       DEFAULT NULL               COMMENT '绑定的智能体ID，关联 agent.id（自增主键），为空表示默认助手',
    planner        TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否规划模式会话：1=动态规划器（运行时由 LLM 规划多智能体步骤），0=普通/智能体会话',
    agent_bind_source VARCHAR(16) DEFAULT NULL          COMMENT '智能体绑定来源：EXPLICIT=用户显式选择（保持粘住），HANDOFF=智能体主动转交（同样粘住），CLARIFY=追问流程临时绑定（允许话题切换时解绑）；空=未绑定',
    rag_enabled  TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '会话级 RAG 开关：1=每轮对话自动检索资料库（通用知识库 + 路由到智能体时其专属库）并把命中内容注入上下文；0=不使用 RAG（仅靠模型自身知识）',
    planner_confirm TINYINT(1) NOT NULL DEFAULT 0      COMMENT '规划模式「先看计划」开关：1=规划只产出计划并暂停，用户确认后才执行（计划作为待执行任务落库）；0=规划后直接执行（默认）',
    review_enabled TINYINT(1)  NOT NULL DEFAULT 0      COMMENT '并行评审开关：1=本轮由多个候选智能体并行作答、再由裁决者综合成最终回答（候选与最终答案同推，候选不落记忆）；0=普通单智能体回答（默认）。与 planner 互斥：两者都是「编排形态」，不允许同时开',
    cross_session  TINYINT(1)  NOT NULL DEFAULT 0      COMMENT '跨会话搜索开关：1=每轮先用 LLM 抽取检索关键词，在本用户「其他会话」的历史消息里做关键词召回并注入上下文；0=不检索（默认）。只查本人会话，天然排除当前会话',
    created_at DATETIME                                 COMMENT '会话创建时间',
    updated_at DATETIME                                 COMMENT '最后更新时间，用于会话列表倒序排序',
    summary           TEXT        DEFAULT NULL               COMMENT '较早对话的滚动摘要（长期记忆），超出最近窗口的历史由LLM压缩写入',
    summarized_count  INT         DEFAULT 0                  COMMENT '已被摘要覆盖的最旧消息条数（按时间正序索引）',
    core_facts        TEXT        DEFAULT NULL               COMMENT '用户核心信息（旧版事实归档：逐条事实已迁到 conversation_fact，本列保留原文不再自动更新）',
    clarify_state     TEXT        DEFAULT NULL               COMMENT '参数补全（澄清追问）的显式状态 JSON：{agentId,asked,request,question,params}；NULL=无进行中的追问。落库以摆脱「每轮扫历史重放推导」——历史被摘要压缩或标记不参与记忆后，重放会算错已问次数与已确认参数',
    PRIMARY KEY (id),
    -- 会话列表是「WHERE user_id = ? ORDER BY updated_at DESC」：复合索引让过滤与排序一趟走完（免 filesort）。
    -- 单列 (user_id) 是本索引最左前缀，无需另建（存量库若已有 idx_user 可择机删，见 alter.sql）
    INDEX idx_user_updated (user_id, updated_at),
    INDEX idx_agent (agent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE=utf8mb4_general_ci COMMENT = '会话表：记录一次完整的多轮对话';

-- 会话消息表：每条用户消息与每条AI回复各存一条，同一会话下有多轮记录
CREATE TABLE IF NOT EXISTS chat_message (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '消息自增主键',
    conversation_id VARCHAR(64)  NOT NULL                COMMENT '所属会话ID，关联 conversation.id',
    role            VARCHAR(20)                          COMMENT '消息角色：user=用户，assistant=AI助手',
    content         TEXT                                 COMMENT '消息内容（用户提问或AI回复文本）',
    attachments_json TEXT        DEFAULT NULL            COMMENT '本轮附件元数据（JSON数组：type/filename/storedName/size）；仅用于历史展示，不参与记忆读取（DbChatMemory.get 不读此列，零 token 开销）',
    citations_json   TEXT        DEFAULT NULL            COMMENT '本轮 RAG 引用来源（JSON数组：index/kbName/source/chunkId/score）；仅 assistant 消息、仅用于历史展示与前端角标，不参与记忆读取',
    turn_group_id   VARCHAR(64)  DEFAULT NULL            COMMENT '对话分支组ID（UUID）：同一轮提问的多个「版本」（编辑重发 / 重新生成）共用；NULL=从未分叉，该轮只有一个版本。刻意不做回填——NULL 即旧语义',
    turn_version    INT          DEFAULT NULL            COMMENT '该分支组内的版本序号，从 1 开始递增；版本号连续，故前端可直接令 versionCount = 当前 version',
    turn_active     TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '该版本是否为当前生效版本：1=生效（会被读取、会注入记忆）；同一组内至多一个版本为 1。未分叉的行恒为 1',
    memory_excluded TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否被用户标记为「不参与记忆」：1=该条既不进记忆窗口、也不进摘要（历史仍可见）；0=正常参与（默认）',
    created_at      DATETIME                             COMMENT '消息写入时间；同一轮消息的先后顺序由自增主键 id 兜底（查询统一 ORDER BY created_at, id）。分叉出的新版本沿用被替换版本首条的 created_at，以占回原来的位置——否则它会带着更晚的时间排到后面几轮之后',
    PRIMARY KEY (id),
    -- 记忆窗口读取（DbChatMemory 按会话倒序取最近 N 条）走这条复合索引，避免长会话全表扫描
    INDEX idx_conv_created (conversation_id, created_at),
    -- 分支读取（按组筛当前版本）与版本计数（GROUP BY turn_group_id）共用
    INDEX idx_conv_turn (conversation_id, turn_group_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE=utf8mb4_general_ci COMMENT = '会话消息表：存储每个会话下的多轮对话明细';

-- 智能体链路追踪表：一轮对话的全链路留痕，答「这轮为什么路由到 X / 规划器哪步慢 / RAG 有没有命中」。
-- 纯旁路：异步落库、失败只记日志，不进对话主链路，也不被任何检索或记忆读取
CREATE TABLE IF NOT EXISTS agent_trace (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '追踪记录ID',
    trace_id          VARCHAR(64)  NOT NULL                COMMENT '本轮唯一追踪ID（UUID，同一轮内所有阶段共用一个）',
    conversation_id   VARCHAR(64)  DEFAULT NULL            COMMENT '所属会话ID，关联 conversation.id',
    mode              VARCHAR(16)  DEFAULT NULL            COMMENT '本轮形态：agent=普通/智能体对话，planner=规划模式，review=并行评审（多候选作答 + 裁决综合）',
    route_source      VARCHAR(24)  DEFAULT NULL            COMMENT '处理方来源：BOUND=会话显式绑定，HANDOFF=智能体主动转交，ROUTE=智能路由命中，NONE=通用助手，PLAN=规划编排，REVIEW=并行评审',
    agent_code        VARCHAR(64)  DEFAULT NULL            COMMENT '本轮实际处理/路由到的智能体编码（规划模式为最终步骤的智能体）',
    user_message      VARCHAR(1000) DEFAULT NULL           COMMENT '用户本轮输入（截断）',
    retrieval_query   VARCHAR(1000) DEFAULT NULL           COMMENT '本轮实际用于知识库检索的问题（多轮查询改写产物）；NULL=未改写（未开RAG/首轮/关闭改写/原话已自包含）',
    plan_json         TEXT         DEFAULT NULL            COMMENT '规划模式的步骤计划 JSON（mode=planner 时有值）',
    tool_calls        TEXT         DEFAULT NULL            COMMENT '工具调用明细 JSON 数组：name/args/result（均截断）',
    kb_hit_count      INT          NOT NULL DEFAULT 0      COMMENT 'RAG 命中条数（0=未命中或未开启）',
    citations_json    TEXT         DEFAULT NULL            COMMENT 'RAG 引用来源 JSON（与 chat_message.citations_json 同构）',
    prompt_tokens     INT          NOT NULL DEFAULT 0      COMMENT '本轮输入 token 合计（多次模型调用累加）',
    completion_tokens INT          NOT NULL DEFAULT 0      COMMENT '本轮输出 token 合计',
    total_tokens      INT          NOT NULL DEFAULT 0      COMMENT '本轮 token 合计',
    elapsed_ms        BIGINT       NOT NULL DEFAULT 0      COMMENT '本轮总耗时（毫秒，从进入编排到产出回复）',
    status            VARCHAR(16)  NOT NULL DEFAULT 'ok'   COMMENT '本轮结果：ok=正常产出，error=异常',
    error_message     VARCHAR(1000) DEFAULT NULL           COMMENT '异常信息（status=error 时）',
    memory_json       TEXT         DEFAULT NULL            COMMENT '本轮注入的记忆构成快照（JSON：窗口逐条 role/preview/chars + 长期摘要/长期事实字符数）；NULL=未采集（旁路观测，失败即留空）',
    self_eval_score   TINYINT      DEFAULT NULL            COMMENT '模型对回答的自评分 1~5；NULL=未自评（按比例采样未命中 / 回答过短 / 调用失败）。单独成列而非只存 JSON：可观测面板的「低分轮次」要按它过滤与聚合，JSON 里解析不出索引',
    self_eval_json    TEXT         DEFAULT NULL            COMMENT '自评明细 JSON：score/answered/grounded/issues/comment/trigger；NULL=未自评。与 score 同生共死，一列过滤一列细看',
    created_at        DATETIME                             COMMENT '记录时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_trace_id (trace_id),
    INDEX idx_trace_conv (conversation_id, created_at),
    INDEX idx_trace_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '智能体链路追踪表：一轮对话的路由/RAG/工具/token/耗时/自评留痕';

-- 裸 LLM 调用成本流水表：与 agent_trace 互补，记全量成本 —— 路由判定 / 参数抽取 / 查询改写 /
-- 视觉识别 / 记忆合并这些裸 ChatModel.call()（不经 Advisor）的 token，按 purpose 各记一条
CREATE TABLE IF NOT EXISTS llm_usage (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    trace_id          VARCHAR(64)  DEFAULT NULL            COMMENT '本轮追踪ID（可空：视觉识别是独立请求、早于 trace 建立）',
    conversation_id   VARCHAR(64)  DEFAULT NULL            COMMENT '所属会话ID（可空：如生成智能体人设这类无会话的调用）',
    purpose           VARCHAR(24)  NOT NULL                COMMENT '调用用途：ROUTE=智能路由 / CLARIFY=参数抽取 / REWRITE=查询改写 / PLAN=任务规划 / VISION=视觉识别 / MEMORY_MERGE=记忆合并 / SUBAGENT=智能体转交 / RECALL=跨会话召回 / REVIEW=并行评审 / SELF_EVAL=回答自评',
    model             VARCHAR(128) DEFAULT NULL            COMMENT '实际使用的模型名（可空：未显式指定时取默认模型）',
    prompt_tokens     INT          NOT NULL DEFAULT 0      COMMENT '本次调用输入 token',
    completion_tokens INT          NOT NULL DEFAULT 0      COMMENT '本次调用输出 token',
    total_tokens      INT          NOT NULL DEFAULT 0      COMMENT '本次调用 token 合计',
    created_at        DATETIME                             COMMENT '记录时间',
    PRIMARY KEY (id),
    INDEX idx_usage_purpose (purpose, created_at),
    INDEX idx_usage_conv (conversation_id, created_at),
    INDEX idx_usage_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '裸 LLM 调用成本流水表：全量成本口径（按用途拆解）';

-- 知识库表：agent_id 非空 = 智能体专属库；NULL = 全局知识库（任意会话的资料库选择器可选）
CREATE TABLE IF NOT EXISTS kb (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '知识库ID',
    name        VARCHAR(128) NOT NULL                   COMMENT '知识库名称',
    agent_id    BIGINT       DEFAULT NULL               COMMENT '归属智能体ID（关联 agent.id）；NULL=全局知识库（可被任意会话选用）',
    description VARCHAR(512) DEFAULT NULL               COMMENT '知识库说明',
    doc_count   INT          NOT NULL DEFAULT 0         COMMENT '知识块数量（冗余，便于列表展示，由增删操作维护）',
    chunk_strategy VARCHAR(20) NOT NULL DEFAULT 'recursive' COMMENT '默认分片策略 key：fixed/paragraph/recursive/markdown（新上传文件未指定时继承）',
    chunk_overlap  INT          NOT NULL DEFAULT 60     COMMENT '默认相邻块重叠字符数（0=不重叠，默认60；新上传文件未指定时继承）',
    created_at  DATETIME                                 COMMENT '创建时间',
    updated_at  DATETIME                                 COMMENT '最后更新时间（含知识块变更）',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE=utf8mb4_general_ci COMMENT = '知识库表：智能体专属库（agent_id 非空）+ 全局库（agent_id 为 NULL）';

-- 知识库文件表：文件是知识的上传与管理单元；file_name 与 kb_chunk.source 一致（同库内唯一），
-- 删文件时按 kb_id + file_name 级联删其知识块
CREATE TABLE IF NOT EXISTS kb_file (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '文件ID',
    kb_id       BIGINT       NOT NULL                COMMENT '所属知识库ID（关联 kb.id）',
    file_name   VARCHAR(200) NOT NULL                COMMENT '文件名（含扩展名，同一库内唯一，与 kb_chunk.source 一致）',
    file_type   VARCHAR(20)  DEFAULT NULL            COMMENT '文件类型（扩展名小写，如 pdf/docx/xlsx/txt/md）',
    chunk_strategy VARCHAR(20) NOT NULL DEFAULT 'recursive' COMMENT '分片策略 key：fixed/paragraph/recursive/markdown',
    chunk_overlap INT          NOT NULL DEFAULT 60    COMMENT '相邻知识块重叠字符数（0=不重叠，默认60，供重新分片沿用）',
    size_bytes  BIGINT       NOT NULL DEFAULT 0      COMMENT '文件大小（字节）',
    chunk_count INT          NOT NULL DEFAULT 0      COMMENT '解析出的知识块数量（冗余，由上传/删块操作维护）',
    raw_text    LONGTEXT     DEFAULT NULL            COMMENT '入库时的解析原文（用于不重传文件直接切换分片策略）',
    created_at  DATETIME                              COMMENT '上传时间',
    updated_at  DATETIME                              COMMENT '最后更新时间（同名重传时刷新）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_file (kb_id, file_name),
    INDEX idx_kb_file_kb (kb_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE=utf8mb4_general_ci COMMENT = '知识库文件表：每个知识库维护的文件列表（文件是知识的上传与管理单元）';

-- 知识块表：知识库的最小检索单元（一段文本 + 它的向量，由 Embedding API 生成，存 JSON float 数组）
CREATE TABLE IF NOT EXISTS kb_chunk (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '知识块ID',
    kb_id       BIGINT       NOT NULL                COMMENT '所属知识库ID（关联 kb.id）',
    content     TEXT         NOT NULL                COMMENT '知识块原文',
    source      VARCHAR(200) DEFAULT NULL            COMMENT '来源标注（如文档名/条目名），供引用溯源与展示',
    embedding   MEDIUMTEXT   DEFAULT NULL            COMMENT '内容向量（JSON float 数组），余弦相似度检索用',
    created_at  DATETIME                              COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_kb (kb_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE=utf8mb4_general_ci COMMENT = '知识块表：知识库内容的分块与向量存储';

-- ===========================================================================
-- 第 1 段 · 本轮新增表（2026-10-08：定时任务 / 通知 / 提示词门禁）
-- ===========================================================================

-- 定时任务表：把「一句话 + 一个周期」变成自动执行。刻意不复用 task —— 那是一轮规划的**运行实例**
-- （状态机 RUNNING→DONE/FAILED、单会话单 RUNNING），生命周期以「一次执行」为单位；本表是长期存在的
-- **定义**（周期 / 开关 / 下次触发时间），每次到点才产生一次执行。执行体也不另造：到点后就是
-- 「以本用户身份，在承载会话（conversation_id）里把 prompt 问一遍」，走既有对话链路。
CREATE TABLE IF NOT EXISTS scheduled_task (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    user_id         BIGINT       NOT NULL                COMMENT '归属用户ID，关联 sys_user.id；按用户隔离，越权一律 404',
    name            VARCHAR(100) NOT NULL                COMMENT '任务名称（同时用作首次执行时创建会话的标题）',
    cron            VARCHAR(64)  NOT NULL                COMMENT '触发周期（Spring CronExpression 六段式：秒 分 时 日 月 周）',
    agent_id        BIGINT       DEFAULT NULL            COMMENT '绑定智能体ID，关联 agent.id；NULL=走智能路由',
    prompt          TEXT         NOT NULL                COMMENT '到点时发给对话的提示词（就是一句用户话）',
    conversation_id VARCHAR(64)  DEFAULT NULL            COMMENT '承载本任务的会话ID：首次执行时创建并回填，之后一直复用',
    enabled         TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否启用：0=停用（保留定义与历史，不再触发）',
    notify_on       TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '执行完成后是否发通知（不发通知的定时任务等于跑给人看不见）',
    last_run_at     DATETIME     DEFAULT NULL            COMMENT '上次执行时间',
    last_status     VARCHAR(16)  DEFAULT NULL            COMMENT '上次执行状态：OK / ERROR / RUNNING（RUNNING 是「这一轮正在进行」的展示态；重叠触发由 next_run_at 的 CAS 挡住）',
    last_result     VARCHAR(1000) DEFAULT NULL           COMMENT '上次执行结果摘要（截断；全文在承载会话里）',
    next_run_at     DATETIME     DEFAULT NULL            COMMENT '下次触发时间（由 cron 预先算出并落库，扫描据此取「到点的」）；NULL=未排期',
    created_at      DATETIME                             COMMENT '创建时间',
    updated_at      DATETIME                             COMMENT '最后更新时间',
    PRIMARY KEY (id),
    -- 调度扫描是「WHERE enabled = 1 AND next_run_at <= now ORDER BY next_run_at」：复合索引让过滤与排序一趟走完
    INDEX idx_sched_due (enabled, next_run_at),
    INDEX idx_sched_user (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '定时任务表：周期性地把一句话送进对话链路';

-- 通知表：给「用户不在对话里时发生的事」一个到达用户的出口（定时任务完成 / 阈值告警 / 门禁结论）。
-- 为什么不直接推 SSE：SSE 通道的生命周期绑在一次对话请求上，而通知的产生时机与任何请求无关
-- （后台线程 / 定时器），硬塞长连接既不经济也不可靠（关页面就丢）。落表 + 轮询：简单、可重放、可回看。
-- user_id 为 NULL = 全员广播（系统级）；前端取「本人 + 广播」。
CREATE TABLE IF NOT EXISTS notification (
    id         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    user_id    BIGINT        DEFAULT NULL            COMMENT '目标用户ID，关联 sys_user.id；NULL=全员广播（系统级）',
    type       VARCHAR(24)   NOT NULL                COMMENT '类型：SYSTEM=系统 / SCHEDULED_TASK=定时任务完成 / ALERT=阈值告警 / PROMPT_GATE=提示词门禁',
    level      VARCHAR(8)    NOT NULL DEFAULT 'INFO' COMMENT '级别：INFO / WARN / ERROR（决定前端配色与外发门槛）',
    title      VARCHAR(200)  NOT NULL                COMMENT '标题（列表一行一句）',
    content    VARCHAR(1000) DEFAULT NULL            COMMENT '正文（被通知事件的摘要，已截断）',
    ref_type   VARCHAR(24)   DEFAULT NULL            COMMENT '关联对象类型：CONVERSATION / SCHEDULED_TASK / PROMPT_SNAPSHOT / ALERT_RULE',
    ref_id     VARCHAR(64)   DEFAULT NULL            COMMENT '关联对象ID（字符串，兼容自增主键与业务 UUID）',
    read_at    DATETIME      DEFAULT NULL            COMMENT '已读时间；NULL=未读',
    created_at DATETIME                              COMMENT '记录时间',
    PRIMARY KEY (id),
    INDEX idx_notify_user (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '通知表：用户不在对话里时发生之事的送达出口';

-- 提示词门禁快照表：把「某一版 prompts.yaml 的评测结论」固化下来，回答「改了提示词变好还是变差」。
-- 与 eval_result 的分工：那张表只记「某一批跑了什么」，不知道跑的是哪一版提示词；本表用内容指纹
-- (fingerprint) 把提示词与批次绑定，改没改 / 跑没跑 / 结论如何一眼可判。
-- 不认识 prompt 语义：指纹只回答「变了没有」；注释类改动同样会让指纹变化（注释也会进模型上下文）。
CREATE TABLE IF NOT EXISTS prompt_snapshot (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    fingerprint  VARCHAR(64)  DEFAULT NULL            COMMENT 'prompts.yaml 内容的 SHA-256 定长前缀（只回答「变了没有」，不回答「改了哪句」）；NULL=读不到文件',
    batch_id     VARCHAR(32)  DEFAULT NULL            COMMENT '关联 eval_result.batch_id；NULL=尚未跑批（PENDING）',
    total        INT          DEFAULT NULL            COMMENT '本次跑批用例总数',
    passed       INT          DEFAULT NULL            COMMENT '通过数',
    failed       INT          DEFAULT NULL            COMMENT '失败数（不含配置错误）',
    config_error INT          DEFAULT NULL            COMMENT '用例配置错误数（用例自己写错，不是提示词问题）',
    broken_count INT          DEFAULT NULL            COMMENT '相对上一批「上批过、本批败」的用例数（>0 即劣化）',
    fixed_count  INT          DEFAULT NULL            COMMENT '相对上一批「上批败、本批过」的用例数',
    verdict      VARCHAR(16)  NOT NULL                COMMENT '结论：PENDING=已变更待验证 / PASS=通过 / DEGRADED=出现 broken / STILL_FAILED=无新坏但仍有未过 / ERROR=跑批本身失败',
    detail_json  TEXT         DEFAULT NULL            COMMENT '明细 JSON：{broken:[用例名],fixed:[用例名],error:"..."}',
    created_at   DATETIME                             COMMENT '记录时间',
    PRIMARY KEY (id),
    INDEX idx_snapshot_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '提示词改动门禁快照表：绑定提示词指纹与评测结论';

-- ---------- 存量库补列 / 补索引（幂等规则见文件头） ----------
-- conversation：绑定智能体 / 绑定来源 / 滚动摘要
ALTER TABLE conversation ADD COLUMN agent_id BIGINT DEFAULT NULL COMMENT '绑定的智能体ID，关联 agent.id（自增主键），为空表示默认助手';
ALTER TABLE conversation ADD COLUMN agent_bind_source VARCHAR(16) DEFAULT NULL COMMENT '智能体绑定来源：EXPLICIT=用户显式选择（保持粘住），HANDOFF=智能体主动转交（同样粘住），CLARIFY=追问流程临时绑定（允许话题切换时解绑）；空=未绑定';
ALTER TABLE conversation ADD COLUMN summary TEXT DEFAULT NULL COMMENT '较早对话的滚动摘要（长期记忆）';
ALTER TABLE conversation ADD COLUMN summarized_count INT DEFAULT 0 COMMENT '已被摘要覆盖的最旧消息条数';
ALTER TABLE conversation ADD COLUMN core_facts TEXT DEFAULT NULL COMMENT '用户核心信息（旧版事实归档：逐条事实已迁到 conversation_fact，本列保留原文不再自动更新）';

-- agent：参数清单
ALTER TABLE agent ADD COLUMN param_schema TEXT DEFAULT NULL COMMENT '参数清单（JSON）：声明执行所需参数，用于对话中的追问/参数补全';

-- conversation：规划模式
ALTER TABLE conversation ADD COLUMN planner TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否规划模式会话：1=动态规划器（运行时由 LLM 规划多智能体步骤），0=普通/智能体会话';

-- kb：默认分片策略 / 默认重叠字数（新上传文件未指定时继承）
ALTER TABLE kb ADD COLUMN chunk_strategy VARCHAR(20) NOT NULL DEFAULT 'recursive' COMMENT '默认分片策略 key：fixed/paragraph/recursive/markdown（新上传文件未指定时继承）';
ALTER TABLE kb ADD COLUMN chunk_overlap INT NOT NULL DEFAULT 60 COMMENT '默认相邻块重叠字符数（0=不重叠，默认60；新上传文件未指定时继承）';

-- kb_file：同一组两列。schema.sql 的 kb_file 一直带这两列，本脚本此前漏补 ——
-- 存量库缺列会让知识库文件的读取 / 重新分片报 Unknown column
ALTER TABLE kb_file ADD COLUMN chunk_strategy VARCHAR(20) NOT NULL DEFAULT 'recursive' COMMENT '分片策略 key：fixed/paragraph/recursive/markdown（本文件入库时实际采用）';
ALTER TABLE kb_file ADD COLUMN chunk_overlap INT NOT NULL DEFAULT 60 COMMENT '相邻知识块重叠字符数（0=不重叠，默认60；重新分片时沿用）';

-- conversation：RAG 纯开关。旧设计 rag_kb_id（显式选库）已废弃并移除；存量库若残留该列，
-- 可执行 DROP COLUMN rag_kb_id 清理
ALTER TABLE conversation ADD COLUMN rag_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '会话级 RAG 开关：1=每轮对话自动检索资料库（通用知识库 + 路由到智能体时其专属库）并把命中内容注入上下文；0=不使用 RAG（仅靠模型自身知识）';

-- chat_message：附件元数据。只服务历史展示，不进记忆（DbChatMemory 只读 content）⇒ 对 token 零影响
ALTER TABLE chat_message ADD COLUMN attachments_json TEXT DEFAULT NULL COMMENT '本轮附件元数据（JSON数组：type/filename/storedName/size）；仅用于历史展示，不参与记忆读取';

-- agent：工具装配。此前所有智能体共享全局工具池（ChatComposer 挂 ToolRegistry 全量工具），
-- 翻译 / 闲聊类也会看到 SQL、天气等无关工具。三态语义见列注释（NULL 兼容存量，无需迁移）
ALTER TABLE agent ADD COLUMN tools_json VARCHAR(1000) DEFAULT NULL COMMENT '工具装配（JSON数组）：NULL=不限制（挂载全部工具）；[]=不挂任何工具；["工具名",...]=仅挂白名单内工具';

-- chat_message：RAG 引用来源。与 attachments_json 同理，只服务前端角标，不进记忆
ALTER TABLE chat_message ADD COLUMN citations_json TEXT DEFAULT NULL COMMENT '本轮 RAG 引用来源（JSON数组：index/kbName/source/chunkId/score）；仅用于历史展示，不参与记忆读取';

-- agent_trace：本轮实际检索问题（多轮查询改写产物）。单独留痕是为了让「RAG 没命中」可归因 ——
-- 究竟是改写跑偏了，还是知识库里确实没有
ALTER TABLE agent_trace ADD COLUMN retrieval_query VARCHAR(1000) DEFAULT NULL COMMENT '本轮实际用于知识库检索的问题（多轮查询改写产物）；NULL=未改写（未开RAG/首轮/关闭改写/原话已自包含）' AFTER user_message;

-- conversation：所属用户（会话按用户隔离；越权一律 404，不泄漏他人会话是否存在）。
-- 存量会话该列为 NULL ⇒ 对所有人不可见；若要归属给某用户：
--   UPDATE conversation SET user_id = <用户ID> WHERE user_id IS NULL;（多用户环境勿盲目全量赋值）
ALTER TABLE conversation ADD COLUMN user_id BIGINT DEFAULT NULL COMMENT '所属用户ID，关联 sys_user.id；会话按用户隔离，仅本人可见（NULL=历史遗留，不归属任何用户）';

-- 会话列表按 user_id 过滤
ALTER TABLE conversation ADD INDEX idx_user (user_id);

-- llm_usage：补上 PLAN 用途（PlannerService.plan 此前没采集成本，属全量成本口径漏项）。
-- 用 MODIFY 而非 ADD：它幂等，重复执行只更新列注释，不改类型，不丢数据
ALTER TABLE llm_usage MODIFY COLUMN purpose VARCHAR(24) NOT NULL COMMENT '调用用途：ROUTE=智能路由 / CLARIFY=参数抽取 / REWRITE=查询改写 / PLAN=任务规划 / VISION=视觉识别 / MEMORY_MERGE=记忆合并 / SUBAGENT=智能体转交 / RECALL=跨会话召回 / REVIEW=并行评审 / SELF_EVAL=回答自评';

-- conversation：「先看计划」开关。开启后规划只产出计划并暂停，点「执行计划」走
-- POST /api/chat/task/resume —— 复用断点续跑通路，不新增执行入口
ALTER TABLE conversation ADD COLUMN planner_confirm TINYINT(1) NOT NULL DEFAULT 0 COMMENT '规划模式「先看计划」开关：1=规划只产出计划并暂停，用户确认后才执行；0=规划后直接执行（默认）';

-- task_step：步骤审批点两列。执行到该步整条流水线暂停（剩余 PENDING、task 仍 RUNNING），
-- 批准后走既有续跑通路，不新增第二套执行逻辑。
-- 审批刻意不塞进 status 状态机：status 说「跑到哪了」，审批说「允不允许跑」，两者正交
ALTER TABLE task_step ADD COLUMN approval_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT '该步执行前是否需要用户审批：1=执行到此步先暂停等待批准（计划阶段可改），0=直接执行';
ALTER TABLE task_step ADD COLUMN approved TINYINT(1) NOT NULL DEFAULT 0 COMMENT '审批是否已通过：1=已批准可执行；0=未批准。仅当 approval_required=1 时有约束意义';

-- 并行评审：LLM 从智能体库选 2~3 个候选并行作答、裁决者综合。与 planner 互斥（同为编排形态）；
-- 候选只走 SSE 展示、不进记忆
ALTER TABLE conversation ADD COLUMN review_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '并行评审开关：1=多候选智能体并行作答 + 裁决者综合；0=普通单智能体回答（默认）。与 planner 互斥';

-- 跨会话搜索：LLM 抽关键词，在本用户其他会话的历史消息里召回并注入。
-- 归属靠 JOIN conversation 判定（chat_message 无 user_id 列），天然排除当前会话
ALTER TABLE conversation ADD COLUMN cross_session TINYINT(1) NOT NULL DEFAULT 0 COMMENT '跨会话搜索开关：1=检索本用户其他会话的历史消息并注入；0=不检索（默认）';

-- chat_message：对话分支版本三列。编辑重发 / 重新生成不再删掉旧的那一轮，而是同轮多版本并存，
-- 消息上挂「1/2 ‹ ›」原地翻看。
-- 读取侧（getHistory / 记忆窗口 / 消息计数 / 摘要切片）统一只认 turn_group_id IS NULL OR turn_active = 1，
-- 故未分叉行行为同改造前 —— 这也是刻意不做数据回填的原因
ALTER TABLE chat_message ADD COLUMN turn_group_id VARCHAR(64) DEFAULT NULL COMMENT '对话分支组ID（UUID）：同一轮提问的多个「版本」（编辑重发 / 重新生成）共用；NULL=从未分叉，该轮只有一个版本。刻意不做回填——NULL 即旧语义';
ALTER TABLE chat_message ADD COLUMN turn_version INT DEFAULT NULL COMMENT '该分支组内的版本序号，从 1 开始递增；版本号连续，故前端可直接令 versionCount = 当前 version';
ALTER TABLE chat_message ADD COLUMN turn_active TINYINT(1) NOT NULL DEFAULT 1 COMMENT '该版本是否为当前生效版本：1=生效（会被读取、会注入记忆）；同一组内至多一个版本为 1。未分叉的行恒为 1';

-- 分支读取（按组筛当前版本）与版本计数（GROUP BY turn_group_id）共用
ALTER TABLE chat_message ADD INDEX idx_conv_turn (conversation_id, turn_group_id);

-- chat_message：「不参与记忆」标记。该条不进记忆窗口也不进摘要，历史里照常看得见 ——
-- 用来把粘贴的大段日志、无关闲聊排除出后续上下文。
-- 记忆侧三处（getRecentHistory / countMessages / getMessagesRange）追加 memory_excluded = 0；
-- 展示侧（getHistory / 导出）刻意不过滤：「不进记忆」不等于「删掉」。
-- 必须与 turn_active 同口径，否则摘要水位与实际注入区间错位
ALTER TABLE chat_message ADD COLUMN memory_excluded TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否被用户标记为「不参与记忆」：1=该条既不进记忆窗口、也不进摘要（历史仍可见）；0=正常参与（默认）';

-- agent_trace：本轮记忆注入构成快照，回答「模型为什么记得 / 不记得」。纯旁路，采集失败留 NULL
ALTER TABLE agent_trace ADD COLUMN memory_json TEXT DEFAULT NULL COMMENT '本轮注入的记忆构成快照（JSON：窗口逐条 role/preview/chars + 长期摘要/长期事实字符数）；NULL=未采集（旁路观测，失败即留空）';

-- task：用户中途喊停信号。为何落库而不是内存标志 —— 与「单会话单 RUNNING」这条既有不变量同处
-- task 表，不引入第二种状态源；且执行体跑在 SSE 的异步线程上，「另一个 HTTP 请求改内存标志」在
-- 重启 / 多实例下不可靠。清零责任在 resumeTask 开头 clearPause，否则点「继续执行」会立刻被暂停位拦住
ALTER TABLE task ADD COLUMN pause_requested TINYINT(1) NOT NULL DEFAULT 0 COMMENT '用户中途喊停的信号位：1=执行循环在下一个「层边界」停止推进（正在跑的那一层照常跑完），剩余步骤保持 PENDING、任务保持 RUNNING；由续跑入口（resumeTask）负责清零，故不会把用户永久挡在门外';

-- agent_trace：线上回答自评（元认知）两列。为何 score 单独成列而不全塞 JSON —— 可观测面板的
-- 「低分轮次」要按分过滤与聚合，JSON 里解析不出索引。NULL = 未自评（采样未命中 / 回答过短 /
-- 调用失败），必须与「评了低分」分开，混在一起会把「没覆盖到」读成「质量差」
ALTER TABLE agent_trace ADD COLUMN self_eval_score TINYINT DEFAULT NULL COMMENT '模型对回答的自评分 1~5；NULL=未自评（按比例采样未命中 / 回答过短 / 调用失败）';
ALTER TABLE agent_trace ADD COLUMN self_eval_json TEXT DEFAULT NULL COMMENT '自评明细 JSON：score/answered/grounded/issues/comment/trigger；NULL=未自评';

-- conversation：澄清（参数补全）状态落库。此前「已问几次 / 已确认哪些参数 / 原始请求是什么」靠
-- 每轮扫最近 50 条 chat_message 重放推导（ParamFillingService#countClarifyStreak + clarifyScopedHistory），
-- 三种情况会算歪：① 历史超窗被摘要压缩，原始请求滑出窗口，参数抽取退化成看最近几条；
-- ② 消息被标「不参与记忆」，重放侧看不到，追问次数凭空少一次；③ 长会话里最后一轮正式回答落在
-- 50 条之外，连续追问段的起点判断不到。落显式状态后直接读，不再猜；重放逻辑仍作兜底保留。
-- 结构（Hutool JSON）：{agentId,asked,request,question,params}；agentId 用来判断「换智能体就作废」
ALTER TABLE conversation ADD COLUMN clarify_state TEXT DEFAULT NULL COMMENT '参数补全（澄清追问）的显式状态 JSON：{agentId,asked,request,question,params}；NULL=无进行中的追问。落库以摆脱「每轮扫历史重放推导」——历史被摘要压缩或标记不参与记忆后，重放会算错已问次数与已确认参数';

-- conversation_fact：事实的生命周期三件套（置信度 / 有效期 / 取代状态）。
-- 此前一条事实写进来就永远是「现在时」：「下周三要交的报告」在三个月后仍被当成待办注入上下文。
-- 三列各治一种病 —— expires_at 让有时限的事实自动退场，status+superseded_by 让被新说法顶替的旧值
-- 留档而不注入，confidence 让被反复确认的条目浮到高位（面板据此排序）。
-- 存量行：confidence 取默认 3（中性），expires_at 为 NULL（永不过期，与原行为一致），
-- status 取默认 'ACTIVE'（继续注入，不会因为加了列而让老数据凭空消失）。
ALTER TABLE conversation_fact ADD COLUMN confidence TINYINT NOT NULL DEFAULT 3 COMMENT '置信度1~5：用户手写起始5、模型整理起始3，此后每次合并里仍被列出就+1（封顶5）；面板据此排序';
ALTER TABLE conversation_fact ADD COLUMN expires_at DATETIME DEFAULT NULL COMMENT '有效期：NULL=永不过期；过期的条目不再注入但仍留在面板（标「已过期」），删不删由用户定';
ALTER TABLE conversation_fact ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE=生效中（参与注入与合并）/ SUPERSEDED=已被同主题的新说法取代（不注入，留档可查）';
ALTER TABLE conversation_fact ADD COLUMN superseded_by BIGINT DEFAULT NULL COMMENT '被哪一条取代（status=SUPERSEDED 时有值），用于面板「被……取代」回链';

-- conversation_fact：留档行按会话+状态过滤（合并末尾的「只留最近 20 条取代记录」用）
ALTER TABLE conversation_fact ADD INDEX idx_fact_status (conversation_id, status);
