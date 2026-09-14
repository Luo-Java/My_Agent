# My_Agent 多智能体对话平台

基于 **Spring Boot 4 + Spring AI 2.0** 的多智能体（Multi-Agent）对话平台：支持自定义智能体、智能路由、参数追问补全、动态规划编排、双层长期记忆、知识库 RAG、多模态图片理解与工具调用，前端为内置静态页面，开箱即用。

## 功能特性

### 对话与智能体

- **自定义智能体**：页面创建任意角色（翻译、天气、教育数据分析、代码助手…），配置人设提示词、模型、温度与可用工具；提示词自动汇总到 `agent_code.md`。
- **智能路由**：未绑定智能体的会话由 LLM 三态路由（命中智能体 / 普通对话 / 正在回答追问），并携带最近上下文识别「北京呢？」这类承接上一轮的短追问。
- **参数追问补全**：智能体用 `paramSchema`（JSON 数组）声明参数；缺失必填项时自动追问（上限 3 轮），参数齐全才正式回答。跟进任务会继承上一轮已明确的参数（如「今天」→ 日期=今天）。追问状态**无显式存储**，每轮从历史重放推导，天然跨重启一致。
- **动态规划（Planner）**：会话级 🧭 开关开启后，由 LLM 运行时把用户目标拆成多智能体步骤并顺序执行；执行过程实时展示、不写入记忆。规划模式与绑定智能体互斥。
- **双层记忆**：短期窗口（`chat_message` 原文，受 token 预算与条数下限约束）+ 长期滚动摘要（`conversation.summary` / `core_facts`）；超窗历史异步压缩合并，**先推回复、后处理记忆**。
- **流式输出**：SSE 推送 `token`（正文，进记忆）、`progress`（执行过程，不进记忆）、`citations`（引用来源）、`ping`（心跳）、`error` 等事件，前端逐字渲染。
- **链路追踪**：每轮对话的路由来源、规划步骤、改写后检索问句、RAG 命中、工具调用（参数/结果/token/耗时）异步落库 `agent_trace`；页面 🔍 追踪弹窗可查看本会话最近 50 轮。

### 知识库与多模态

- **知识库 RAG（会话级纯开关 + 自动多库）**：会话开启「📚 RAG」后，每轮自动检索「通用知识库（`agent_id` 为空）＋路由/绑定智能体的专属库」，多库一次合并检索并注入编号上下文；关闭即完全不检索。三段式检索：**粗排召回 → 精排（DashScope text-rerank）→ 编号注入**，精排不可用时降级「向量分截断」。检索前会用最近若干轮历史做**多轮查询改写**（指代消解），首轮/未开 RAG 自动跳过。
- **以「文件」为管理单元**：上传 → 解析 → 分片 → 向量化入库并登记（`kb_file`）；支持 4 种分片策略（fixed / paragraph / recursive / markdown）与重叠字数、**重新分片**（不重传换策略）、**同名重传=替换**。
- **向量存储双写**：MySQL 留档（源，向量以 JSON 文本存 `kb_chunk`）+ Chroma 加速副本。检索优先 Chroma（余弦 TopK），不可用/无命中自动降级 MySQL 余弦（有界扫描，防 OOM）；`POST /api/kb/chroma/sync` 幂等回填副本。Chroma 写入统一延后到**事务提交之后**，避免 MySQL 回滚后副本失配。
- **多模态图片理解**：输入框 🖼 支持多选图片（≤5 张 / 单张 ≤10MB），由视觉模型（默认 `qwen3.5-ocr`，可配）识别成中文 caption 拼入本轮上下文；走 Spring AI 原生多模态（裸 `ChatModel` + `UserMessage.media`，per-request 覆盖模型、多图并发识别）。**原始二进制不进会话存储**——caption 仅当轮可见。
- **文档解析**：附件与知识库支持 txt / md / markdown / csv / json / xml / yml / properties / log / sql 文本，以及 pdf（PDFBox）、docx / xlsx（POI）。

### 工具调用

- **全局能力池**：天气查询、日期解析、SQL 安全查询、表结构查看、样例数据、SQL 预检、ECharts 图表、文本直方图。
- **按智能体装配**：`agent.tools_json` 控制白名单 —— `NULL`/空 = 挂全量、`[]` = 不挂、`["名"]` = 白名单（按 `@Tool` 名匹配，未指定 name 时即方法名）。未知名忽略、非法 JSON 回退全量。
- **安全护栏**：SQL 工具仅允许只读 `SELECT`/`WITH`，白名单表名（student/class/teacher/subject/course/score）、拒绝多语句与可执行注释、结果行数上限。

## 技术栈

| 组件 | 版本 / 说明 |
|---|---|
| JDK | 17 |
| Spring Boot | 4.0.7 |
| Spring AI | 2.0.0（`spring-ai-starter-model-openai`，兼容 OpenAI 协议） |
| MyBatis-Plus | 3.5.16（`mybatis-plus-spring-boot4-starter`） |
| MySQL | 8.x（`mysql-connector-j`） |
| Chroma | 0.5.23（向量加速副本，`spring-ai-chroma-store` 2.0.0；与 MySQL 双写，缺失自动降级） |
| Hutool | 5.8.38（JSON 解析统一用 `JSONUtil`，规避 Jackson `ObjectMapper`） |
| PDFBox / POI | 2.0.30 / 4.1.2（pdf、docx、xlsx 文本抽取） |
| 前端 | 内置静态页面（`static/index.html` + Vue 3 + 原生 CSS） |

> 主对话模型默认 `kimi-k2.7-code`，向量化默认 `qwen3.7-text-embedding`，视觉默认 `qwen3.5-ocr`，精排固定 `gte-rerank-v2`；均可在 `application.yaml` 调整。

## 快速开始

### 1. 环境要求

- JDK 17+
- Maven 3.9+
- MySQL 8.x（库名 `agent`，需**手动创建**，连接串未带 `createDatabaseIfNotExist`）
- Chroma 服务（**可选**）：`chroma run` 后监听 `8000`；未启动时知识库检索自动降级 MySQL，功能不受阻，且「先起应用、后起 Chroma」会在冷却后自动重连，无需重启。

### 2. 初始化数据库

DDL **默认不自动执行**，首次启动前手动执行（脚本幂等，可重复执行）：

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS agent DEFAULT CHARSET utf8mb4;"
mysql -uroot -p agent < src/main/resources/sql/schema.sql   # 建表
mysql -uroot -p agent < src/main/resources/sql/alter.sql    # 存量库补列（新库可跳过）
```

> 如需应用启动时自动建表，取消 `application.yaml` 中 `spring.sql.init` 整段的注释即可。

### 3. 配置

口令与 API Key **不进仓库**，由根目录 `application-local.yaml`（已 gitignore）或环境变量提供：

```yaml
# application-local.yaml（本机私有，勿提交）
spring:
  datasource:
    password: 你的数据库口令
  ai:
    openai:
      api-key: 你的模型服务 API Key
```

主配置 `src/main/resources/application.yaml` 结构（仅列关键项，完整见文件内注释）：

```yaml
spring:
  config:
    import: classpath:prompts.yaml, optional:file:./application-local.yaml
  servlet:
    multipart: {max-file-size: 20MB, max-request-size: 80MB}
  datasource:
    url: jdbc:mysql://localhost:3306/agent?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
    username: ${DB_USERNAME:root}          # password 由 application-local.yaml / DB_PASSWORD 提供
  ai:
    openai:
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1   # 可换 DeepSeek / Ollama 等兼容服务
      timeout: 60s
      max-retries: 1
      chat:      {model: kimi-k2.7-code}
      embedding: {model: qwen3.7-text-embedding}
agent:
  memory: {recent-tokens: 4000, min-keep-messages: 2, max-message-chars: 4000}
  rag:    {rerank-enabled: true, recall-k: 20, top-k: 3, rerank-min-score: 0.20,
           min-score: 0.25, recall-min-score: 0.10, fallback-max-chunks: 2000,
           query-rewrite-enabled: true, query-rewrite-history-size: 6}
chroma:
  base-url: http://127.0.0.1:8000
  collection-name: kb_chunks
  tenant: default_tenant          # 必须用 Chroma 原生命名空间
  database: default_database
  retry-interval-seconds: 60
  upsert-batch-size: 10
app:
  api-key: ${APP_API_KEY:}        # 留空=不校验（本地默认）
  sse: {timeout-seconds: 300, heartbeat-seconds: 15}
  attachment: {dir: ./data/attachments}
server:
  port: 8080
```

> **配置前缀必须是顶层 `agent.*`**（对应 `@ConfigurationProperties("agent.*")`）。历史上曾因缩进错误被挂到 `spring:` 下导致整段静默失效，改动务必核对。

### 3.1 部署安全（可选）

- **接口访问控制**：默认不校验（本地开发）。若服务会暴露到局域网/公网，设置 `APP_API_KEY=你的密钥` 后重启，所有 `/api/**` 请求必须携带 `X-Api-Key: 你的密钥`（或 `Authorization: Bearer ...`），否则 401 —— 防止他人直接调用 SQL 查询工具、白嫖 LLM Key、读写知识库。
- **前端如何带密钥**：页面静态资源不在拦截范围，但页面发出的 `/api` 请求需要密钥。打开页面点顶栏 **🔑 访问密钥**，填入与 `APP_API_KEY` 相同值即可（仅存浏览器 `localStorage`）。前端所有 `/api` 调用统一经 `apiFetch` 自动附加该头；附件图片走 `/files/**`，无需密钥。
- **附件安全**：`/files/**` 是免鉴权的同源静态映射，落盘文件名后缀经**白名单化**（图片/文档类保留，其余一律 `.bin`=octet-stream 只下载不渲染），杜绝上传 `.html`/`.svg` 后同源执行脚本。
- **密钥泄露处理**：若密钥曾以明文提交进仓库，改配置只是止血 —— **必须到服务商控制台轮换/吊销旧 Key**（历史提交里的旧值依然可用）。

### 4. 构建与运行

```bash
mvn spring-boot:run
# 或
mvn clean package && java -jar target/my_agent-0.0.1-SNAPSHOT.jar
```

浏览器访问 <http://localhost:8080>。改动前端静态资源后 `mvn compile` 同步 `target/classes`，再 Ctrl+F5 强刷。

## 架构与核心机制

### 包结构

```
org.luo
├── controller/      # ChatController(SSE) / ConversationController / AgentController
│                    # / KnowledgeBaseController / AttachmentController / TraceController
├── service/         # ChatService(编排门面) / ConversationService / AgentService
│                    # / KbService / KbSearchService / ChunkingService
├── agent/           # AgentRouter(智能路由) / ParamFillingService(参数补全) / PlannerService(规划)
│                    # / MemoryMergeService(记忆合并) / PromptService / QueryRewriteService(查询改写)
│   └── handler/     # RoundHandler + AgentRoundHandler / PlannerRoundHandler / RoundResult
├── chat/            # ChatComposer(请求装配：人设/记忆/材料/工具/RAG)
├── advisor/         # ToolUsageLoggingAdvisor / RoundTraceAdvisor
├── tool/            # ToolRegistry(能力池) / WeatherTools / DateResolver
│                    # / SqlQueryTool / SqlSafety / SqlSchemaTool / ChartTool
├── memory/          # DbChatMemory(Spring AI ChatMemory 的 DB 实现)
├── trace/           # RoundTrace(一轮收集器) / TraceService(异步落库)
├── infrastructure/  # attachment(解析/落盘) / chroma(客户端/副本同步) / document
│                    # / rerank(DashScope 精排) / vision(多模态)
├── config/          # ApiSecurity / CorsConfig / ExecutorConfig(线程池) / MemoryProperties
│                    # / RagProperties / VisionProperties / PromptProperties / GlobalExceptionHandler
├── entity/ mapper/ dto/ enums/ exception/ constant/
resources/
├── application.yaml     # 数据源 / 模型 / 平台自身配置
├── prompts.yaml         # 全部提示词模板（agent.prompt.*）
├── sql/schema.sql       # 建表（幂等）
├── sql/alter.sql        # 存量库补列
└── static/              # 前端（index.html / js/app.js / css/style.css）
```

### 一轮对话的编排流程

```
POST /api/chat/send | /stream
        │
        ▼
ChatService（编排门面，同步 chat() / 流式 stream()）
   │
   ├─ 规划模式会话（conversation.planner=1）
   │    └─ PlannerRoundHandler：LLM 规划多智能体步骤 → 顺序执行 → 落库（只回传最后一步引用）
   │
   └─ AgentRoundHandler（普通/智能体会话）
        ⓪ 前置链并行预取：RAG 查询改写 ∥ 路由 ∥ 参数抽取（三者并发，正式回答前 join）
        ① determineAgent   显式绑定 / CLARIFY 绑定 / 智能路由
        ② 话题切换预检      仅 CLARIFY 绑定，防止含城市词的新话题被误判为补全
        ③ decideClarify     参数抽取 → 缺失必填则追问；齐全则进入正式回答
        ④ buildRequest     人设 + 长期记忆 + 已确认参数 + 本轮附件材料 + 工具 + RAG 上下文 → 主模型 call()
        └─ 收尾：推回复 → 推 citations → 落库附件/引用 → 异步落库追踪 → 异步合并记忆
```

### 关键机制

| 机制 | 说明 |
|---|---|
| 绑定来源 `agent_bind_source` | `EXPLICIT`=用户显式选择（粘住不解绑）；`CLARIFY`=追问临时绑定（话题切换自动解绑）；空=自由路由 |
| 智能路由 | 裸 ChatModel 三态 JSON 决策（`{"route":true,"agentCode":"..."}` / `{"route":false}` / `{"route":false,"continue":true}`），Hutool 解析 |
| 参数追问 | `paramSchema` 声明参数；LLM 从有界历史抽取已确认取值；缺失必填生成 `🔎 还需补充信息`，上限 3 次；**状态每轮重放推导，不落库** |
| 记忆体系 | 窗口 = 原文预算 + **条数下限**（防单条超预算导致窗口塌缩）+ 单条截断；`DbChatMemory` 与 `MemoryMergeService` 共用同一 `MemoryProperties` 口径 |
| 对话附件三通道 | ① 纯提问 `message` → 走记忆/路由，写 `chat_message.content`；② 解析文本 `material` → 注入**当轮** system，仅当轮可见；③ 展示元数据 `attachments_json`（不含正文）→ 仅供历史回看 |
| RAG 引用溯源 | `KbCitation` 与资料块**同趟产出**，出口三处同一份 JSON：落库 `chat_message.citations_json`、SSE `citations` 事件、`agent_trace.citations_json` |
| 流式策略 | Spring AI 2.0.0 的 `stream()` 在工具调用场景会崩（见「已知缺陷」），统一 `call()` 拿完整答案后按自适应分片模拟流式（总时长封顶 ≈2s） |
| 并发预取 | 前置链（路由/参数抽取/查询改写）由专用线程池并行，显著缩短首字节时延；预取只加速、不承担正确性，异常/超时一律回退用户原话 |
| 纯旁路追踪 | `agent_trace` 删掉后对话照常运行；写入在回复产出后异步、失败只记日志 |
| SSE 兜底 | 有限超时（默认 300s）+ 独立心跳调度器（默认 15s，专用线程池，不与打字机抢 Reactor 线程） |

## API 一览

### 对话

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat/send` | 同步对话 → `{"content":"完整回复"}` |
| POST | `/api/chat/stream` | SSE 流式对话；事件 data 为 `{"token":"..."}` / `{"progress":"..."}` / `{"citations":[...]}` 等 |
| POST | `/api/chat/attachment/process` | 批量解析附件：multipart `files` → `{"results":[{"type","filename","content","storedName","size","url"}]}`。**图片在此内部委托 `VisionService` 并发识别**（无独立视觉端点）；`type` ∈ `image`/`text`/`file` |

> 请求体 `ChatRequest`：`{conversationId, message, planner, attachments:[{type,content,filename,storedName,size}]}`；`planner` 非空时覆盖并写回会话形态。

### 会话

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat/conversation` | 开新会话（可传 `agentId` 绑定智能体，或 `planner:true` 建规划会话） |
| PUT | `/api/chat/conversation/{id}` | 重命名会话 |
| PUT | `/api/chat/conversation/{id}/planner` | 更新规划开关 `{enabled}`（绑定智能体的会话不可开启） |
| PUT | `/api/chat/conversation/{id}/rag` | 更新 RAG 开关 `{enabled}` |
| DELETE | `/api/chat/conversation/{id}` | 删除会话及全部消息 |
| GET | `/api/chat/conversations` | 会话列表（按最近更新倒序） |
| GET | `/api/chat/history?conversationId=` | 读取会话历史消息 |

### 智能体

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/agent` | 智能体列表 |
| GET | `/api/agent/{id}` | 单个智能体详情 |
| GET | `/api/agent/by-code/{code}` | 按编码查询（多智能体协作路由入口） |
| GET | `/api/agent/tools` | 全局工具池（按 Provider 分组，供前端勾选白名单） |
| POST | `/api/agent` | 创建智能体 |
| PUT | `/api/agent/{id}` | 更新智能体 |
| DELETE | `/api/agent/{id}` | 删除智能体（解除会话绑定 + 级联清理知识库与向量） |
| POST | `/api/agent/generate-prompt` | 用 AI 根据名称/描述生成系统提示词（不落库） |

### 知识库

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/kb` | 知识库列表 |
| GET | `/api/kb/global` | 通用知识库（`agent_id` 为空） |
| GET | `/api/kb/chunk-strategies` | 可选分片策略清单 |
| POST | `/api/kb` | 创建知识库（可绑定智能体） |
| PUT | `/api/kb/{id}` | 更新知识库 |
| DELETE | `/api/kb/{id}` | 删除知识库（级联删块、文件与向量副本） |
| GET | `/api/kb/{id}/chunks` | 分页查看知识块 |
| POST | `/api/kb/{id}/chunks` | 手动追加知识块 |
| DELETE | `/api/kb/{id}/chunks/{chunkId}` | 删除单个知识块（校验块归属） |
| POST | `/api/kb/{id}/upload` | 上传文件（multipart `files` + `chunkStrategy` + `overlap`），同名重传=替换 |
| GET | `/api/kb/{id}/files` | 文件列表 |
| POST | `/api/kb/{id}/files/{fileId}/rechunk` | 重新分片 `{"strategy","overlap"}`（缺省沿用文件当前值） |
| DELETE | `/api/kb/{id}/files/{fileId}` | 删除文件及其知识块 |
| POST | `/api/kb/chroma/sync` | 幂等回填 Chroma 副本 |
| GET | `/api/kb/chroma/status` | Chroma 连接状态 / 空间 / 向量条数 |

### 链路追踪

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/trace?conversationId=` | 某会话最近若干轮追踪 |
| GET | `/api/trace/{traceId}` | 单轮追踪详情 |

## 数据库表

| 表 | 关键列 | 说明 |
|---|---|---|
| `conversation` | id, title, agent_id, planner, agent_bind_source, rag_enabled, summary, summarized_count, core_facts | 会话：绑定智能体、规划/RAG 开关、滚动摘要与核心事实 |
| `chat_message` | id, conversation_id, role, content, attachments_json, citations_json, created_at | 消息明细；附件元数据与引用来源**独立列**，不进记忆、不占 token |
| `agent` | id, name, agent_code, icon, description, system_prompt, param_schema, tools_json, model, temperature, avatar_color | 智能体：人设、参数清单、工具白名单、模型/温度覆盖 |
| `kb` | id, name, agent_id, description, doc_count, chunk_strategy, chunk_overlap | 知识库；`agent_id` 为空即通用全局库 |
| `kb_chunk` | id, kb_id, content, source, embedding, created_at | 知识块；`embedding` 为向量 JSON 文本（MySQL 源） |
| `kb_file` | id, kb_id, file_name, file_type, chunk_strategy, chunk_overlap, size_bytes, chunk_count, raw_text | 以文件为管理单元；`raw_text` 支持不重传重新分片 |
| `agent_trace` | trace_id, conversation_id, mode, route_source, agent_code, user_message, retrieval_query, plan_json, tool_calls, kb_hit_count, citations_json, prompt_tokens, completion_tokens, total_tokens, elapsed_ms, status | 纯旁路可观测表，删掉不影响对话 |

## 前端界面

- **顶栏**：会话列表（含 🧭 规划标记）、当前会话徽标（🧭 规划模式 / 📚 RAG）、🔑 访问密钥、🔍 追踪。
- **输入区**：🖼 图片多选（≤5 张）、📎 文档上传、📚 RAG 开关、🧭 规划开关（绑定智能体的会话置灰）。
- **知识库页**：库/文件管理、上传与重新分片、分页查看知识块、Chroma 状态条与「同步本库」。
- **追踪弹窗**：路由来源、规划步骤、检索问句、RAG 命中、工具调用、token 与耗时。

## 开发备注

- **离线编译**：本机 `mvnw` 已损坏，使用真实 Maven（`D:\software\Java\maven\apache-maven-3.9.16`）离线构建：
  ```bash
  cd /d/MyProject/My_Agent && MAVEN_HOME="D:/software/Java/maven/apache-maven-3.9.16" && PROJ="D:/MyProject/My_Agent" && \
  java -classpath "${MAVEN_HOME}/boot/plexus-classworlds-2.11.0.jar" -Dmaven.home="${MAVEN_HOME}" \
    -Dmaven.multiModuleProjectDirectory="${PROJ}" -Dclassworlds.conf="${MAVEN_HOME}/bin/m2.conf" \
    -Dmaven.legacyLocalRepo=true \
    org.codehaus.plexus.classworlds.launcher.Launcher -o package -DskipTests
  ```
  （`-Dmaven.legacyLocalRepo=true` 用于绕过离线仓库对 `_remote.repositories` 来源的严格校验。）
- **代码约定**：LLM 返回 JSON 的解析统一用 Hutool `cn.hutool.json.JSONUtil`，不引入 Jackson `ObjectMapper` 解析路径。
- **配置约定**：新增自定义配置段必须写在**顶层**，并登记进 `MyAgentApplication` 的 `@EnableConfigurationProperties`；注释必须与事实一致（本项目已多次出现「注释与代码相反」的漂移）。
- **DDL 约定**：变更表结构须同步更新 `sql/schema.sql`（建库权威定义）与 `sql/alter.sql`（存量库补列），两处列状态保持一致。
- **生效方式**：改动 Java / 配置 / SQL 需重启服务；改动前端静态资源后 `mvn compile` 同步，浏览器 Ctrl+F5。
- **已知缺陷**：Spring AI 2.0.0 的 `stream()` 合并工具调用分片时抛 `NoSuchElementException`（`OpenAiChatModel$ChunkMerger` 对工具调用 `Optional` 直接 `.get()`）。带工具的对话必须用 `call()` 走完工具循环再切片模拟流式。
- `agent_code.md` 由系统自动生成（智能体提示词汇总，原子写入），修改请通过页面操作，勿直接编辑。
