-- ===========================================================================
-- 第 0 段 · 补齐「功能扩展引入、但此前只写进 schema.sql 的新表」
--   存量库并不跑 schema.sql（那是建库权威定义），只跑本脚本补列。此前这些表只写进了 schema.sql，
--   导致存量库执行本脚本后根本不存在它们 —— 反馈 / 转用例 / 评测跑批 / 规划持久化 / 规划模板全部 500
--   （与 10-08 修的 conversation_fact 同一类欠账：单个表漏补，功能整块不可用）。
--   必须放在文件最前：本脚本后面有 ALTER TABLE task / task_step，若这两张表不存在会先失败在这里。
--   定义与 schema.sql 逐字一致（两脚本同一张表结构必须相同）。
-- 注：CREATE TABLE IF NOT EXISTS，重复执行安全。
-- ===========================================================================

-- 规划任务表（schema.sql 同款）：一轮规划 = 一条 task + N 条 task_step，落库支撑断点续跑。
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

-- 规划任务步骤表（schema.sql 同款）
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

-- 规划模板表（schema.sql 同款）：把跑顺的规划步骤骨架沉淀为可复用资产，按 user_id 隔离。
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

-- 提示词回归评测结果表（schema.sql 同款）：一次跑批每个用例一行，只保留最近 20 批。
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

-- 消息反馈表（schema.sql 同款）：B「反馈 → 回归用例」的入口。一人对一条消息一票。
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

-- 库内回归用例表（schema.sql 同款）：承接运行时新增的用例，与 eval-cases.yaml 合并参与跑批。
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
-- ---------------------------------------------------------------------------

-- 兼容已存在旧表：补充 agent_id 列（表已含该列时因 continue-on-error 被忽略，不报错）
ALTER TABLE conversation ADD COLUMN agent_id BIGINT DEFAULT NULL COMMENT '绑定的智能体ID，关联 agent.id（自增主键），为空表示默认助手';

-- 兼容已存在旧表：补充绑定来源列（列已存在时因 continue-on-error 被忽略，不报错）
ALTER TABLE conversation ADD COLUMN agent_bind_source VARCHAR(16) DEFAULT NULL COMMENT '智能体绑定来源：EXPLICIT=用户显式选择，CLARIFY=追问流程临时绑定；空=未绑定';

-- 兼容已存在旧表：补充滚动摘要/核心记忆列（列已存在时因 continue-on-error 被忽略，不报错）
ALTER TABLE conversation ADD COLUMN summary TEXT DEFAULT NULL COMMENT '较早对话的滚动摘要（长期记忆）';
ALTER TABLE conversation ADD COLUMN summarized_count INT DEFAULT 0 COMMENT '已被摘要覆盖的最旧消息条数';
ALTER TABLE conversation ADD COLUMN core_facts TEXT DEFAULT NULL COMMENT '用户核心信息（旧版事实归档：逐条事实已迁到 conversation_fact，本列保留原文不再自动更新）';

-- 兼容已存在旧表：补充 agent 表的参数清单列（列已存在时因 continue-on-error 被忽略，不报错）
ALTER TABLE agent ADD COLUMN param_schema TEXT DEFAULT NULL COMMENT '参数清单（JSON）：声明执行所需参数，用于对话中的追问/参数补全';

-- 兼容已存在旧表：补充 conversation 表的规划模式列（列已存在时因 continue-on-error 被忽略，不报错）
ALTER TABLE conversation ADD COLUMN planner TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否规划模式会话：1=动态规划器（运行时由 LLM 规划多智能体步骤），0=普通/智能体会话';

-- 兼容已存在旧表：kb 表补充「默认分片策略 / 默认重叠字数」列（知识库设置：新上传文件未指定时继承；列已存在时被忽略）
ALTER TABLE kb ADD COLUMN chunk_strategy VARCHAR(20) NOT NULL DEFAULT 'recursive' COMMENT '默认分片策略 key：fixed/paragraph/recursive/markdown（新上传文件未指定时继承）';
ALTER TABLE kb ADD COLUMN chunk_overlap INT NOT NULL DEFAULT 60 COMMENT '默认相邻块重叠字符数（0=不重叠，默认60；新上传文件未指定时继承）';

-- 兼容「kb_file 已存在但早于分片策略版本」的库：补充同一组两列（列已存在时 MySQL 报 1060，忽略即可）。
-- schema.sql 的 kb_file 一直带这两列，本脚本此前漏补 —— 存量库缺列会让知识库文件的读取/重新分片报 Unknown column。
ALTER TABLE kb_file ADD COLUMN chunk_strategy VARCHAR(20) NOT NULL DEFAULT 'recursive' COMMENT '分片策略 key：fixed/paragraph/recursive/markdown（本文件入库时实际采用）';
ALTER TABLE kb_file ADD COLUMN chunk_overlap INT NOT NULL DEFAULT 60 COMMENT '相邻知识块重叠字符数（0=不重叠，默认60；重新分片时沿用）';

-- 兼容已存在旧表：conversation 表 RAG 纯开关（rag_enabled=1 时自动检索通用知识库 + 路由到智能体时其专属库；0=不检索）。
-- 旧设计 rag_kb_id（显式选库）已废弃并从脚本移除：schema.sql 不含该列；若存量库仍存在该历史残留列，可执行 DROP COLUMN rag_kb_id 清理。列已存在时被忽略
ALTER TABLE conversation ADD COLUMN rag_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '会话级 RAG 开关：1=每轮对话自动检索资料库（通用知识库 + 路由到智能体时其专属库）并把命中内容注入上下文；0=不使用 RAG（仅靠模型自身知识）';

-- 兼容已存在旧表：chat_message 表补充「附件元数据」列（列已存在时被忽略）。
-- 仅存附件展示元数据（type/filename/storedName/size），不含附件正文，也不参与记忆读取——DbChatMemory.get 只读 content，
-- 因此本列对 LLM 上下文与 token 零影响；历史接口 (/api/chat/history) 单独读取用于渲染缩略图/下载链接。
ALTER TABLE chat_message ADD COLUMN attachments_json TEXT DEFAULT NULL COMMENT '本轮附件元数据（JSON数组：type/filename/storedName/size）；仅用于历史展示，不参与记忆读取';

-- 兼容已存在旧表：agent 表补充「工具装配」列（列已存在时被忽略），实现「按智能体装配工具」——
-- 此前所有智能体共享全局工具池（ChatComposer.decorateRequest 挂载 ToolRegistry 全量工具），
-- 导致翻译/闲聊类智能体也会看到 SQL、天气等无关工具。语义（注意 NULL 的兼容含义）：
--   NULL / ''  —— 不限制，挂载全部工具（存量智能体保持原行为，无需迁移）
--   '[]'       —— 不挂任何工具（纯聊天型智能体）
--   '["queryWeatherByDate","chart_echarts"]' —— 仅挂白名单内工具（按 ToolDefinition.name 匹配）
ALTER TABLE agent ADD COLUMN tools_json VARCHAR(1000) DEFAULT NULL COMMENT '工具装配（JSON数组）：NULL=不限制（挂载全部工具）；[]=不挂任何工具；["工具名",...]=仅挂白名单内工具';

-- 兼容已存在旧表：chat_message 表补充「RAG 引用来源」列（列已存在时被忽略）。
-- 仅 assistant 消息可能带值；与 attachments_json 同理，只服务前端引用角标 / 来源列表的展示，
-- 不含正文，也不参与记忆读取（DbChatMemory.get 只读 content），对 LLM 上下文与 token 零影响。
ALTER TABLE chat_message ADD COLUMN citations_json TEXT DEFAULT NULL COMMENT '本轮 RAG 引用来源（JSON数组：index/kbName/source/chunkId/score）；仅用于历史展示，不参与记忆读取';

-- 兼容「agent_trace 已存在」的库：补充「本轮实际检索问题」列（列已存在时 MySQL 报 1060，忽略即可）。
-- 值为多轮查询改写（指代消解）的产物；NULL 表示未改写（未开 RAG / 首轮无历史 / 关闭改写 / 模型判定原话已自包含）。
-- 单独留痕是为了让「RAG 没命中」可归因：究竟是改写跑偏了，还是知识库里确实没有。
ALTER TABLE agent_trace ADD COLUMN retrieval_query VARCHAR(1000) DEFAULT NULL COMMENT '本轮实际用于知识库检索的问题（多轮查询改写产物）；NULL=未改写（未开RAG/首轮/关闭改写/原话已自包含）' AFTER user_message;

-- 兼容已存在旧表：conversation 表补充「所属用户」列（列已存在时被忽略），实现「会话按用户隔离」——
-- 每个用户只能列出 / 打开 / 修改 / 删除自己的会话，越权访问按 404 处理（不泄漏他人会话是否存在）。
-- 存量会话该列为 NULL，不归属任何用户，对所有人不可见；如需把存量会话归给某个用户，
-- 可执行：UPDATE conversation SET user_id = <用户ID> WHERE user_id IS NULL;（谨慎：一旦有多个用户请勿盲目全量赋值）
ALTER TABLE conversation ADD COLUMN user_id BIGINT DEFAULT NULL COMMENT '所属用户ID，关联 sys_user.id；会话按用户隔离，仅本人可见（NULL=历史遗留，不归属任何用户）';

-- 会话列表按 user_id 过滤，补索引（索引已存在时被忽略）
ALTER TABLE conversation ADD INDEX idx_user (user_id);

-- 成本流水新增「任务规划」用途（PlannerService.plan 此前没有采集成本，属全量成本口径的漏项）。
-- MODIFY 是幂等的：重复执行只更新列注释，不改类型，不丢数据。
ALTER TABLE llm_usage MODIFY COLUMN purpose VARCHAR(24) NOT NULL COMMENT '调用用途：ROUTE=智能路由 / CLARIFY=参数抽取 / REWRITE=查询改写 / PLAN=任务规划 / VISION=视觉识别 / MEMORY_MERGE=记忆合并 / SUBAGENT=智能体转交 / RECALL=跨会话召回 / REVIEW=并行评审 / SELF_EVAL=回答自评';

-- 兼容已存在旧表：conversation 表补充「先看计划」开关（列已存在时被忽略）。
-- 规划模式默认「规划完直接执行」；开启本开关后，规划只产出计划并暂停（计划已落库为 RUNNING 任务，步骤全 PENDING），
-- 用户在计划卡片上点「执行计划」才走 POST /api/chat/task/resume 跑剩余步骤——复用断点续跑这条既有通路，不新增执行入口。
ALTER TABLE conversation ADD COLUMN planner_confirm TINYINT(1) NOT NULL DEFAULT 0 COMMENT '规划模式「先看计划」开关：1=规划只产出计划并暂停，用户确认后才执行；0=规划后直接执行（默认）';
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- task_step 补充「步骤审批点」两列（功能扩展，非新表）：存量库执行这一段即可。
-- 用途：给计划中的某些步骤打上「执行前需我确认」标记；执行到该步时整条流水线暂停（剩余步骤保持 PENDING、
-- task 仍 RUNNING），用户批准后走既有的断点续跑通路继续跑 —— 不新增第二套执行逻辑。
-- 审批刻意不塞进 status 状态机：status 表达「这一步跑到哪了」，审批表达「允不允许跑」，两者正交。
-- 注：MySQL 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报 `Duplicate column name`（错误码 1060），可忽略。
-- ---------------------------------------------------------------------------
ALTER TABLE task_step ADD COLUMN approval_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT '该步执行前是否需要用户审批：1=执行到此步先暂停等待批准（计划阶段可改），0=直接执行';
ALTER TABLE task_step ADD COLUMN approved TINYINT(1) NOT NULL DEFAULT 0 COMMENT '审批是否已通过：1=已批准可执行；0=未批准。仅当 approval_required=1 时有约束意义';

-- ---------------------------------------------------------------------------
-- conversation 补充「并行评审」「跨会话搜索」两个开关（功能扩展，非新表）：存量库执行这一段即可。
-- 两列都是会话级开关，默认 0（关闭），行为与改造前完全一致。
-- 注：MySQL 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报 `Duplicate column name`（错误码 1060），可忽略。
-- ---------------------------------------------------------------------------
-- 并行评审：开启后本轮由 LLM 从智能体库里选 2~3 个候选并行作答，再由裁决者综合成最终回答。
-- 与 planner 互斥（两者都是编排形态）；候选内容只走 SSE 展示、不进会话记忆。
ALTER TABLE conversation ADD COLUMN review_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '并行评审开关：1=多候选智能体并行作答 + 裁决者综合；0=普通单智能体回答（默认）。与 planner 互斥';
-- 跨会话搜索：开启后本轮用 LLM 抽取关键词，在本用户「其他会话」的历史消息里做关键词召回并注入上下文。
-- 归属隔离靠 JOIN conversation 判定（chat_message 没有 user_id 列），且天然排除当前会话。
ALTER TABLE conversation ADD COLUMN cross_session TINYINT(1) NOT NULL DEFAULT 0 COMMENT '跨会话搜索开关：1=检索本用户其他会话的历史消息并注入；0=不检索（默认）';

-- ---------------------------------------------------------------------------
-- chat_message 补充「对话分支版本」三列（功能扩展，非新表）：存量库执行这一段即可。
-- 用途：编辑重发 / 重新生成不再「删掉旧的那一轮」，而是把同一轮提问的多个版本都留下，
-- 消息上挂「1/2 ‹ ›」切换器原地翻看 —— 旧思路不再当场消失。
-- 三列的关系：turn_group_id 标识「这是同一轮的哪几个版本」，turn_version 是组内序号，turn_active 标记当前生效的那一版。
-- 读取侧（getHistory / 记忆窗口 / 消息计数 / 摘要切片）统一只认「turn_group_id IS NULL OR turn_active = 1」，
-- 因此未分叉的行（turn_group_id 为 NULL）行为与改造前完全一致 —— 这也是刻意不做数据回填的原因。
-- 注：MySQL 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报 `Duplicate column name`（错误码 1060），可忽略。
-- ---------------------------------------------------------------------------
ALTER TABLE chat_message ADD COLUMN turn_group_id VARCHAR(64) DEFAULT NULL COMMENT '对话分支组ID（UUID）：同一轮提问的多个「版本」（编辑重发 / 重新生成）共用；NULL=从未分叉，该轮只有一个版本。刻意不做回填——NULL 即旧语义';
ALTER TABLE chat_message ADD COLUMN turn_version INT DEFAULT NULL COMMENT '该分支组内的版本序号，从 1 开始递增；版本号连续，故前端可直接令 versionCount = 当前 version';
ALTER TABLE chat_message ADD COLUMN turn_active TINYINT(1) NOT NULL DEFAULT 1 COMMENT '该版本是否为当前生效版本：1=生效（会被读取、会注入记忆）；同一组内至多一个版本为 1。未分叉的行恒为 1';
-- 分支读取（按组筛当前版本）与版本计数（GROUP BY turn_group_id）共用该索引（索引已存在时被忽略）
ALTER TABLE chat_message ADD INDEX idx_conv_turn (conversation_id, turn_group_id);
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- chat_message 补充「不参与记忆」标记（功能扩展，非新表）：存量库执行这一段即可。
-- 用途：单条消息可被标记为「不参与记忆」—— 该条既不进记忆窗口（DbChatMemory.get）、也不进滚动摘要
-- （MemoryMergeService），历史里照常看得见。用于把粘贴的大段日志、无关闲聊排除出后续上下文。
-- 读取口径：记忆侧三处（getRecentHistory / countMessages / getMessagesRange）统一追加 memory_excluded = 0；
-- 展示侧（getHistory / 导出）刻意<b>不</b>过滤 —— 「不进记忆」不等于「删掉」。
-- 与 turn_active 的关系：两者都是在「可见消息序列」上做过滤，必须同口径，否则摘要水位与实际注入区间错位。
-- 注：MySQL 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报 `Duplicate column name`（错误码 1060），可忽略。
-- ---------------------------------------------------------------------------
ALTER TABLE chat_message ADD COLUMN memory_excluded TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否被用户标记为「不参与记忆」：1=该条既不进记忆窗口、也不进摘要（历史仍可见）；0=正常参与（默认）';

-- ---------------------------------------------------------------------------
-- agent_trace 补充「本轮记忆注入构成」快照列（功能扩展，非新表）：存量库执行这一段即可。
-- 用途：把「这一轮到底往 prompt 里塞了哪些历史」留痕，回答「模型为什么记得 / 不记得」。
-- 旁路数据：采集失败一律留 NULL，不影响对话，也不影响既有追踪字段。
-- 注：MySQL 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报 `Duplicate column name`（错误码 1060），可忽略。
-- ---------------------------------------------------------------------------
ALTER TABLE agent_trace ADD COLUMN memory_json TEXT DEFAULT NULL COMMENT '本轮注入的记忆构成快照（JSON：窗口逐条 role/preview/chars + 长期摘要/长期事实字符数）；NULL=未采集（旁路观测，失败即留空）';
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- task 补充「用户中途喊停」信号列（功能扩展，非新表）：存量库执行这一段即可。
-- 用途：规划跑到一半时用户可以喊停 —— 执行循环在下一个「层边界」停止推进，剩余步骤保持 PENDING、
-- 任务保持 RUNNING，用户改完计划再点「继续执行」走现成的断点续跑通路（不新增执行入口）。
-- 为什么是库里的列而不是内存信号：与「单会话单 RUNNING」这条既有不变量放在同一处（task 表），
-- 不引入第二种状态源；且执行体跑在 SSE 的异步线程上，「另一个 HTTP 请求改内存标志」在重启/多实例下不可靠。
-- 清零责任在续跑入口（resumeTask 开头 clearPause）：否则用户点了「继续执行」会立刻又被自己的暂停位拦住。
-- 注：MySQL 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报 `Duplicate column name`（错误码 1060），可忽略。
-- ---------------------------------------------------------------------------
ALTER TABLE task ADD COLUMN pause_requested TINYINT(1) NOT NULL DEFAULT 0 COMMENT '用户中途喊停的信号位：1=执行循环在下一个「层边界」停止推进（正在跑的那一层照常跑完），剩余步骤保持 PENDING、任务保持 RUNNING；由续跑入口（resumeTask）负责清零，故不会把用户永久挡在门外';

-- ---------------------------------------------------------------------------
-- 功能扩展 D：线上回答自评（元认知）—— agent_trace 补两列。
-- 为什么单独存 self_eval_score 而不是全塞 JSON：可观测面板的「低分轮次」要按分过滤与聚合，
-- JSON 里解析不出索引。NULL 的语义是「未自评」（采样未命中 / 回答过短 / 调用失败），
-- 与「评了低分」必须能分开 —— 前者是没测，后者是测出来不好，混在一起会把「没覆盖到」读成「质量差」。
-- 注：MySQL 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报 `Duplicate column name`（1060），可忽略。
-- ---------------------------------------------------------------------------
ALTER TABLE agent_trace ADD COLUMN self_eval_score TINYINT DEFAULT NULL COMMENT '模型对回答的自评分 1~5；NULL=未自评（按比例采样未命中 / 回答过短 / 调用失败）';
ALTER TABLE agent_trace ADD COLUMN self_eval_json TEXT DEFAULT NULL COMMENT '自评明细 JSON：score/answered/grounded/issues/comment/trigger；NULL=未自评';

-- ---------------------------------------------------------------------------
-- 功能扩展 C：澄清（参数补全）状态落库（存量库补一列，与 schema.sql 同款）。
-- 此前追问的「已问几次 / 已确认哪些参数 / 原始请求是什么」全靠每轮扫最近 50 条 chat_message 重放推导
-- （ParamFillingService#countClarifyStreak + clarifyScopedHistory）。这在三种情况下会算歪：
--   ① 历史超窗后被摘要压缩 —— 原始请求那条消息滑出窗口，「任务范围」丢失，参数抽取退化成看最近几条；
--   ② 消息被标「不参与记忆」—— 重放侧看不到，追问次数凭空少一次，可能提前转主模型或重复追问；
--   ③ 长会话里最后一轮正式回答落在 50 条之外 —— 连续追问段的起点判断不到。
-- 落一列显式状态后，上述三项直接读，不再猜；历史仍然保留（重放逻辑作为兜底保留，见 ParamFillingService）。
--
--   结构（Hutool JSON）：{"agentId":3,"asked":2,"request":"原文请求","question":"最近一次追问原文",
--                        "params":{"目标语言":"日语"}}
--   agentId 用来判断「换智能体就作废」：路由转向别的 agent 时状态自然失效，无需额外清理动作。
-- 注：MySQL 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报 `Duplicate column name`（1060），可忽略。
-- ---------------------------------------------------------------------------
ALTER TABLE conversation ADD COLUMN clarify_state TEXT DEFAULT NULL COMMENT '参数补全（澄清追问）的显式状态 JSON：{agentId,asked,request,question,params}；NULL=无进行中的追问。落库以摆脱「每轮扫历史重放推导」——历史被摘要压缩或标记不参与记忆后，重放会算错已问次数与已确认参数';
