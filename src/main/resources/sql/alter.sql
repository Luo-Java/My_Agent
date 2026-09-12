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

-- 新增「智能体链路追踪」表（可观测性）：schema.sql 已含建表语句，这里同样保留一份，
-- 供存量库直接执行（CREATE TABLE IF NOT EXISTS 幂等，表已存在时不报错）。
-- 记录一轮对话的路由来源 / 命中知识库 / 工具调用 / token 用量 / 耗时，纯旁路数据（异步落库、失败不影响对话）。
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

-- 兼容「agent_trace 已存在」的库：补充「本轮实际检索问题」列（列已存在时 MySQL 报 1060，忽略即可）。
-- 值为多轮查询改写（指代消解）的产物；NULL 表示未改写（未开 RAG / 首轮无历史 / 关闭改写 / 模型判定原话已自包含）。
-- 单独留痕是为了让「RAG 没命中」可归因：究竟是改写跑偏了，还是知识库里确实没有。
ALTER TABLE agent_trace ADD COLUMN retrieval_query VARCHAR(1000) DEFAULT NULL COMMENT '本轮实际用于知识库检索的问题（多轮查询改写产物）；NULL=未改写（未开RAG/首轮/关闭改写/原话已自包含）' AFTER user_message;
