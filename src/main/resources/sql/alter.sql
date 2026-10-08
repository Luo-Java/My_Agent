-- ===========================================================================
-- alter.sql —— 存量库补丁脚本。建库权威定义是 schema.sql；存量库不跑它，只跑本脚本补表/补列/补索引。
--
-- 三条约定：
--   1. 幂等。新增表用 CREATE TABLE IF NOT EXISTS，重复执行安全；ADD COLUMN / ADD INDEX 没有
--      IF NOT EXISTS 语法，重复执行报 1060 (Duplicate column name) / 1061 (Duplicate key name)，忽略即可。
--   2. 顺序。新建表必须放文件最前 —— 后面有 ALTER TABLE task / task_step，表不存在会先在那里失败。
--   3. 双写。本脚本新建或改动的表结构必须与 schema.sql 逐字一致（同一张表不能有两种定义）。

-- ---------- 存量库补列 / 补索引（幂等规则见文件头） ----------
-- conversation：绑定智能体 / 绑定来源 / 滚动摘要
ALTER TABLE conversation ADD COLUMN agent_id BIGINT DEFAULT NULL COMMENT '绑定的智能体ID，关联 agent.id（自增主键），为空表示默认助手';
ALTER TABLE conversation ADD COLUMN agent_bind_source VARCHAR(16) DEFAULT NULL COMMENT '智能体绑定来源：EXPLICIT=用户显式选择，CLARIFY=追问流程临时绑定；空=未绑定';
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
