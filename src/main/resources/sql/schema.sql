-- 会话表：每个对话会话对应一条记录
CREATE TABLE IF NOT EXISTS conversation (
    id         VARCHAR(64)  NOT NULL                   COMMENT '会话ID（业务层生成的UUID）',
    user_id    BIGINT       DEFAULT NULL               COMMENT '所属用户ID，关联 sys_user.id；会话按用户隔离，仅本人可见（NULL=历史遗留，不归属任何用户）',
    title      VARCHAR(255) DEFAULT '新对话'           COMMENT '会话标题，默认“新对话”，由首条用户消息派生（最多20字）',
    agent_id   BIGINT       DEFAULT NULL               COMMENT '绑定的智能体ID，关联 agent.id（自增主键），为空表示默认助手',
    planner        TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否规划模式会话：1=动态规划器（运行时由 LLM 规划多智能体步骤），0=普通/智能体会话',
    agent_bind_source VARCHAR(16) DEFAULT NULL          COMMENT '智能体绑定来源：EXPLICIT=用户显式选择（保持粘住），CLARIFY=追问流程临时绑定（允许话题切换时解绑）；空=未绑定',
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

-- 智能体链路追踪表：一轮对话的全链路留痕，答「这轮为什么路由到 X / 规划器哪步慢 / RAG 有没有命中」。
-- 纯旁路：异步落库、失败只记日志，不进对话主链路，也不被任何检索或记忆读取
CREATE TABLE IF NOT EXISTS agent_trace (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '追踪记录ID',
    trace_id          VARCHAR(64)  NOT NULL                COMMENT '本轮唯一追踪ID（UUID，同一轮内所有阶段共用一个）',
    conversation_id   VARCHAR(64)  DEFAULT NULL            COMMENT '所属会话ID，关联 conversation.id',
    mode              VARCHAR(16)  DEFAULT NULL            COMMENT '本轮形态：agent=普通/智能体对话，planner=规划模式，review=并行评审（多候选作答 + 裁决综合）',
    route_source      VARCHAR(24)  DEFAULT NULL            COMMENT '处理方来源：BOUND=会话显式绑定，ROUTE=智能路由命中，NONE=通用助手，PLAN=规划编排，REVIEW=并行评审',
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

-- 提示词回归评测结果表：一次跑批每个用例一行，按 batch_id 分组；只保留最近 20 批（EvalService 自动清理）。
-- 与 agent_trace 的分工：trace 记「一轮真实对话」，本表记「固定用例集在某一版提示词下的判定结果」，
-- 让「改完 prompts.yaml 变好还是变差」有据可依（改前跑一批、改后跑一批，比 broken 清单）
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

-- 规划任务表：一轮规划 = 一条 task + N 条 task_step，状态机 RUNNING → DONE/FAILED/CANCELLED；
-- 单会话单 RUNNING（开新规划任务前自动结旧）
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

-- 规划任务步骤表：状态机 PENDING → RUNNING → DONE/SKIPPED/FAILED；FAILED 续跑时重试一次，
-- 累计 >= 2 判确定性失败。
-- 审批关卡：approval_required=1 的步骤执行前必须先批准，否则整条流水线在此暂停（剩余 PENDING、
-- task 仍 RUNNING）。审批是「执行前的闸门」而非状态，故不塞进 status 状态机
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

-- 规划模板表：把一次跑顺的多智能体规划**步骤骨架**沉淀成可复用资产，同类目标下次直接套用，
-- 省掉一次规划模型往返。步骤存快照 JSON 而非引用 task_step：① task_step 带 status/output/retry_count
-- 等运行态列，模板不该背着它们；② 局部重规划（TaskService.replanTail）会删改甚至重排 task_step 行，
-- 引用式模板会被连带破坏。按 user_id 隔离，与 conversation 同口径（越权一律 404）
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

-- 用户反馈与「库内回归用例」：eval-cases.yaml 是**只读种子**（打包进 jar，运行时写不了），
-- 库内用例表承接运行时新增（主要来自用户对某条回复的点踩），两者在 EvalService.loadCases 里合并跑批
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

-- 结构化长期事实条目：把「一条事实」变成可寻址的行，让用户能像编辑联系人一样维护它
-- （此前 core_facts 是一个平铺文本字段，聊久了话题一杂就成不断追加的长段落，既不能按主题取用，
--   也不能删掉记错的那一条 —— 只能整段覆盖）。
-- 与 core_facts 的关系：core_facts 保留不动，退化为「旧版文本事实归档」—— MemoryMergeService 首次
-- （本表为空）会把它喂给 LLM 拆成条目，此后注入侧一律以条目为准；不清空是因为拆分是模型行为、
-- 可能有损，保留原文让信息零丢失。
-- fact_hash 的必要性：条目由 LLM 每次重新生成，同一件事实两次合并的措辞会微微变化，不去重就会
-- 一涨一大片；唯一键 (conversation_id, fact_hash) 把「同一会话内同一条事实」收敛为一行。
-- 不用 fact 本身做唯一键：VARCHAR(500) utf8mb4 超索引长度上限，必须用定长摘要
CREATE TABLE IF NOT EXISTS conversation_fact (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    conversation_id VARCHAR(64)  NOT NULL                COMMENT '所属会话ID，关联 conversation.id',
    topic           VARCHAR(32)  NOT NULL                COMMENT '主题标签（身份 / 偏好 / 待办 / 背景 / 其它），供面板分组与人工扫读',
    fact            VARCHAR(500) NOT NULL                COMMENT '事实内容（单条，不含主题前缀）',
    fact_hash       CHAR(32)     NOT NULL                COMMENT 'topic+fact 的 MD5，用于同会话内去重（见建表注释）',
    source          VARCHAR(16)  NOT NULL DEFAULT 'MERGE' COMMENT '来源：MERGE=自动合并产出（每次合并按 diff 重写）/ USER=用户手动添加（合并绝不覆盖或删除）',
    created_at      DATETIME                             COMMENT '首次写入时间',
    updated_at      DATETIME                             COMMENT '最后被确认/改写时间（合并里仍然有效即刷新，故它等于「这条最近还在被维护」）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_fact (conversation_id, fact_hash),
    INDEX idx_fact_conv (conversation_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '会话长期事实条目表：把 core_facts 那一段文本拆成逐条可管理的行';
