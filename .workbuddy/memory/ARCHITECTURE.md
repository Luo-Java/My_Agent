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
  否则 `<img>` 无法带 X-Api-Key 被 401）；落盘目录 `app.attachment.dir`。
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
- **鉴权**：`app.api-key`(env `APP_API_KEY`) → `ApiKeyInterceptor` 校验 `/api/**` 的 `X-Api-Key`(兼容 Bearer)，
  401 常量比较。

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

