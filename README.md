# My_Agent 多智能体对话平台

基于 **Spring Boot 4 + Spring AI 2.0** 的多智能体（Multi-Agent）对话平台：支持自定义智能体、智能路由、参数追问补全、动态规划编排、双层长期记忆、知识库 RAG、多模态图片理解与工具调用，前端为内置静态页面，开箱即用。

## 功能特性

### 用户与权限

- **登录鉴权**：JWT（Hutool 签发，HMAC-SHA256）。token 只装身份与有效期，**权限每次请求回库取**，因此改角色 / 停用账号立即生效，不必等 token 过期。
- **用户与角色管理**（`/user.html`，限 ADMIN）：用户 CRUD + 口令重置、角色 CRUD。口令只存 BCrypt 哈希，列表与详情都不回显。
- **默认拒绝自锁**：不能停用或删除当前登录账号；摘掉自己最后一个 ADMIN 角色会被拒 —— 系统始终保留至少一个启用状态的管理员。

### 对话与智能体

- **自定义智能体**：页面创建任意角色（翻译、天气、教育数据分析、代码助手…），配置人设提示词、模型、温度与可用工具；提示词自动汇总到 `agent_code.md`。
- **智能路由**：未绑定智能体的会话由 LLM 三态路由（命中智能体 / 普通对话 / 正在回答追问），并携带最近上下文识别「北京呢？」这类承接上一轮的短追问。
- **参数追问补全**：智能体用 `paramSchema`（JSON 数组）声明参数；缺失必填项时自动追问（上限 3 轮），参数齐全才正式回答。跟进任务会继承上一轮已明确的参数（如「今天」→ 日期=今天）。追问状态**无显式存储**，每轮从历史重放推导，天然跨重启一致。
- **动态规划（Planner）**：会话级 🧭 开关开启后，由 LLM 运行时把用户目标拆成多智能体步骤并**按依赖并行**执行；执行过程实时展示、不写入记忆。规划模式与绑定智能体互斥。**任务状态持久化**：每轮规划落库 `task`/`task_step`，服务重启/中断后可点「继续执行」显式续跑剩余步骤（不重新规划）。
- **双层记忆**：短期窗口（`chat_message` 原文，受 token 预算与条数下限约束）+ 长期滚动摘要（`conversation.summary` / `core_facts`）；超窗历史异步压缩合并，**先推回复、后处理记忆**。
- **流式输出**：SSE 推送 `token`（正文，进记忆）、`progress`（执行过程，不进记忆）、`citations`（引用来源）、`ping`（心跳）、`error` 等事件，前端逐字渲染。
- **链路追踪**：每轮对话的路由来源、规划步骤、改写后检索问句、RAG 命中、工具调用（参数/结果/token/耗时）异步落库 `agent_trace`；页面 🔍 追踪弹窗可查看本会话最近 50 轮。
- **成本看板**：全量成本口径——除「回答本身」（`agent_trace`）外，路由判定/参数抽取/查询改写/视觉识别/记忆合并这些裸 `ChatModel` 调用也各自记入 `llm_usage`（按用途 `purpose` 拆解）；页面 💰 成本弹窗按天趋势 + 按用途聚合展示（近 7/30/90 天）。

### 知识库与多模态

- **知识库 RAG（会话级纯开关 + 自动多库）**：会话开启「📚 RAG」后，每轮自动检索「通用知识库（`agent_id` 为空）＋路由/绑定智能体的专属库」，多库一次合并检索并注入编号上下文；关闭即完全不检索。三段式检索：**粗排召回 → 精排（DashScope text-rerank）→ 编号注入**，精排不可用时降级「向量分截断」。检索前会用最近若干轮历史做**多轮查询改写**（指代消解），首轮/未开 RAG 自动跳过。
- **以「文件」为管理单元**：上传 → 解析 → 分片 → 向量化入库并登记（`kb_file`）；支持 4 种分片策略（fixed / paragraph / recursive / markdown）与重叠字数、**重新分片**（不重传换策略）、**同名重传=替换**。
- **向量存储双写**：MySQL 留档（源，向量以 JSON 文本存 `kb_chunk`）+ Chroma 加速副本。检索优先 Chroma（余弦 TopK），不可用/无命中自动降级 MySQL 余弦（有界扫描，防 OOM）；`POST /api/kb/chroma/sync` 幂等回填副本。Chroma 写入统一延后到**事务提交之后**，避免 MySQL 回滚后副本失配。
- **多模态图片理解**：输入框 🖼 支持多选图片（≤5 张 / 单张 ≤10MB），由视觉模型（默认 `qwen-image-2.0-pro-2026-06-22`，可配）识别成中文 caption 拼入本轮上下文；走 Spring AI 原生多模态（裸 `ChatModel` + `UserMessage.media`，per-request 覆盖模型、多图并发识别）。**原始二进制不进会话存储**——caption 仅当轮可见。
- **文档解析**：附件与知识库支持 txt / md / markdown / csv / json / xml / yml / properties / log / sql 文本，以及 pdf（PDFBox）、docx / xlsx（POI）。

### 工具调用

- **全局能力池**：天气查询、日期解析、SQL 安全查询、表结构查看、样例数据、SQL 预检、ECharts 图表、文本直方图。
- **有界工具循环**：Spring AI 默认的「模型调工具」循环是无上限的，模型反复调同一个工具会死循环拖垮 token。已用自定义 Advisor 装上双刹车——**轮数上限**（默认 10 轮，覆盖「查表→查数→画图」合理长链）+ **连续重复检测**（默认连续 3 次同名同参即停），超限走**软刹车**（追加「基于已有信息作答」指令，不抛错中断），配置见 `application.yaml` 的 `agent.tool-call.*`。
- **按智能体装配**：`agent.tools_json` 控制白名单 —— `NULL`/空 = 挂全量、`[]` = 不挂、`["名"]` = 白名单（按 `@Tool` 名匹配，未指定 name 时即方法名）。未知名忽略、非法 JSON 回退全量。
- **安全护栏**：SQL 工具仅允许只读 `SELECT`/`WITH`，白名单表名（student/class/teacher/subject/course/score）、拒绝多语句与可执行注释、结果行数上限。
- **MCP 远端工具**：官方 `spring-ai-starter-mcp-client` 接入的 MCP server 工具经 `McpToolSource` 收进**同一个能力池**（前端分组显示为 `MCP`），与本地工具一样按 `tools_json` 装配。默认**不声明任何 server**，即「不接入」——启动行为与未引入 MCP 时一致。
  接一个 server：在 `application-local.yaml` 写 `spring.ai.mcp.client.stdio.connections.<名>`，`command` 用可执行文件绝对路径、`args` 首项为 server 入口、其后为允许读写的沙箱根目录（**不要用 `npx`**，Windows 上是批处理包装、且依赖 PATH 与网络）。工具名以 server 返回为准，看 `GET /api/agent/tools` 的 `MCP` 分组。

## 技术栈

| 组件 | 版本 / 说明 |
|---|---|
| JDK | 17 |
| Spring Boot | 4.0.7 |
| Spring AI | 2.0.0（`spring-ai-starter-model-openai`，兼容 OpenAI 协议） |
| MCP | `spring-ai-starter-mcp-client` 2.0.0（官方 MCP 客户端，stdio / Streamable-HTTP / SSE；默认不接 server） |
| MyBatis-Plus | 3.5.16（`mybatis-plus-spring-boot4-starter`） |
| MySQL | 8.x（`mysql-connector-j`） |
| Chroma | 0.5.23（向量加速副本，`spring-ai-chroma-store` 2.0.0；与 MySQL 双写，缺失自动降级） |
| Hutool | 5.8.38（JSON 解析统一用 `JSONUtil`，规避 Jackson `ObjectMapper`） |
| PDFBox / POI | 2.0.30 / 4.1.2（pdf、docx、xlsx 文本抽取） |
| 前端 | 内置静态页面（`static/index.html` + Vue 3 + 原生 CSS） |

> 主对话模型默认 `qwen3.7-flash-2026-07-15`，向量化默认 `qwen3.7-text-embedding`，视觉默认 `qwen-image-2.0-pro-2026-06-22`，精排固定 `gte-rerank-v2`；均可在 `application.yaml` 调整。

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
mysql -uroot -p agent < src/main/resources/sql/system.sql   # 用户/角色表 + 初始角色（登录鉴权依赖）
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
      chat:      {model: qwen3.7-flash-2026-07-15}
      embedding: {model: qwen3.7-text-embedding}
    mcp:                            # MCP 客户端：这里不声明 server（=不接入）；server 配置放 application-local.yaml
                                    # 例：stdio.connections.<名>.command: <node.exe 绝对路径>
                                    #     stdio.connections.<名>.args: [<server 入口 js>, <沙箱根目录>]
      client: {enabled: true, name: my-agent, version: 1.0.0, type: sync, request-timeout: 15s}
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
  api-key: ${APP_API_KEY:}        # 服务级密钥（脚本/机器调用），留空=不校验
  jwt:                            # 用户登录态：enabled=true 时 /api/** 除 /api/auth/login 外都要带 token
    enabled: true
    secret: ${JWT_SECRET:默认值}   # 默认值写在 application.yaml（固定值，重启不变，本地开箱即用）；留空或不足 32 字节 → 启动生成随机密钥并告警（重启后 token 全部失效）
    expire-minutes: 720
    bootstrap-admin: true         # 首次启动且用户表为空时创建初始管理员
    bootstrap-username: admin
    bootstrap-password: ${JWT_BOOTSTRAP_PASSWORD:admin123}
  sse: {timeout-seconds: 300, heartbeat-seconds: 15}
  attachment: {dir: ./data/attachments}
server:
  port: 8080
```

> **配置前缀必须是顶层 `agent.*`**（对应 `@ConfigurationProperties("agent.*")`）。历史上曾因缩进错误被挂到 `spring:` 下导致整段静默失效，改动务必核对。

### 3.1 部署安全（可选）

- **接口访问控制**：默认不校验（本地开发）。若服务会暴露到局域网/公网，设置 `APP_API_KEY=你的密钥` 后重启，所有 `/api/**` 请求必须携带 `X-Api-Key: 你的密钥`（或 `Authorization: Bearer ...`），否则 401 —— 防止他人直接调用 SQL 查询工具、白嫖 LLM Key、读写知识库。该 401 响应体带 `reason: API_KEY_REQUIRED`（见 `ApiKeyInterceptor`），前端据此与「登录态失效」区分，**不会**因此清掉本地登录凭证。
- **前端如何带密钥**：页面静态资源不在拦截范围，但页面发出的 `/api` 请求需要密钥。打开页面点顶栏 **🔑 访问密钥**，填入与 `APP_API_KEY` 相同值即可（仅存浏览器 `localStorage`）。前端所有 `/api` 调用统一经 `apiFetch` 自动附加该头；附件图片走 `/files/**`，无需密钥。
- **登录鉴权（默认开启）**：`/api/**` 除 `POST /api/auth/login` 外都要求 `Authorization: Bearer <token>`，否则 401（带 `reason`，见下），前端会**就地弹出登录框**（`js/auth.js`，不跳页）并展示服务端给出的具体原因。置 `app.jwt.enabled=false` 只关掉**服务端**校验，**前端的守卫仍会拦**：`GET /api/auth/me` 在开关关闭后同样抛 401，前端拿不到「开关已关」这个状态，会一直弹登录框 —— 要真正回到无登录态，得把页面里的 `Auth.requireLogin()` 调用一并去掉。
  - **拒绝原因可区分**：401 响应体除 `message` 外带机器可读的 `reason`（`NO_TOKEN` / `MALFORMED` / `BAD_SIGNATURE` / `MISSING_EXPIRY` / `EXPIRED` / `USER_UNAVAILABLE`，403 为 `ROLE_DENIED`），拦截器同时落 WARN 日志。前端把服务端 `message` 原样展示，因此「没带凭证」与「凭证不被认（密钥换过）」不再被笼统的「登录已失效」掩盖。
  - **响应头形状与其余接口一致**：拦截器手写的 401/403 不再调 `setCharacterEncoding`（那会让 Tomcat 把响应头写成 `application/json;charset=UTF-8`，而 Jackson 输出的都是 `application/json`）—— 同一 API 两种形状容易被误读成「带 charset 的请求才 401」。JSON 按规范即 UTF-8，直接写字节。
  - **启动日志能回答「这次重启换没换密钥」**：`登录鉴权已启用：token 有效期 N 分钟…，签名密钥 N 字节 / 指纹 xxxxxxxx`。指纹是密钥的 SHA-256 前 8 位，只用于跨重启比对（密钥本身不进日志）。看到 `BAD_SIGNATURE` 先比这个：指纹**变了** ⇒ 那份 token 是更早密钥签发的，重新登录一次即可；指纹**没变** ⇒ 不是密钥问题，看下一条。
  - **同一份合法 token 被随机判为 `BAD_SIGNATURE`（并发验签不可靠，已修复）**：Hutool `HMacJWTSigner` 内部持有**单个** `javax.crypto.Mac`（非线程安全），且 `verify()` 的实现是「用同一个 signer 重新签一遍再比对字符串」。把 `JWTSigner` 当单例字段复用时，Tomcat 并发请求会互相污染 HMAC 计算 —— 表现正是「登录后随便点几下就被弹回登录框」，且**同一 token 有时 200 有时 401**，与密钥、有效期都无关。修复在 `JwtTokenService`：只存 `byte[] secret`，签发/校验各自现建一个 signer（`newSigner()`）。复现与回归脚本：`.workbuddy/tools/probe_token_concurrency.py`（并发打同一 token，统计 200/401 分布，修复后应全 200）。
  - **401 与 Content-Type 无关**（已实测：同一 token 下 `application/json` 与 `application/json;charset=UTF-8` 状态码完全相同）。用 `text/plain` 提交 JSON 会得到 **415**「请求体格式不受支持」，而不是 500。
  - **「连不上服务端」不等于「登录失效」**：页面级校验/链接守卫只在服务端明确 401 时才清 token；请求本身失败（服务端重启中）保留登录态，提示「无法连接服务端，请稍后重试」——把网络抖动当成凭证失效会导致一次无谓的重新登录。
  - **前端按 `reason` 决定要不要清登录态，且清之前先复核**：`js/auth.js` 维护 `TOKEN_FAILURE_REASONS` 白名单（`NO_TOKEN`/`MALFORMED`/`BAD_SIGNATURE`/`MISSING_EXPIRY`/`EXPIRED`/`NO_SUBJECT`/`USER_UNAVAILABLE`），**只有命中才视为「凭证真失效」**；`API_KEY_REQUIRED`（服务级密钥缺失）与非 401 一律**保留** token，只弹提示。命中白名单时也不立即清，先并发安全地 `GET /api/auth/me` 复核（`confirmSession()`，多请求共用一个 in-flight 调用），复核通过则连弹框都不弹 —— 单次 401 不再误踢用户。浏览器控制台可跑 `Auth.diagnose()` 打印本地 token 声明/剩余有效期（不含 token 本身）与服务端 reason。
  - **签名密钥**：`JWT_SECRET` 必须是 ≥32 字节的固定值。留空或过短时每次启动都会换随机密钥，表现是「一重启所有人都要重新登录」。生成：`openssl rand -hex 32`。注意 `${JWT_SECRET:默认值}` 里**显式设成空串**的环境变量会覆盖默认值，等同于「未配置」。
  - **有效期**：`app.jwt.expire-minutes` 必须是正数。该字段是 `int`，配置没绑上时默认 0，而 0 分钟意味着 token **一签发就过期** —— 表现是「登录成功后进入页面又让登录」，且不抛任何异常。启动时会打 WARN 并回落到默认 720 分钟。
  - **初始账号**：`sys_user` 表为空时启动自动创建 `admin`（口令取 `app.jwt.bootstrap-password`，默认 `admin123`），请在 `/user.html` 立即修改；表非空时该引导不再触发。
  - **权限模型**：`/api/user/**` 与 `/api/role/**` 需 `ADMIN` 角色（`@RequireRole`），其余接口只要求已登录。
  - **改口令不会踢掉已签发的 token**：token 是无状态的，需要强制下线请先停用该账号（停用状态每次请求都会校验）。
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
├── MyAgentApplication  # 启动类（@MapperScan 指向 ai.mapper + edu.mapper + system.mapper）
├── common/             # 共享基础设施（AI / 教务 / 用户系统共用）
│   ├── config/         # CorsConfig / GlobalExceptionHandler / MybatisPlusConfig(分页插件)
│   │                   # / ApiKeyInterceptor + ApiSecurityConfig(服务级密钥 /api/**)
│   ├── result/         # RestResult / PageResult（统一响应与分页契约）
│   ├── BaseBO          # 分页入参基类：缺省 10 条、上限 100
│   └── exception/      # AiBusinessException / AiErrorCode
├── ai/                 # AI 多智能体平台
│   ├── controller/     # ChatController(SSE) / ConversationController / AgentController
│   │                   # / KnowledgeBaseController / AttachmentController / TraceController
│   ├── service/        # ChatService(编排门面) / ConversationService / AgentService
│   │                   # / KbService / KbSearchService / ChunkingService
│   ├── agent/          # AgentRouter(智能路由) / ParamFillingService / PlannerService
│   │                   # / MemoryMergeService / PromptService / QueryRewriteService
│   │   └── handler/    # RoundHandler + AgentRoundHandler / PlannerRoundHandler / RoundResult
│   ├── chat/           # ChatComposer(请求装配：人设/记忆/材料/工具/RAG)
│   ├── advisor/        # ToolUsageLoggingAdvisor / RoundTraceAdvisor
│   ├── tool/           # ToolRegistry / ToolProvider(注解式) / ToolCallbackSource(动态工具接缝)
│   │                   # / McpToolSource(MCP) / WeatherTools / DateResolver
│   │                   # / SqlQueryTool / SqlSafety / SqlSchemaTool / ChartTool
│   ├── memory/         # DbChatMemory(Spring AI ChatMemory 的 DB 实现)
│   ├── trace/          # RoundTrace / TraceService(异步落库) / LlmUsageService
│   ├── infrastructure/ # attachment / chroma(客户端+副本同步) / document / rerank / vision
│   ├── config/         # ExecutorConfig(线程池) / ChatMemoryConfig / ToolCallingConfig 等 AI 专用
│   ├── properties/     # MemoryProperties / RagProperties / VisionProperties / PromptProperties
│   └── entity/ mapper/ dto/ enums/ constant/
├── system/             # 用户管理系统（登录鉴权 + 用户/角色 CRUD）
│   ├── security/       # JwtTokenService(签发/解析) / JwtAuthInterceptor(拦 /api/**)
│   │                   # / AuthContext(当前用户) / @RequireRole / PasswordHasher(BCrypt)
│   ├── controller/     # AuthController(/api/auth) / SysUserController(/api/user)
│   │                   # / SysRoleController(/api/role)  —— 后两个整个控制器限 ADMIN
│   ├── service/        # AuthService / SysUserService / SysRoleService（+ impl）
│   ├── entity/ mapper/ dto/ vo/ constant/
│   ├── config/         # JwtSecurityConfig（把登录拦截器挂到 /api/**）
│   └── bootstrap/      # UserBootstrap（用户表为空时创建初始管理员）
└── edu/                # 教务系统（每表独立 Controller/Service + Mapper XML）
    ├── controller/     # 10 个 {表}Controller（统一命令式：POST /page、GET /list、GET /{id}、
    │                   # POST /save、PUT /update、DELETE /delete/{id}）
    │                   # / EduQueryController(4 关联查询) / EduMetaController(看板)
    ├── dto/ vo/        # {表}DTO（筛选条件，继承 BaseBO）、{表}VO（join 出的可读名）、OptionVO（下拉选项）
    ├── service/        # 10 个 {表}Service extends IService（分页/下拉/写操作 + 唯一性校验 + 删除前引用校验）
    │                   # / EduQueryService(关联查询) / EduMetaService(看板统计)
    ├── entity/         # 10 个教务实体（Clazz/Course/Score/... 驼峰字段）
    └── mapper/         # 10 个 BaseMapper + 跨表分页/下拉 SQL（见 mapper/edu/*.xml）
resources/
├── application.yaml     # 数据源 / 模型 / 平台自身配置
├── prompts.yaml         # 全部提示词模板（agent.prompt.*）
├── sql/schema.sql       # 对话/知识库/智能体建表（幂等）
├── sql/alter.sql        # 存量库补列
├── sql/system.sql       # 用户/角色表 + 初始角色（幂等）
├── mapper/edu/*.xml     # 教务关联查询 SQL（namespace 对应 edu.mapper）
└── static/              # 前端（index.html / login.html / chat.html / edu.html / user.html + js / css）
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
| 工具注册 | 两类来源统一进 `ToolRegistry`：注解式（`ToolProvider` + `@Tool` 反射）与动态式（`ToolCallbackSource`，如 MCP 远端工具、运行时才知道有哪些）；同名时注解式优先并告警 |

## API 一览

> 除 `POST /api/auth/login` 外，下表全部接口都要求 `Authorization: Bearer <token>`；标「需 ADMIN」的还要求具备 `ADMIN` 角色。

### 认证

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/login` | 登录（**唯一免登录接口**）→ `{token, expiresIn, user}`；账号停用返回 403 |
| GET | `/api/auth/me` | 当前登录用户信息 |
| POST | `/api/auth/password` | 修改自己的口令 `{oldPassword, newPassword}`（校验原口令） |

> 没有 logout 接口：JWT 无状态，服务端没有会话可销毁 —— 退出登录 = 前端丢弃本地 token。

### 用户管理（需 ADMIN）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/user/page` | 分页，筛选走 body：`keyword`（登录名/显示名）、`status`、`roleId` |
| GET | `/api/user/{id}` | 单条详情 |
| POST | `/api/user/save` | 新增 `{username, password, nickname, email, status, roleIds}` |
| PUT | `/api/user/update` | 编辑 `{id, nickname, email, status, roleIds}`（登录名与口令不在此改） |
| PUT | `/api/user/{id}/password` | 管理员重置他人口令 `{password}` |
| DELETE | `/api/user/delete/{id}` | 删除用户（连带清理角色关联） |

### 角色管理（需 ADMIN）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/role/page` | 分页，筛选 `keyword`（编码/名称） |
| GET | `/api/role/list` | 全部角色（用户表单的角色多选、列表筛选用） |
| POST | `/api/role/save` | 新增 `{code, name, description}`（编码统一转大写后校验格式） |
| PUT | `/api/role/update` | 编辑 `{id, name, description}`（编码不可改） |
| DELETE | `/api/role/delete/{id}` | 删除角色；已分配给用户时 409 |

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
| POST | `/api/chat/task/resume` | SSE 流式续跑未完成任务（显式按钮触发）：回填已完成步骤、只跑剩余步骤 |
| GET | `/api/chat/task/running?conversationId=` | 查询当前会话的 RUNNING 任务（无则 null，供「继续执行」提示条） |

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

### 成本看板

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/cost/summary?days=` | 近 N 天（默认 30、上限 90）全量成本聚合：回答本身 + 裸调用按用途拆解 |

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
| `llm_usage` | trace_id, conversation_id, purpose, model, prompt_tokens, completion_tokens, total_tokens, created_at | 裸 LLM 调用成本流水（全量成本口径）：路由/参数抽取/查询改写/视觉/记忆合并各记一条，按用途拆解 |
| `task` | id, conversation_id, user_goal, status, total_steps, done_steps, result, created_at, updated_at | 规划任务：一轮规划落库一条，状态机 `RUNNING→DONE/FAILED/CANCELLED`；单会话单 RUNNING |
| `task_step` | id, task_id, step_index, agent_code, instruction, depends_on, status, retry_count, output, error, citations_json, started_at, finished_at | 任务步骤：逐步增量提交产出；`FAILED` 续跑重试一次，累计 ≥2 判确定性失败 |
| `sys_user` | id, username, password, nickname, email, status, last_login_at, created_at, updated_at | 登录账号；`password` 为 BCrypt 哈希（自带盐），`status=0` 停用后已签发 token 立即失效 |
| `sys_role` | id, code, name, description | 角色；`code` 是授权判定依据（`@RequireRole` 比的是它），不可修改 |
| `sys_user_role` | id, user_id, role_id, created_at | 用户-角色多对多授权，权限取并集；无外键约束，删除用户时由服务层清理 |

> 教务系统另用一组**业务表**（与对话主表独立，建表/演示数据见 `sql/business.sql`）：
> `subject`（科目）、`teacher`（老师）、`class`（班级）、`student`（学生）、`semester`（学期）、
> `course`（课程）、`period`（节次）、`course_arrangement`（排课）、`exam`（考试）、`score`（成绩）。
> 这 10 张表由教务系统（`/edu.html`）读写，走 `/api/edu/**` 接口，与 AI 对话数据完全分离。
> 接口统一为**命令式**：`POST /api/edu/{表}/page`（筛选条件走 body）、`GET /api/edu/{表}/list`
> （下拉选项，只回 `id` + 拼好的可读文案）、`GET /api/edu/{表}/{id}`、`POST /api/edu/{表}/save`、
> `PUT /api/edu/{表}/update`（id 在 body）、`DELETE /api/edu/{表}/delete/{id}`；
> 外键列的可读名由服务端 join 进 `{表}VO`，前端不再做 id→名称映射，也没有集中的字典接口。
> 4 个只读关联查询为顶层路径 `GET /api/edu/score-detail`、`/score-stats`、`/timetable`、`/schedule`，
> **四个都分页**（`page`/`size`，默认每页 10 条、上限 100，一律回 `PageResult`；行是类型化投影
> `ScoreDetailVO` / `ScoreStatsVO` / `TimetableVO` / `ScheduleVO`，不用 `Map`）；均可带筛选参数：成绩明细 `classId`/`subjectId`/`semesterId`/`keyword`
> （模糊匹配学生姓名或学号）、成绩统计 `classId`/`subjectId`/`semesterId`、班级课表 `classId`/`teacherId`/`dayOfWeek`、
> 考试日程 `classId`/`subjectId`/`semesterId`，不传即不过滤。

## 前端界面

- **顶栏**：会话列表（含 🧭 规划标记）、当前会话徽标（🧭 规划模式 / 📚 RAG）、🔑 访问密钥、🔍 追踪、💰 成本；最右为**当前登录用户 + 用户管理入口（仅 ADMIN 可见）+ 退出**。
- **输入区**：🖼 图片多选（≤5 张）、📎 文档上传、📚 RAG 开关、🧭 规划开关（绑定智能体的会话置灰）。
- **知识库页**：库/文件管理、上传与重新分片、分页查看知识块、Chroma 状态条与「同步本库」。
- **追踪弹窗**：路由来源、规划步骤、检索问句、RAG 命中、工具调用、token 与耗时。
- **成本看板弹窗**：全量成本按天趋势（堆叠柱）+ 按用途拆解（饼图），近 7/30/90 天切换。
- **教务系统**（`/edu.html`，独立入口）：10 张业务表（科目/老师/班级/学生/学期/课程/节次/排课/考试/成绩）的增删改查 + 4 个关联查询看板（学生成绩明细、成绩统计、班级课表、考试日程），复用深色色板，与 AI 对话页分离。单表页与关联查询页共用同一套顶部搜索条（文本输入 + 外键/枚举下拉 + 查询/重置，按 `tableMeta.search` / `queryMeta.search` 声明渲染）、序号列、右下分页（首页/上一页/下一页/尾页/跳页[/每页条数]）；新增与删除走自绘弹窗。

- **登录**：登录界面全站只有一份 —— 结构在 `js/auth.js`（`Auth.openLogin()`），样式在 `css/auth.css`（`auth-` 前缀变量与类名，与各页样式互不污染）。
  - **首页**（`/index.html`）右上角「登录」按钮：点击**就地弹框**（不跳页），登录后原地变为「用户名 + 用户管理（仅 ADMIN）+ 退出」。首页自身不含登录逻辑，只放一个 `data-auth-nav` 挂载点。
  - **受限页**（chat / edu / user）**先锁住页面、再确认登录态，最后才决定是否渲染页面**：`Auth.requireLogin()` 在 `<head>` 里同步把整页盖住（`html.auth-locked` + 一句「正在校验登录状态…」），无 token 直接弹登录框；有 token 也先向 `GET /api/auth/me` 确认，**只有确认有效才解除遮罩放行渲染**。所以直接打开 `/chat.html`、`/edu.html` 不会先闪一眼未登录的空壳页（此前只看本地 token、页面照常渲染，等首个接口 401 才弹框，观感是「先进去再被踢出来」）。弹的是**整页观感的登录框**（不透明底，不会把空壳页透出来），登录成功后自动重载当前页；关掉弹框会回到首页 —— 受限页在没有登录态时数据全 401，留在空壳页面上没有意义。运行中 401 同样弹框并提示「登录状态已失效」。需要登录才能走的链接加 `data-auth-required` 即可，点击时由 `Auth.guardNavigation()` **先确认登录态再放行**：未登录就地弹框，已登录也先向 `GET /api/auth/me` 确认 token 仍有效（token 会被服务端单方面作废 —— 过期、账号停用、换密钥重启），确认通过才跳转，避免「先跳进去、再被踢出来」。
  - `/login.html` 只是弹框的「整页模式」外壳（调 `Auth.mountLoginPage()`），保留它是给未登录的深链访问一个落地地址；登录成功后回跳 `?redirect=`，只接受站内路径，防开放重定向。
  - token 存 `localStorage`（键 `my_agent_token`），与 `X-Api-Key` 的存法一致；退出登录 = 前端丢弃 token（服务端无会话可销毁）。
- **用户管理**（`/user.html`，仅 ADMIN）：左侧切换「用户管理 / 角色管理」。用户列表支持关键词/状态/角色筛选，可行新增、编辑（角色为复选框多选）、重置口令、删除；角色列表支持编码/名称筛选，可新增、编辑（编码锁定）、删除。口令全程只写不读，界面上不提供查看。

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
