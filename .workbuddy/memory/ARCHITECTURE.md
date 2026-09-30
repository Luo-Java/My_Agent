# 架构与组件契约（My_Agent）

> 本文件是 `.workbuddy/memory/MEMORY.md` 的展开细节，**按需查阅**（被自动注入的只有 MEMORY.md）。
> 逐日实施明细见同目录 `YYYY-MM-DD.md`。
> 阅读顺序建议：要改某个组件 → 先在上面的「架构要点」定位它 → 再看「已知缺陷 / 坑」里有没有相关红线。

---

## 一、架构要点

- 多 Agent 平台（Spring Boot 4.0.7 + Spring AI 2.0.0 + MyBatis-Plus 3.5.16）。`ChatService` 是编排门面；
  路由 / 参数补全 / 记忆合并 / 提示词分在 `AgentRouter` / `ParamFillingService` / `MemoryMergeService` / `PromptService`。
- **包结构**（全在 `org.luo`）：`service`=业务服务；`infrastructure`=外部集成(chroma/vision/document/attachment/rerank)；
  `agent`=核心（＋`handler`: Round/PlannerRound/AgentRound/RoundResult）；`chat`=ChatComposer；
  `advisor`=ToolUsageLogging + RoundTraceAdvisor；`trace`=RoundTrace + TraceService；`tool`=ToolProvider 实现族。
  **搬包脚本务必保留 `org/luo/` 目录前缀**，否则目录与 package 声明错位。
- **`@ConfigurationProperties` 记录须登记进 `MyAgentApplication` 的 `@EnableConfigurationProperties`**
  （Prompt / Vision / Rag / Memory），否则不生效。紧凑构造器兜默认值 → 访问器返回的包装类型保证非空。
- **`RoundResult` 4 元组**（reply / clarified / needSaveExchange / **citations**）；
  新增会话形态实现 `RoundHandler` 须按 `handle(conv, cid, message, material, progress, trace)` 签名。
- **`ChatService` 收尾顺序（勿乱）**：*推回复 → 推 citations 事件 → 落库附件/引用 → 异步落库追踪 → 异步合并记忆*。
  旁路数据一律排在用户看到答案之后。
- **Agent 参数补全**：`paramSchema`(JSON 数组) 驱动追问，上限 `ParamFillingService.MAX_CLARIFY=3`；
  **无显式状态**，每轮从 DB 历史重放推导（天然跨重启/多实例一致），**依赖「chat_message 不物理删除」**。
  历史读取已收敛为 `getRecentHistory(cid, HISTORY_SCAN_LIMIT=50)`。
- **按智能体装配工具**：`agent.tools_json` —— **NULL/空 = 不限制（挂全量）**、`[]` = 不挂、
  `["名"]` = 白名单（按 `ToolDefinition.name()` 匹配；`@Tool` 未指定 name 时**即方法名**，改名会使白名单失配并 warn）。
  `ToolRegistry.resolve()` 是唯一解析入口（未知名忽略、非法 JSON 回退全量）。**唯一挂载点 `ChatComposer.decorateRequest`**。
  **坑**：`AgentService.applyFields` 里 toolsJson 的 `null` 是**有意义取值**（=全部工具），必须原样 set。
- **前置链并行预取**：`ChatComposer.prefetchRetrievalQuery`（池 `roundPrefetchExecutor`）在
  `AgentRoundHandler.handle` **最前**发起，与路由/参数抽取并行，正式回答前 `resolveQuery` join
  （超时 10s / 中断 / 异常一律回退用户原话）。**预取只加速、不承担正确性**；`ragOn(conv)==false` 返回 `null`
  （零额外调用）。走追问分支时结果作废（可接受）；**规划模式不预取**（有意，回退概率低）。
- **会话级 RAG 纯开关 + 自动多库 + 三段式检索（勿回退）**：conversation 只存 `rag_enabled`。
  `KbSearchService.buildKbContext(ragEnabled, agent, query)` 返回 **`KbContext(text, citations)`**：
  - ① **粗排**：目标库 = 通用全局库(`kb.agent_id IS NULL`，只查不建) + 该 agent 专属库；
    多库一次合并检索（Chroma 单 collection 按 kb_id OR，query 只向量化一次），召回 `recall-k`(20)，
    下限用**宽松**的 `recall-min-score`(0.10)；无命中回退 MySQL（有界）。
  - ② **精排**：`infrastructure/rerank/RerankService` 调 **DashScope 原生 text-rerank**
    （不在 OpenAI 兼容路径下，Spring AI 2.0 无 rerank 抽象 → Hutool `HttpRequest` 手写；
    key 复用 `spring.ai.openai.api-key`）。按 `rerank-min-score`(0.20) 过滤取 `top-k`(3)。
    **失败/不可用返回 null → 降级「向量分降序 + `min-score`(0.25)」**；精排成功但候选全被滤掉 →
    **直接返回空、不退回向量分**。**两套阈值尺度独立**（rerank 0~1 vs 余弦）。
    **阈值口径不得随候选条数变化**：只要候选非空且精排可用就必须走精排（含只有 1 条候选的情况）。
  - ③ **编号注入**：命中块渲染 `[n] [库名|来源] 内容`，套 `prompts.yaml` 的 `agent.prompt.kb-context` 模板；
    **同趟产出**与编号 1:1 的 `KbCitation`。
- **RAG 引用溯源**：`KbCitation` 由 `KbSearchService.render` 与资料块**同趟产出**（拆两次算会导致序号与来源漂移）。
  传递链 `ComposedRequest(spec, citations)` → `RoundResult.citations` → `ChatService`，
  出口三处**同一份 JSON**（`KbCitation.toJson/parse` 是唯一序列化点）：
  落库 `chat_message.citations_json`（仅 assistant）、SSE `citations` 事件、`agent_trace.citations_json`。
  **红线同附件**：独立列、`DbChatMemory.get()` 只读 content、零 token。**规划模式只回传最后一步引用**
  （每步各自从 [1] 编号）。
- **agent_trace（纯旁路）**：`trace/RoundTrace` 是一轮的可变收集器，**显式沿调用链传递** + 经 `decorateRequest`
  塞进 **Spring AI advisor 上下文**（键 `RoundTrace.CONTEXT_KEY`）——**刻意不用 ThreadLocal 做主上下文**
  （规划器要做 DAG 并行，ThreadLocal 会静默丢数据）。`RoundTraceAdvisor`(order `MAX_VALUE-90`，在工具循环之内)
  采集工具调用（`AssistantMessage.toolCalls` 取 args + `ToolResponseMessage` 取 result，按序对齐、截 300 字；
  **`syncToolCalls` 只增不减**避免末轮空集覆盖）与 token（`Usage` 累加；
  **ThreadLocal 只做 before→after 的同线程过渡**，`response.context()` 优先兜底）。
  落库 `TraceService.saveAsync`（`traceExecutor`，回复产出后异步、失败只记日志、队列满宁可丢追踪）。
  查询 `GET /api/trace`、`GET /api/trace/{traceId}`。**本表删掉对话照常跑**；
  水位守卫 `ChatService.needsWatermark` = 有附件 **或** `conv.ragEnabled`。
  - **引用 / 命中数的唯一接线点是 `ChatService.runRound` 出口**（fallback 兜底之后、`return r` 之前调
    `trace.citations(r.citations())`）——引用产出在**检索环节**（`ChatComposer/KbSearchService`），
    不在模型调用链上，**Advisor 采集不到**；漏掉这一步则 `citations_json` 恒 NULL、`kb_hit_count` 恒 0，
    DDL/DTO/前端全在、数据全空（「看似功能完整却静默失效」）。
  - **Advisor 的「无 trace」分支必须先 `CURRENT.remove()`**：`before()` 里 `if (trace == null) return request;`
    会跳过清理，而 `after()` 的 `finally` 只在正常返回时执行 → 若上一次调用在 `after` 前抛异常，
    boundedElastic 复用的线程会残留旧 trace，下一轮「无 trace」调用命中兜底分支，
    把 token 累加到**已落库的旧记录**上。
- **对话附件三通道分离（勿混）**：① 纯提问 `message` → 走记忆 Advisor + 路由/RAG，唯一落 `chat_message.content`；
  ② 解析文本 `material`（图片 caption / 文档文本，单附件截 30000 字）→ `ChatComposer.withMaterial`
  注入**当轮 system**，仅当轮可见；③ 展示元数据 `attachmentsJson`（type/filename/storedName/size，**不含正文**）
  → 落 `chat_message.attachments_json`，仅供历史回看。**红线**：`DbChatMemory.get()` 只读 `content`。
  入口 `POST /api/chat/attachment/process`；静态访问 `/files/**`（`AttachmentWebConfig`，**不带 `/api` 前缀**
  否则 `<img>` 无法带 `Authorization` 头被 401）；落盘目录 `app.attachment.dir`。
  上传上限：**必须显式配置 `spring.servlet.multipart`**（现 `20MB`/文件、`80MB`/请求）。Spring 默认只有
  1MB/文件、10MB/请求，而前端 `app.js` 允许 `15MB × 5` 个附件 ⇒ 不配就是「浏览器能选中、服务端拒收」。
  超限异常由 `GlobalExceptionHandler` 转 **413**（不接会落通用兜底被当成 500）。**约定：后端上限 ≥ 前端允许值**。
  落库经 `ConversationService.attachToLatestUserMessage(cid, 水位, json)`（水位守卫防误挂历史消息）。
- **多模态 `VisionService`**：Spring AI 原生多模态(`UserMessage.media`)，per-request
  `OpenAiChatOptions.model=qwen-vl-plus` 覆盖主对话模型；多图并发(`visionExecutor`) + 失败隔离。
- **知识库以「文件」为管理单元**：`kb_file`(kb_id+file_name 唯一，≤200 字符)。上传 → `KbService.registerFile`
  解析分块向量化入库并登记；同名重传 = 替换（全成功才删旧写新）；rechunk 可不重传换策略/重叠重切。
  分片 `ChunkingService`+`ChunkStrategy` 4 策略(fixed/paragraph/recursive 默认/markdown)，overlap 默认 60(≤200)。
- **删除侧必须三处同步清 Chroma**：`KbService.deleteKb` / 同名重传 / rechunk 都调 `chromaStore.deleteByChunkIds`；
  **`AgentService.deleteAgent` 级联删专属库时同样要清**（它曾只删 MySQL 三表）。
  **必须「先取 chunkIds → 再删 MySQL → 最后删向量」**，顺序不可换：MySQL 行删掉后就再也查不到该清哪些 id。
  漏了会永久残留孤儿向量——Chroma 回填（`POST /api/kb/chroma/sync`）是 **upsert-only、不清孤儿**。
- **鉴权（`/api/**` 的唯一闸门）**：`app.jwt`(env `JWT_SECRET`) → `JwtAuthInterceptor` 校验
  `Authorization: Bearer <token>`（白名单只有 `POST /api/auth/login`），每请求回库复核状态+角色。
  旧的**服务级密钥** `app.api-key`/`APP_API_KEY` + `ApiKeyInterceptor`/`ApiSecurityConfig` **已删除**
  （2026-09-29）：它与登录流程冲突 —— 启用后连 `POST /api/auth/login` 都会被拒（前端登录请求只带
  `Authorization`，不带 `X-Api-Key`），用户进不来；而 `/api/**` 已由 JWT 全覆盖，机器调用也得先登录。

- **token 内部约定（踩过的坑，逐条都有实测）**
  - **有效期用自定义 claim `expMs`（毫秒），不用标准 `exp`**：Hutool 对 claims 里 `Date` 的序列化单位属
    其内部约定，依赖它会出「一签发就过期」的**静默**故障（不抛异常，只是每次校验都判已过期）。
  - **`app.jwt.expire-minutes` 是 `int`，没绑上就是 0**（原始字段无初始值）⇒ 0 分钟 = 签发即过期，症状是
    「登录成功后进页面又被要求登录」，且日志里什么都没有。`JwtTokenService` 已收敛为 ≤0 → WARN + 回落 720，
    构造期 INFO 打出生效 TTL。`JwtProperties` 的 `enabled` 默认 true（配错也拦）、`secret` 默认空。
    判断鉴权异常**先看这几条默认值**。
  - **密钥指纹进启动日志**：`登录鉴权已启用：token 有效期 N 分钟，签名密钥 N 字节 / 指纹 xxxxxxxx`
    （密钥的 SHA-256 前 8 位，只用于跨重启比对，密钥本身不进日志）。看到 `BAD_SIGNATURE` 先比指纹：
    变了 ⇒ token 由更早密钥签发，重新登录即可；没变 ⇒ 不是密钥问题，查并发验签（下一条）。
    本机实测（2026-09-22）：未设 `JWT_SECRET`，生效的是 `application.yaml` 里的固定默认值，指纹跨重启不变。
    注意 `${JWT_SECRET:默认值}` 的默认值在环境变量**被显式设成空串**时**不生效**。
  - **`JWTSigner` 绝不能共享（2026-09-22 定案的真凶）**：Hutool `HMacJWTSigner` 内含**一个**
    `javax.crypto.Mac`（非线程安全），而它 `verify()` 是「重新签一遍再比对字符串」⇒ 把 signer 存成
    字段/单例，并发请求会互相污染 HMAC 计算，**同一份合法 token 会被判 `BAD_SIGNATURE`**（连带 `MALFORMED`）。
    实测（真实编译产物，Hutool 5.8.38）：16 线程 × 100 共享 signer → `OK=232 / BAD_SIGNATURE=1288 /
    MALFORMED=80`；共享 signer 并发 `issue()` → 800 次里 600 次「签发即无效」。修法：字段只留密钥 `byte[]`，
    `issue()`/`verify()` **每次现建** `JWTSignerUtil.hs256(secret)`（改后 1600/1600 通过、签发 800/800 有效）。
    线上症状是**「登录成功后一进页面点几下就被要求重新登录」**——页面挂载并发发起 5 个接口，总有几个被
    误判 ⇒ 前端把本来有效的登录态清掉。复现（当前进程实测过）：同一份合法 token 并发打接口，会出现
    部分 401 `BAD_SIGNATURE`（串行必现 200）—— 见上「串行 curl 永远复现不出并发问题」。
  - **`BAD_SIGNATURE` 的判读顺序**：先排除「并发验签不可靠」（上条），再谈「token 由另一把密钥签发」。
    **串行 curl 永远复现不出并发问题** —— 判定服务端是否可靠要看并发分布，不是单次结果。
  - **401 必须能说清原因**：`JwtTokenService.verify()` 返回 `TokenCheck`（`NO_TOKEN`/`MALFORMED`/
    `BAD_SIGNATURE`/`MISSING_EXPIRY`/`EXPIRED`/`NO_SUBJECT`），拦截器把它写进响应体 `reason` + 落 WARN，
    前端用 `resp.clone()` 读 `message` 原样展示。**别再退化成一句笼统的「登录已失效」**——那会让
    「头没到服务端」和「密钥换过」无法区分。
  - **手写的 401/403 响应必须与其余接口同形状**：拦截器里**别用 `setCharacterEncoding` + `getWriter`**
    （Tomcat 会追加 `;charset=UTF-8`，而 Jackson 输出的是 `application/json`）—— 同一 API 两种响应头形状
    会被误读成「带 charset 的请求才 401」。直接 `getOutputStream().write(utf8Bytes)`。
  - **角色闸门**：`@RequireRole(SysRoleCode.ADMIN)`（拦截器读 `HandlerMethod` 注解，**不引 AOP**），
    不通过 403 `ROLE_DENIED`。**权限刻意不入 token**：拦截器每请求回库取状态+角色 ⇒ 改角色/停用立即生效。
    当前三处：`/api/user/**`、`/api/role/**`、`/api/cost/**`。**至少留一个启用的 ADMIN**：删除/停用/摘角色
    共用 `assertAdminRemains`，防最后一个管理员把自己锁在管理端外。

---

## 二、记忆 / 上下文窗口

- **记忆窗口 = 预算 + 下限 + 单条截断**（`MemoryProperties`/`agent.memory.*` + `DbChatMemory.computeWindowStart`）。
  缺下限时「单条消息自己超预算」会把起点推到列表末尾 → **整个窗口塌缩为空**（模型「突然失忆」）。
  **摘要侧与注入侧必须完全同源**：同一份 `MemoryProperties` + **同一个 `DbChatMemory.SQL_FETCH_LIMIT`（已改 public）
  取同一段「最近 N 条」列表**，合并侧再把起点换算回全量索引 `windowStart = total - recent.size() + startInRecent`；
  否则历史超过预取上限时两把尺子不同，中间那段**既不摘要也不注入**（记忆静默丢失）。
  `MemoryMergeService` 只读待摘要区间（`getMessagesRange`），不做无界 `getHistory`。
- **`chat_message` 的排序一律带 id tiebreaker**（`ORDER BY created_at, id`，倒序亦然）：
  `created_at` 是 **DATETIME(0) 秒级**，同一轮的 user/assistant 时间完全相同，只按时间排序时先后取决于执行计划
  （当前正确仅因 InnoDB 二级索引隐含主键，优化器一改走 filesort 就错序 → 表现为「用户的话与回答对不上」）。
  **不要依赖纳秒偏移**——`plusNanos` 会被该列静默截断（已从 `DbChatMemory.add` / `saveClarifyExchange` 移除）；
  改 `DATETIME(3)` 也无用（同毫秒多条仍需 tiebreaker），故**刻意不做该 DDL**。
- **记忆读写的真实时机（读源码核实，勿再误判）**：`MessageChatMemoryAdvisor.before()` **既读又写**
  （先 `get` 注入历史、紧接着 `add` 落库 user 消息），`after()` 落 assistant。工具循环在 `OpenAiChatModel` 内部
  （`ToolCallingManager`），**不经过 ChatClient 的 advisor 链** → 一轮 `call()` 的 `before/after` **各只执行 1 次**，
  「工具循环 N 次读库」这个说法是错的。**推论：不要做请求级缓存**——缓存须在 `add` 时失效，而 `add` 紧跟 `get`，
  几无收益；ThreadLocal 方案一旦清理遗漏（boundedElastic 复用）会让下一轮静默读到上一轮历史。

---

## 三、SSE 传输层

- **必须有心跳 + 有限超时**：前置链（路由→参数抽取→改写→检索）**完全不发字节**，易被反向代理当空闲切断 →
  `ChatController` 独立心跳（`app.sse.heartbeat-seconds`，发 `TYPE_PING`，前端天然忽略未知类型）
  + 有限超时（`app.sse.timeout-seconds`；原先 `0L` 永不超时会把「上游卡死」变成「连接永久泄漏」）。
  心跳**刻意不用 `Flux.merge/interval`**——无限流会让 emitter 永不 complete。
- **「打字机」切片（`ChatService.emitChunks`）**：帧间隔 `TYPING_FRAME_MS`=15ms（与 `stream()` 的 `delayElements`
  共用同一常量）+ **分片大小按答案长度自适应** `ceil(len / (TYPING_MAX_MS/TYPING_FRAME_MS))`
  ⇒ 「帧数 × 帧间隔 ≤ `TYPING_MAX_MS`=2000ms」，**总时长恒定有界**。
  旧实现固定 4 字/片 → 延迟随长度线性累加（4000 字平白多等 ~15s，且内容早已生成完、纯人为延迟）。
  短答案（≤133×4=532 字）仍按 `TYPING_MIN_CHUNK`=4 字推送，逐字观感不变。

---

## 四、SQL 工具 / 只读安全

- **`SqlSafety` 表白名单的三条硬约束**：
  ① 表名提取必须覆盖 **FROM/JOIN 之后的整段表引用**（逗号多表、别名、**子查询收尾的 `)`** 都要作为切分/终止点）
  ——只取「后一个标识符」会被 `FROM student, conversation` 绕过；
  ② **必须显式拒绝 `/*!…*/` 版本注释与 `/*+…*/` 优化器提示**——它们会被 MySQL 执行、却被
  `stripCommentsAndStrings` 删掉，「校验的文本」≠「执行的文本」，无法还原只能拒绝；
  ③ **CTE 名会被当表名**（`WITH t AS (…) … FROM t` 被拒）属**有意保留的「过严」**（旧实现亦然），
  放行需先解析 WITH 定义、反而扩大放行面，故不做。
  改这个正则**务必跑多形态用例回归**（逗号多表 / 别名 / LEFT JOIN / 多级 JOIN / 子查询 / IN 子查询 / UNION /
  反引号 / 换行），本次即靠此查出「子查询漏提取 → 系统表被放行」。
- **`SqlQueryTool` 的重试保护是「三层」**（都在 `query()` 内，是启发式保护、非精确状态）：
  ① **同一条 SQL** 连续失败 `MAX_SAME_SQL_FAILS`=3 次 → 直接拒绝，逼模型换写法；
  ② **60s 窗口内**失败超 `MAX_WINDOW_FAILS`=10 次 → 熔断，防死循环烧 token；
  ③ **失败计数表容量上限 `MAX_TRACKED_SQL`=500**：模型每次改写都产生新 key，只靠「成功」与「熔断」两个清理时机
  会长期累积 → 超限按**插入顺序**（`failKeys` FIFO）淘汰最早记录，并同步清 `failCounts` 中对应项。
  `failKeys` 在成功时**不回删**（队列自身按容量自限，残留 key 淘汰时对 map 做一次幂等 remove 即可），
  因此 `failCounts` 键集恒为 `failKeys` 子集，两张结构都有上界。

---

## 五、Chroma（2026-09-10 多次修复，勿回退）

- 官方 `spring-ai-chroma-store` 2.0.0（**非 starter**）；本机 `http://127.0.0.1:8000`，默认 collection `kb_chunks`。
- **命名空间必须 `default_tenant/default_database`**：Spring AI 默认 `SpringAiTenant` 在 0.5.x 不存在；
  且其 `afterPropertiesSet` 先 `getCollection()`，0.5.x 对不存在集合返回 **400 InvalidCollection 而非 404**
  → Spring AI 只处理 404 → 抛「Collection xxx does not exist.」→ `initializeSchema(true)` 没机会建库（新装必复现）。
  故 build 前 `ensureCollection()` 幂等预建。
- **集合必须 cosine 空间**：0.5.x 不从 metadata `hnsw:space` 读空间（会建成 l2，让命中被阈值全滤掉），
  须原生 `POST /api/v1/collections` 传 `configuration.hnsw_configuration.space=cosine`；
  `detectSpace()` 发现 l2 且**空集合**才自动删建，非空保留。
- **相似度语义坑**：Chroma 0.5 cosine 空间 `distance = 2·(1−cos)` → `score = 2·cos−1`，**非真实余弦**
  → 须还原 `realCos = (score+1)/2` 再与阈值同口径，否则相关结果全被滤掉。
- **绕过 Spring AI 阈值**：`similaritySearch` 强制 `threshold∈[0,1]`（传 -1 抛异常），
  且 0.0 只留 `cos≥0.5`、丢掉 `[0.25,0.5)`；故检索直接 `api.queryCollection` 自算 query 向量
  + `where.$or` 多库合并、自过滤。
- **upsert 批大小受 Embedding 模型限制**：`text-embedding-v3` 单次 ≤20 → `chroma.upsert-batch-size` 默认 **10**。
- **冷却重试**：`nextRetryAt` + `chroma.retry-interval-seconds`(默认 60)，「先起应用后起 Chroma」自动自愈，
  不永久降级。
- **绝不静默降级**：`logSkipped(op)` 限频 warn；`status()` 暴露 connected/space/documentCount/lastError；
  `GET /api/kb/chroma/status`；`POST /api/kb/chroma/sync` 幂等回填（**只 upsert，不清孤儿**）。
- **副本写入时机（2026-09-14 统一，勿回退）**：所有事务内的 Chroma 写/删都经
  `ChromaSyncSupport.afterCommit(...)` 延后到 MySQL 提交之后（`KbService` 6 处 + `AgentService.deleteAgent`）；
  无事务上下文时立即执行。理由：Chroma 不参与 MySQL 事务，事务内写副本会在回滚后留下孤儿向量，而检索走
  Chroma 优先、且直接用副本 content 构造命中 → 会返回库里**已不存在**的 chunk（幽灵引用）。
  失败只 warn、**绝不外抛**（afterCommit 抛异常会把「已提交」变成接口报错）。因此 `registerFile` 不再返回
  `chromaSynced`（返回时结果未产生），副本实况一律看 `/chroma/status`；`syncToChroma` 是回填命令，保持同步执行。
- **三层拆分（勿回退）**：`ChromaClient`(全量操作适配：拆包 / 余弦还原 / 多库 OR / 分批写)
  ← `ChromaConnection`(lazy + 冷却重试 + `ensureCollection` 原生建库与空间校正 + `status`)
  ← `ChromaVectorStoreService`(纯业务门面：`KnowledgeChunk→ChromaDoc` + 降级 warn)。
  `KbSearchService` 用 `ChromaClient.ChromaHit`。

---

## 六、已知缺陷 / 坑（框架与库）

- **Spring AI 2.0.0：`stream()` + `@Tool` 必崩 `NoSuchElementException`**
  （`OpenAiChatModel$ChunkMerger` 对工具调用 `Optional` 直接 `.get()`）。
  带工具智能体改用 `call()` 走完工具循环后再切片模拟流式。
- **Spring AI 2.0 的 LLM 超时只有全局一处**：`spring.ai.openai.timeout`(60s) + `max-retries`(默认 3)。
  由自动配置在建 `setupSyncClient` 时固化，**运行时改不了**；`OpenAiChatOptions.timeout` 是**死字段**
  （`OpenAiChatModel` 全文无引用）→ **per-request 超时做不到，不要再试**。已收紧 `max-retries: 1`。
- **`CallResponseSpec.content()` 标注 `@Nullable`**：模型可能返回 null → 把 `spec.call().content()`
  直接塞进 `Map.of(...)` 会 **NPE**（`Map.of` 拒绝 null 值）。
- **自定义配置前缀必须写顶层**：`agent.*` 曾缩进 2 格挂到 `spring:` 下 → 实际键变成 `spring.agent.rag.*`
  → **整段配置静默失效**（默认值与 yaml 恰好相同，长期未暴露）。
  新增配置段后 `grep -n "^[a-z][a-z-]*:" application.yaml` 核对。
- **RAG 兜底路径必须设扫描上限**：`kb_chunk.embedding` 是 1024 维向量 JSON 文本，
  MySQL 回退无 `LIMIT` 会把整库向量连同文本读进堆（OOM）。`agent.rag.fallback-max-chunks`(2000)，
  触顶 `warn` 带真实总数——降级不许静默。
- **编辑工具坑**：同一条消息里对**同一个文件**发多个 Edit，第二个会被静默丢弃（报 success 但未落盘）。
  同一文件多处改动必须**分条消息串行**，改完用 `grep` 复核；不同文件可同批发送。
- **构建环境坑**：Bash 若报 `ls / find / dirname: command not found`，是本会话 PATH 缺 coreutils，
  在命令前加 `export PATH="/c/Users/802302/.workbuddy/binaries/PortableGit/versions/1.2.0/usr/bin:$PATH"`。

## 七、第三批修复：体验 / 安全 / 一致性（2026-09-14）

- **SSE 心跳独立调度器**：`ExecutorConfig#sseHeartbeatScheduler`（8 线程、线程名 `sse-heartbeat-`、守护、
  Spring 托管 shutdown）+ `ChatController` 用 `scheduleWithFixedDelay`（下一次从上次完成起算，不积压）。
  此前跑在 Reactor `Schedulers.parallel()` 上——与打字机 `delayElements` 及 Reactor 内部共用线程，
  慢客户端阻塞 `emitter.send()` 会占满 parallel、连带全站心跳停摆。现全项目 `Schedulers.` 只剩 `boundedElastic`。
- **附件落盘后缀白名单**：`/files/**` 免鉴权、浏览器按后缀推断 Content-Type ⇒ 上传 `.html/.svg` 会被同源渲染执行。
  `AttachmentStorageService.SAFE_EXTENSIONS`（覆盖前端 `ACCEPT` 全部类型）之外的统一落 `.bin`（octet-stream，只下载）；
  `AttachmentService.isImage` 同步排除 svg，**MIME 分支（`image/svg+xml`）也要挡**，只改扩展名分支会漏。
- **`clearMessages` 与摘要水位同事务归零**（`summary`/`core_facts`/`summarized_count`）：保持「摘要描述的历史
  == 库里真实历史」，维持 `getMessagesRange` 的「chat_message 不物理删除」索引契约。
- **归档原子写 / 批量解绑**：`AgentService.writeAtomically`（临时文件 + `ATOMIC_MOVE`，不支持时退化普通 move）；
  `deleteAgent` 改单条 `LambdaUpdateWrapper` 批量清 `agent_id` + `agent_bind_source`（原 N+1 次 `updateById`）。
- **配置注释与事实对齐**：`spring.sql.init` 默认整段注释 = 不自动建表（DDL 人工执行）；库 `agent` 需手动建
  （URL 无 `createDatabaseIfNotExist`）；`agent.vision.model` 默认 `qwen3.5-ocr`（`VisionProperties.DEFAULT_MODEL`）。

## 八、MCP 接入（2026-09-17，官方 starter 路线）

**选型**：走官方 `org.springframework.ai:spring-ai-starter-mcp-client`（版本由 `spring-ai-bom` 统一管，pom 里
不写版本号），不采用自研极简客户端。本地仓库原先没有 MCP 构件，**首次构建需联网拉取一次**
（`ONLINE=1 bash .workbuddy/memory/run_mvn.sh`），之后照旧离线 `-o` 构建。
传递依赖：`spring-ai-mcp` / `spring-ai-mcp-annotations` / `spring-ai-autoconfigure-mcp-client-{common,httpclient}`
/ `io.modelcontextprotocol.sdk:mcp:2.0.0`（+ `mcp-core`、`mcp-json-jackson3`）。

**接线**：`org.luo.tool.McpToolSource implements ToolCallbackSource`，注入 `ObjectProvider<ToolCallbackProvider>`
（starter 为每个 MCP server 装配一个 `Sync/AsyncMcpToolCallbackProvider`），逐个 try/catch 后合并，分组名固定 `MCP`。
- 该接口的返回值在 **`ToolRegistry` 构造期**被消费 ⇒ **必须吞异常**，否则一个 server 挂掉等于应用起不来。
- 不要另开注册路径：绕过 `ToolRegistry` 会出现「前端工具装配看不到 / `tools_json` 白名单选不中 /
  `LenientToolCallbackResolver` 兜底不认识」三处静默失效。

**配置**（`spring.ai.mcp.client.*`；下表默认值取自 2.0.0 的 `spring-configuration-metadata.json`）：

| 键 | 默认 | 本项目取值 |
|---|---|---|
| `enabled` | true | true |
| `name` / `version` | `spring-ai-mcp-client` / `1.0.0` | `my-agent` / `1.0.0` |
| `type` | `sync` | `sync`（与全同步工具链一致） |
| `request-timeout` | `20s` | `15s`（工具卡住不拖垮整轮） |
| `toolcallback.enabled` | true | true（**关掉则 `McpToolSource` 收不到任何工具**） |
| `initialized` | true | 未改 = 启动时同步 initialize |
| `stdio.connections` / `streamable-http.connections` / `sse.connections` | 空 | **刻意留空** |
| `stdio.servers-configuration` | — | 未用（Claude Desktop 风格 JSON 文件） |

`stdio.connections.<名>` 的字段为 `command` / `args`(List) / `env`(Map)。
**主 yaml 刻意不声明任何 server** ⇒ 默认即「不接入」，启动行为与未引入 MCP 时一致（零连接、零等待）；
真正的 server 配置只写 `application-local.yaml`（已 gitignore）。

**坑**：
- **Windows**：`npx` / `npm` / `node` 都是 `.cmd` 批处理，`ProcessBuilder` 不能直接执行 ⇒ 必须
  `command: cmd.exe` + `args: ["/c", "npx", ...]`，否则表现为「启动即退出、连不上」。
- 启动时会同步做一次 `tools/list`，server 不健康会拖慢启动；工具名以 server 返回为准
  （`DefaultMcpToolNamePrefixGenerator` 仅在跨连接重名时才加前缀），别凭猜 —— 看 `GET /api/agent/tools`
  的 `MCP` 分组。
- **安全**：stdio 配置等价于「在本机拉起任意进程」。server 配置只能来自配置文件，**绝不能让接口 / 前端在
  运行时新增**；MCP 返回的文本按不可信外部数据对待（同 RAG 文档，只进工具结果位、不进 system 指令位）；
  `tools_json` 为 NULL / 空 = 挂全量 ⇒ 接入 MCP 后「全量装配」的智能体会**自动获得 MCP 工具**，
  接入前先想清楚要不要改成显式白名单。

**未验证 / 验收**：应用未启动（按项目约定不由 AI 启动），故「无 server 时零副作用」目前只有静态证据
（两个自动配置类带 `@ConditionalOnProperty` / `@ConditionalOnMissingBean` 守卫）。验收方式：启动日志出现
`MCP 工具加载：N 个`，且 `  GET /api/agent/tools` 能列出 `MCP` 分组的工具；再建个测试智能体写
  `tools_json: ["<工具名>"]` 试调。

## 九、补充（合并自旧日志 9-01 / 9-12 / 9-21，原 9-xx.md 已删）

### 有界工具循环（2026-09-21）
Spring AI 2.0 工具调用**天生 do…while 循环**（返回 toolCall 就执行、拼回再调，直到停止），默认**无迭代上限**，模型反复调同一工具会死循环。"工具组合调用"要做的不是实现循环，而是**装刹车**：覆盖 `ToolCallingAdvisor.Builder<?>` bean（默认来自 `ChatClientAutoConfiguration`，`@ConditionalOnMissingBean`），自建 `BoundedToolCallingAdvisor`（ThreadLocal 计数 + 软刹车 `augmentSystemMessage`），配置 `agent.tool-call.*`（`max-iterations=10` / `repeat-threshold=3`）。

### 成本看板（独立 `llm_usage` 流水表，2026-09-21；2026-09-29 补 PLAN、2026-09-30 限 ADMIN）
7 处裸 `ChatModel.call()`（视觉 / 记忆合并 / 路由 / 参数抽取 / 改写 / 任务规划）生命周期无法都塞进当轮 RoundTrace，故**不硬塞**，新增旁路表 `llm_usage`，各处裸调用各记一条（purpose=ROUTE/CLARIFY/REWRITE/**PLAN**/VISION/MEMORY_MERGE）；`CostService` 聚合 `llm_usage`（全量裸调用）+ `agent_trace`（回答本身）。聚合 SQL 用 `DATE_FORMAT` GROUP BY（2026-09-29 已由 `@Select` 迁到 XML）。**接口与前端入口都限 ADMIN**：看板是**全站聚合**口径，聚合成一行「今天花了多少 token」后就分不出是谁的 ⇒ 按归属收敛技术上做不到，按人拆分又会暴露他人用量对比。

### 提示词外置约定（2026-09-01）
提示词集中外置到 `prompts.yaml`（`agent.prompt.*`），动态变量用 `{占位符}` 模板、**运行时替换**（不用 String.format，规避 % 与 MessageFormat 大括号转义冲突）；yaml 里写**字面 JSON 花括号必须转义 `\{ \}`**（ST4 把 `{...}` 当表达式，否则抛 'true' came as a complete surprise），中文尖括号 `<...>` 在 `{ }` 分隔符下安全。改提示词改 yaml 即可、无需重编译。

### ChatMemory 装配唯一性（2026-09-12，坑勿踩）
`DbChatMemory` 仅靠 `ChatMemoryConfig` 的 `@Bean @ConditionalOnMissingBean(ChatMemory.class)` 注册（类上无 stereotype 注解）；**别在任何别处再定义 `@Bean ChatMemory`**（两个用户配置类顺序不确定 → `NoUniqueBeanDefinitionException`）。Spring AI 自带 `MessageWindowChatMemory` 自动配置因条件不成立被跳过，但其 `InMemoryChatMemoryRepository` 成孤儿 bean（无害，但注入 `ChatMemoryRepository` 会意外拿到内存版）。

---

## 十、教务（edu）分层与接口约定

- 每表 `XxxController` + `XxxService extends IService<T>` / `XxxServiceImpl extends ServiceImpl<M,T>`，**不自拷贝 MP 基类**；**接口风格统一为命令式**（无 RESTful 分支）：`POST /{表}/page`（body = `XxxDTO`，筛选走 body）、`GET /{表}/list`（下拉，只回 `OptionVO{id,label}`）、`GET /{表}/{id}`、`POST /save`、`PUT /update`（id 在 body）、`DELETE /delete/{id}`。
- 分页口径唯一在 `BaseBO`（缺省 10、上限 100）：**有 join 的表** → `XxxDTO`/`XxxVO` + `selectXxxPage` 写 `mapper/edu/*.xml`；**无外键的 subject/semester/period** → `LambdaQueryWrapper`，不写 XML。
- 写规则收在各自 Impl，不散到 Controller：`requireUnique(e, selfId)`（新增传 null、编辑排除自身）、删除前 `count()` 其他表做引用校验、冲突与不存在分别抛 `CONFLICT`/`NOT_FOUND`。
- 跨表只读聚合走 `EduQueryService`（`/api/edu/score-detail` 等**顶层**路径，禁改嵌套）与 `EduMetaService.dashboard()`；**`/api/edu/dict` 已删**，选项一律各表自取。
- 4 个关联查询**都是分页接口**（口径只在 `BaseBO`，别在 controller 再声明一份 `MAX_SIZE`），统一回 `PageResult`；筛选不传即不过滤，SQL 全是可选 `<if>`。各配 `XxxDTO extends BaseBO`（`ScoreDetailDTO`/`ScoreStatsDTO`/`TimetableDTO`/`ScheduleDTO`），Controller 用 `@ModelAttribute` 收参、**方法体只转发**，Mapper 收 `@Param("dto")` 返 `List<XxxVO>`，**查询行一律类型化投影、不许回 `Map`**（`ScoreDetailVO`/`ScoreStatsVO`/`TimetableVO`/`ScheduleVO`，XML `resultType` 写全类名），分页对象由 `dto.toPage()` 出。
- **edu 层注入一律 `@Resource` 字段注入**（别用构造器注入）；**统一返回** `RestResult{code,message,data}` + `PageResult{records,total,page,size,pages}`，异常由 `GlobalExceptionHandler` 同形状返回，**改返回体必须同步前端解包**（edu.js 取 `.data`）。
- **mapper 的显式 SQL 一律落 XML**（路径 `resources/mapper/<pkg>/XxxMapper.xml`，namespace=接口全类名），禁止 `@Select`/`@Update` 等注解；纯 `BaseMapper` / `LambdaQueryWrapper` 的 mapper 不需要 XML 文件（2026-09-29 已把 `AgentTraceMapper`/`LlmUsageMapper` 的 `@Select` 迁到 XML）。Mapper 返 `List` 必须配 `PageResult.of(ipage, list)`（插件只回填 total/pages，**不回填 records**）；方法名别用 `selectPage`（撞 MP `BaseMapper.selectPage`）。
- **`resultType` 指向 record 时的两类硬约束（2026-09-30 由 `aggregateOverview` 先后踩出，两次都只在运行期炸）**：

  **A 类 · 列数与列序**：MyBatis 对「有参构造、无默认构造」的类型走**构造器自动映射**（`DefaultResultSetHandler#createResultObject` → `createByConstructorSignature`），本项目未开 `mybatis-plus.configuration.arg-name-based-constructor-auto-mapping`（`MybatisConfiguration` 只默认改 `mapUnderscoreToCamelCase`），故走**列序映射** `applyColumnOrderBasedConstructorAutomapping`：
  ① **构造器参数个数必须 == 结果集列数**，多一个直接 `ExecutorException`（3.5.19 源码 L787 显式 `parameterTypes.length > rsw.getClassNames().size()` 抛错）——**编译期无感、运行期 500**；
  ② **顺序必须与 SELECT 列序一致**（按位置映射，列名只用于挑 typeHandler；开按名映射时才会校验 `@Param`/`-parameters` 参数名，缺列同样抛）；
  ③ 因此**派生指标不入 SELECT**：做成 record 上的方法 + `@JsonProperty("x")` 显式命名（实测 Jackson 3 `tools.jackson` 会把它序列化进 JSON，非分量方法即使叫 `getXxx` 也认）。备选是 bean 投影（按 setter 映射、不看个数，见 `EvalBatchSummary`）。

  **B 类 · 聚合值不得为 NULL**：record 的分量是 primitive（`long`），而反射构造接不住 null ⇒
  `IllegalArgumentException: Error instantiating class ... with invalid types (long,long,...) or values (0,null,0,0,0)`。
  SQL 聚合又恰好在**零行**时返回 NULL（`COUNT(*)` 例外；`SUM/AVG/MAX/MIN` 全是 NULL），两头一夹就炸。写法：
  计数用 `COUNT(CASE WHEN ... THEN 1 END)`（COUNT 恒非 NULL，不需要 COALESCE）；求和/均值用 `COALESCE(SUM(...), 0)` / `COALESCE(AVG(...), 0)`。
  ⚠ 它只在「窗口内一行都没有」时触发 —— 有数据的窗口永远测不出来：2026-09-30 实测默认近 30 天正常，点「近 7 天」直接 500，
  且同一句里只有 `errors` 那一列漏包了 COALESCE（其余列当时包过，才没跟着一起炸）。
  接收方是包装类时不炸：`AnswerAgg`/`UsageAgg` 的 `totalTokens` 是 `Long`，`ScoreStatsVO` 的均/最高/最低分是 `BigDecimal`
  （成绩统计的 NULL 表达「这组没有成绩」，包成 0 反而变成「平均分 0 分」，是错的）。所以判据是「**接收方能不能接住 null**」，
  不是「写法好不好看」——一刀切禁止裸聚合会把有语义的 NULL 也一起抹平。

  自检脚本：`python .workbuddy/tools/check_record_projection_columns.py`（A 类：比对顶层列数与 record 分量数）、
  `python .workbuddy/tools/check_mapper_null_aggregates.py`（B 类：裸聚合 + primitive 接收判为高风险，自带 `--self-test`）。
  **改 mapper 或改投影类型后两个都跑一遍**。
