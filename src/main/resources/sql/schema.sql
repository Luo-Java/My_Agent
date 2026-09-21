-- 会话表：每个对话会话对应一条记录
CREATE TABLE IF NOT EXISTS conversation (
    id         VARCHAR(64)  NOT NULL                   COMMENT '会话ID（业务层生成的UUID）',
    title      VARCHAR(255) DEFAULT '新对话'           COMMENT '会话标题，默认“新对话”，由首条用户消息派生（最多20字）',
    agent_id   BIGINT       DEFAULT NULL               COMMENT '绑定的智能体ID，关联 agent.id（自增主键），为空表示默认助手',
    planner        TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否规划模式会话：1=动态规划器（运行时由 LLM 规划多智能体步骤），0=普通/智能体会话',
    agent_bind_source VARCHAR(16) DEFAULT NULL          COMMENT '智能体绑定来源：EXPLICIT=用户显式选择（保持粘住），CLARIFY=追问流程临时绑定（允许话题切换时解绑）；空=未绑定',
    rag_enabled  TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '会话级 RAG 开关：1=每轮对话自动检索资料库（通用知识库 + 路由到智能体时其专属库）并把命中内容注入上下文；0=不使用 RAG（仅靠模型自身知识）',
    created_at DATETIME                                 COMMENT '会话创建时间',
    updated_at DATETIME                                 COMMENT '最后更新时间，用于会话列表倒序排序',
    summary           TEXT        DEFAULT NULL               COMMENT '较早对话的滚动摘要（长期记忆），超出最近窗口的历史由LLM压缩写入',
    summarized_count  INT         DEFAULT 0                  COMMENT '已被摘要覆盖的最旧消息条数（按时间正序索引）',
    core_facts        TEXT        DEFAULT NULL               COMMENT '用户核心信息（长期关键事实：姓名/身份/偏好/待办等），随摘要一起由LLM提取更新',
    PRIMARY KEY (id),
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
    created_at      DATETIME                             COMMENT '消息写入时间；同一轮消息的先后顺序由自增主键 id 兜底（查询统一 ORDER BY created_at, id）',
    PRIMARY KEY (id),
    -- 复合索引：供「按会话倒序取最近 N 条消息」的记忆窗口读取（DbChatMemory），避免长会话全表扫描
    INDEX idx_conv_created (conversation_id, created_at)
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

-- 知识库表：每个智能体可维护一个专属知识库（agent_id 唯一）；agent_id 为 NULL 的是全局知识库（「通用知识库」，可被任意会话的资料库选择器选用）。
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

-- 知识块表：知识库的最小检索单元（一段文本 + 它的向量，向量由 Embedding API 生成，存 JSON float 数组）
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

-- 知识库文件表：每个知识库维护的文件列表（文件是知识的上传与管理单元）
-- file_name 与 kb_chunk.source 保持一致（同一库内文件名唯一），删除文件时按 kb_id+file_name 级联删除其知识块
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

-- 智能体链路追踪表：一轮对话的全链路留痕（可观测性 / 调优依据）。
-- 记录「本轮是谁处理的、有没有走 RAG、命中了什么、调了哪些工具、花了多少 token、耗时多少」，
-- 用于回答「这轮为什么路由到 X」「规划器哪一步慢」「RAG 有没有命中」这类问题。
-- 纯旁路数据：异步落库、失败只记日志，绝不参与对话主链路，也不被任何检索/记忆读取。
CREATE TABLE IF NOT EXISTS agent_trace (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '追踪记录ID',
    trace_id          VARCHAR(64)  NOT NULL                COMMENT '本轮唯一追踪ID（UUID，同一轮内所有阶段共用一个）',
    conversation_id   VARCHAR(64)  DEFAULT NULL            COMMENT '所属会话ID，关联 conversation.id',
    mode              VARCHAR(16)  DEFAULT NULL            COMMENT '本轮形态：agent=普通/智能体对话，planner=规划模式',
    route_source      VARCHAR(24)  DEFAULT NULL            COMMENT '处理方来源：BOUND=会话显式绑定，ROUTE=智能路由命中，NONE=通用助手，PLAN=规划编排',
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
    created_at        DATETIME                             COMMENT '记录时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_trace_id (trace_id),
    INDEX idx_trace_conv (conversation_id, created_at),
    INDEX idx_trace_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '智能体链路追踪表：一轮对话的路由/RAG/工具/token/耗时留痕';

-- 裸 LLM 调用成本流水表：与 agent_trace 互补，记全量成本。
-- agent_trace 只记「正式回答 + 工具循环」的 token；路由判定/参数抽取/查询改写/视觉识别/记忆合并这些
-- 裸 ChatModel.call()（不经 Advisor）的 token 在这里按用途（purpose）各记一条，成本看板据此做全量聚合。
CREATE TABLE IF NOT EXISTS llm_usage (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    trace_id          VARCHAR(64)  DEFAULT NULL            COMMENT '本轮追踪ID（可空：视觉识别是独立请求、早于 trace 建立）',
    conversation_id   VARCHAR(64)  DEFAULT NULL            COMMENT '所属会话ID（可空：如生成智能体人设这类无会话的调用）',
    purpose           VARCHAR(24)  NOT NULL                COMMENT '调用用途：ROUTE=智能路由 / CLARIFY=参数抽取 / REWRITE=查询改写 / VISION=视觉识别 / MEMORY_MERGE=记忆合并',
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

-- 规划任务表：一轮规划 = 一条 task + N 条 task_step，落库支撑断点续跑。
-- 状态机：RUNNING → DONE/FAILED/CANCELLED；单会话单 RUNNING（开新规划任务前自动结旧）。
CREATE TABLE IF NOT EXISTS task (
    id              VARCHAR(64)  NOT NULL                COMMENT '任务ID（业务层生成UUID）',
    conversation_id VARCHAR(64)  NOT NULL                COMMENT '所属会话ID，关联 conversation.id',
    user_goal       TEXT         DEFAULT NULL            COMMENT '用户原始目标（触发规划的那句原话）',
    status          VARCHAR(16)  NOT NULL DEFAULT 'RUNNING' COMMENT '任务状态：RUNNING/DONE/FAILED/CANCELLED',
    total_steps     INT          NOT NULL DEFAULT 0      COMMENT '步骤总数（快照，避免反复数）',
    done_steps      INT          NOT NULL DEFAULT 0      COMMENT '已完成步骤数（冗余，供列表快速展示进度）',
    result          LONGTEXT     DEFAULT NULL            COMMENT '最终汇总结果（汇总步产出/最后一个成功步骤产出）',
    created_at      DATETIME                             COMMENT '创建时间',
    updated_at      DATETIME                             COMMENT '最后更新时间',
    PRIMARY KEY (id),
    INDEX idx_task_conv (conversation_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '规划任务表：一轮多智能体规划任务的落库与断点续跑';

-- 规划任务步骤表：任务的一步 = 一个智能体 + 指令 + 依赖前驱。
-- 状态机：PENDING → RUNNING → DONE/SKIPPED/FAILED；FAILED 续跑时重试一次，累计失败 >= 2 判确定性失败（不再重试）。
CREATE TABLE IF NOT EXISTS task_step (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    task_id        VARCHAR(64)  NOT NULL                COMMENT '所属任务ID，关联 task.id',
    step_index     INT          NOT NULL                COMMENT '步骤下标（0基，对应规划 steps 数组位置，即重映射后的 specs 连续下标）',
    agent_code     VARCHAR(64)  NOT NULL                COMMENT '本步执行的智能体编码',
    instruction    TEXT         DEFAULT NULL            COMMENT '给该智能体的指令',
    depends_on     VARCHAR(500) DEFAULT '[]'            COMMENT '依赖的前序步骤下标（JSON数组，如 [0,1]；空表=无依赖）',
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '步骤状态：PENDING/RUNNING/DONE/SKIPPED/FAILED',
    retry_count    INT          NOT NULL DEFAULT 0      COMMENT '重试次数（0=未重试；续跑对 FAILED 重试一次，累计>=2 判确定性失败）',
    output         LONGTEXT     DEFAULT NULL            COMMENT '本步产出文本（成功时写入；失败/空为 NULL）',
    error          VARCHAR(1000) DEFAULT NULL           COMMENT '失败原因（FAILED 时）',
    citations_json TEXT         DEFAULT NULL            COMMENT '本步 RAG 引用（与 chat_message.citations_json 同构）',
    started_at     DATETIME                             COMMENT '开始执行时间',
    finished_at    DATETIME                             COMMENT '完成时间',
    PRIMARY KEY (id),
    INDEX idx_step_task (task_id, step_index)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '规划任务步骤表：任务每一步的产出与状态落库（断点续跑的最小粒度）';


-- 新增「规划任务」与「规划任务步骤」表（跨轮任务状态持久化）：schema.sql 已含建表语句，这里同样保留一份，
-- 供存量库直接执行（CREATE TABLE IF NOT EXISTS 幂等，表已存在时不报错）。
-- 一轮规划 = 一条 task + N 条 task_step；单会话单 RUNNING，开新规划任务前自动结旧；显式续跑只跑剩余步骤。
CREATE TABLE IF NOT EXISTS task (
    id              VARCHAR(64)  NOT NULL                COMMENT '任务ID（业务层生成UUID）',
    conversation_id VARCHAR(64)  NOT NULL                COMMENT '所属会话ID，关联 conversation.id',
    user_goal       TEXT         DEFAULT NULL            COMMENT '用户原始目标（触发规划的那句原话）',
    status          VARCHAR(16)  NOT NULL DEFAULT 'RUNNING' COMMENT '任务状态：RUNNING/DONE/FAILED/CANCELLED',
    total_steps     INT          NOT NULL DEFAULT 0      COMMENT '步骤总数（快照，避免反复数）',
    done_steps      INT          NOT NULL DEFAULT 0      COMMENT '已完成步骤数（冗余，供列表快速展示进度）',
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
    output         LONGTEXT     DEFAULT NULL            COMMENT '本步产出文本（成功时写入；失败/空为 NULL）',
    error          VARCHAR(1000) DEFAULT NULL           COMMENT '失败原因（FAILED 时）',
    citations_json TEXT         DEFAULT NULL            COMMENT '本步 RAG 引用（与 chat_message.citations_json 同构）',
    started_at     DATETIME                             COMMENT '开始执行时间',
    finished_at    DATETIME                             COMMENT '完成时间',
    PRIMARY KEY (id),
    INDEX idx_step_task (task_id, step_index)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '规划任务步骤表：任务每一步的产出与状态落库（断点续跑的最小粒度）';


-- 新增「裸 LLM 调用成本流水」表（全量成本口径）：schema.sql 已含建表语句，这里同样保留一份，
-- 供存量库直接执行（CREATE TABLE IF NOT EXISTS 幂等，表已存在时不报错）。
-- 与 agent_trace 互补：agent_trace 只记「正式回答+工具循环」token；本表记路由/参数抽取/查询改写/视觉/记忆合并等
-- 裸 ChatModel.call() 的 token，成本看板据此做全量聚合与按用途（purpose）拆解。
CREATE TABLE IF NOT EXISTS llm_usage (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    trace_id          VARCHAR(64)  DEFAULT NULL            COMMENT '本轮追踪ID（可空：视觉识别是独立请求、早于 trace 建立）',
    conversation_id   VARCHAR(64)  DEFAULT NULL            COMMENT '所属会话ID（可空：如生成智能体人设这类无会话的调用）',
    purpose           VARCHAR(24)  NOT NULL                COMMENT '调用用途：ROUTE=智能路由 / CLARIFY=参数抽取 / REWRITE=查询改写 / VISION=视觉识别 / MEMORY_MERGE=记忆合并',
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
