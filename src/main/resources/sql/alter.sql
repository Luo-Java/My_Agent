-- 兼容已存在旧表：补充 agent_id 列（表已含该列时因 continue-on-error 被忽略，不报错）
ALTER TABLE conversation ADD COLUMN agent_id BIGINT DEFAULT NULL COMMENT '绑定的智能体ID，关联 agent.id（自增主键），为空表示默认助手';

-- 兼容已存在旧表：补充绑定来源列（列已存在时因 continue-on-error 被忽略，不报错）
ALTER TABLE conversation ADD COLUMN agent_bind_source VARCHAR(16) DEFAULT NULL COMMENT '智能体绑定来源：EXPLICIT=用户显式选择，CLARIFY=追问流程临时绑定；空=未绑定';

-- 兼容已存在旧表：补充滚动摘要/核心记忆列（列已存在时因 continue-on-error 被忽略，不报错）
ALTER TABLE conversation ADD COLUMN summary TEXT DEFAULT NULL COMMENT '较早对话的滚动摘要（长期记忆）';
ALTER TABLE conversation ADD COLUMN summarized_count INT DEFAULT 0 COMMENT '已被摘要覆盖的最旧消息条数';
ALTER TABLE conversation ADD COLUMN core_facts TEXT DEFAULT NULL COMMENT '用户核心信息（长期关键事实：姓名/身份/偏好/待办等）';

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
ALTER TABLE llm_usage MODIFY COLUMN purpose VARCHAR(24) NOT NULL COMMENT '调用用途：ROUTE=智能路由 / CLARIFY=参数抽取 / REWRITE=查询改写 / PLAN=任务规划 / VISION=视觉识别 / MEMORY_MERGE=记忆合并 / SUBAGENT=智能体转交';

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
