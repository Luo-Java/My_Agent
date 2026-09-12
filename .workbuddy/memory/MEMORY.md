# 项目长期记忆（My_Agent）

## 约定与偏好
- **解析 LLM JSON 禁用 Jackson `ObjectMapper`**：一律用 Hutool `cn.hutool.json.JSONUtil`/`JSONObject`（pom 已引 `cn.hutool:hutool-all:5.8.38`）。跨轮参数抽取、路由结果皆同。
- **直接执行、小步快跑**：每次改动必须真实编译验证（Maven 在 `D:\software\Java\maven`，本地仓库同路径 `repository`，离线 `-o` 可用；启动器 `java -cp boot/plexus-classworlds-2.11.0.jar` 绕损坏 mvn 脚本，**launcher 模板见 `.workbuddy/memory/run_mvn.sh`**）。
- **离线打包必加 `-Dmaven.legacyLocalRepo=true`**（Maven 3.9 resolver 内部属性，2026-09-07 验证）：绕过 resolver 对 `_remote.repositories` 来源 strict 校验，否则 plugin transitive 依赖离线 fail。run_mvn.sh 已固化。
- **打包后不启动项目验证**（用户明确要求）：构建只到 `mvn package`，运行验证由用户在 IDE 自启；确需验证接口提示用户自启后 curl。
- **DDL 变更必须同步 `sql/schema.sql` 与 `alter.sql`**：schema.sql 是建库权威定义（spring.sql.init 已注释），alter.sql 只补存量库；两处列状态须一致，禁止只改 alter 不动 schema。

## 已知缺陷 / 坑
- **Spring AI 2.0.0：`stream()` + `@Tool` 必崩 `NoSuchElementException`**（`OpenAiChatModel$ChunkMerger` 对工具调用 `Optional` 直接 `.get()`）。带工具智能体改用 `call()` 走完工具循环后再 `Flux.fromIterable(...).delayElements(...)` 模拟流式。

## 架构要点
- 多 Agent 平台（Spring Boot 4 + Spring AI 2.0 + MyBatis-Plus）。`ChatService` 编排门面；参数补全/路由/记忆合并/提示词分别在 `ParamFillingService`/`AgentRouter`/`MemoryMergeService`/`PromptService`。
- Agent 参数：paramSchema(JSON 数组) 驱动对话追问补全，上限 `ParamFillingService.MAX_CLARIFY=3`；跨轮靠 DBChatMemory 累积，不改动 Conversation 结构。
- **会话级 RAG 纯开关 + 自动多库**：conversation 只存 `rag_enabled` TINYINT(1) DEFAULT 0。开启走 `KbSearchService.buildKbContext(ragEnabled, agent, query)`：`ragTargets`=通用全局库(kb.agent_id IS NULL, 只查不建)+路由/绑定 agent 专属库；多库一次合并检索(Chroma 单 collection 按 kb_id OR 一次查完、query 只向量化一次)，命中降序截断 `TOP_K_RAG=3` 注入 system；无命中回退 MySQL 全量余弦。PUT `/api/chat/conversation/{id}/rag` 更新开关。
- **知识库以「文件」为管理单元**：`kb_file`(kb_id+file_name 唯一，≤200 字符)。上传→`KbService.registerFile` 解析分块向量化入库并登记；同名重传=替换(全成功才删旧写新，事务回滚)；rechunk(`POST /{id}/files/{fid}/rechunk`)可不重传换策略/重叠重切。
- **分片 `ChunkingService`+`ChunkStrategy`**：4 策略(fixed/paragraph/recursive 默认/ markdown 标题感知)；overlap 默认 60(0 关闭, ≤200)；kb 级默认策略继承，上传/rechunk 显式参数可覆盖。
- **治理轮 2026-09-03**：`app.api-key`(env `APP_API_KEY`)→`ApiKeyInterceptor` 校验 `/api/**` 带 `X-Api-Key`(兼容 Bearer)，401 常量比较；`KbService` 拆出 `KbSearchService`(检索收敛点)；`ParamFillingService` 无状态 DB 重放设计(每轮 getHistory 全量重放，天然跨重启一致)。
- **多模态 `VisionService`(2026-09-07 起)**：Spring AI 原生多模态(`UserMessage.media`)，per-request `OpenAiChatOptions.model=qwen-vl-plus` 覆盖主对话模型；多图并发(`visionExecutor` + 按序 1:1 回收)保归属标注 + 失败隔离。
- **对话附件（2026-09-10 起，2026-09-12 定型）三通道分离，勿混**：① **纯提问** `message`（可空）→ 走记忆 Advisor + 路由/RAG 查询，唯一落 `chat_message.content`；② **解析文本** `material`（图片 caption / 文档文本，单附件截 30000 字）→ `ChatController.attachmentMaterial` → `ChatComposer.withMaterial` 注入**当轮 system**，仅当轮可见；③ **展示元数据** `attachmentsJson`（type/filename/storedName/size，**不含正文**）→ 落 `chat_message.attachments_json` 独立列，仅 `/api/chat/history` 读取渲染缩略图/下载。**红线**：`DbChatMemory.get()` 只读 `content` → 记忆窗口永远干净、附件不重复耗 token。入口：`POST /api/chat/attachment/process`（`AttachmentService` 解析 + `AttachmentStorageService` 落盘）；静态访问 `/files/**`（`AttachmentWebConfig`，**不带 `/api` 前缀**否则 `<img>` 无法带 X-Api-Key 被 401）；落盘目录 `app.attachment.dir`（默认 `./data/attachments`）。`ChatService/stream/chat` 加 `attachmentsJson` 参数，runRound 后经 `ConversationService.attachToLatestUserMessage(cid, 水位, json)` 写「本轮新增」用户消息（水位守卫防误挂）。
- **包结构（2026-09-10 按角色重排，全在 `org.luo` 基包）**：`service`=业务服务(Agent/Conversation/Kb/Chat/KbSearch/Chunking)；`infrastructure`=外部集成(chroma: ChromaConnection+ChromaClient+ChromaVectorStoreService / vision: VisionService / document: DocumentParserService / attachment: AttachmentService+AttachmentStorageService)；`agent`=智能体核心(AgentRouter/ParamFilling/MemoryMerge/Prompt/Planner + handler: Round/PlannerRound/AgentRound/RoundResult)；`chat`=ChatComposer。Spring 默认扫描 `org.luo` + `@MapperScan("org.luo.mapper")`，搬子包不影响 bean 注册。**搬包脚本务必保留 `org/luo/` 目录前缀**，否则目录与 package 声明错位（2026-09-10 踩过）。

## Chroma 接入（2026-09-10 多次修复，勿回退）
- 官方 `spring-ai-chroma-store` 2.0.0 模块(非 starter)；本机 `http://127.0.0.1:8000`，yaml `chroma.base-url/collection-name/tenant/database`，默认 collection `kb_chunks`。
- **命名空间必须 `default_tenant/default_database`**：Spring AI 2.0 默认 `SpringAiTenant` 在 Chroma 0.5.x 不存在，且其 `afterPropertiesSet` 先 `getCollection()`，0.5.x 对不存在集合返回 **400 InvalidCollection 而非 404** → Spring AI 只处理 404 → 抛「Collection xxx does not exist.」→ `initializeSchema(true)` 没机会建库（新装必复现）。故 build 前 `ensureCollection()` 幂等预建。
- **集合必须 cosine 空间**：0.5.x 不从 metadata `hnsw:space` 读空间(会建成 l2 让命中被 MIN_SCORE 滤掉)，须原生 `POST /api/v1/collections` 传 `configuration.hnsw_configuration.space=cosine`(+`_type`，1.x 用 `hnsw`)；`detectSpace()` 发现 l2 且**空集合**自动删建，非空保留。
- **冷却重试**：`nextRetryAt` + `chroma.retry-interval-seconds`(默认 60)，「先起应用后起 Chroma」自动自愈，不永久降级。
- **upsert 批大小受 Embedding 模型限制**：`ChromaVectorStore.add()` 整批 embed，DashScope `text-embedding-v3` 单次 ≤20 → `chroma.upsert-batch-size` 默认 **10**。
- **绝不静默降级**：`store()` 为 null 时 `logSkipped(op)` 限频 warn；`status()` 暴露 connected/space/documentCount/lastError；`GET /api/kb/chroma/status`；`POST /api/kb/chroma/sync` 幂等回填。写入侧 `registerFile` 返回 `FileIngestResult(file,chromaSynced)`，upload 响应带 `chromaSynced`，前端详情顶部状态条+「同步本库」按钮。
- **相似度语义坑（已修）**：Chroma 0.5 cosine 空间 `distance = 2·(1−cos)`，`score = 1−distance = 2·cos−1`，**非真实余弦**；须还原 `realCos = (score+1)/2` 再与 `MIN_SCORE=0.25` 同口径，否则相关结果全被滤掉。
- **绕过 Spring AI 阈值**：`similaritySearch` 强制 `threshold∈[0,1]`(传 -1 抛异常)，且 0.0 只留 `cos≥0.5` 丢 `[0.25,0.5)`；故检索直接 `api.queryCollection` 自算 `embedding.embed(query)` + `where.$or` 多库合并，自过滤。
- **代码结构（2026-09-10 三层拆分，勿回退）**：① `ChromaClient`=Chroma 全量操作(add/delete/query/count)适配，持有 api/embedding/ChromaVectorStore/collectionId，封装 `add(List<ChromaDoc>)`/`delete(ids)`/`search`/`count`+拆包/余弦还原/多库 OR/分批写+`ChromaDoc`/`ChromaHit` 记录；② `ChromaConnection`(@Service)=连接生命周期：lazy+冷却重试(`nextRetryAt`/`markFailed`/`logSkipped`)、`ensureCollection` 全套原生 HTTP 建库/空间校正(`detectSpace`/`createCollectionNative`/`postCollection`/`hnswConfig`/`deleteCollectionNative`/`countQuietly`/`collectionsUrl`/`chop`)、`connected(op)` 取客户端或降级、`status()`；③ `ChromaVectorStoreService`(@Service)=纯业务门面，注入 `ChromaConnection`，只做 `KnowledgeChunk→ChromaDoc` 转换 + 经 `connection.connected(op)` 取客户端委托 `ChromaClient` + 降级 warn，对外 upsert/delete/search/status 契约不变。`KbSearchService` 用 `ChromaClient.ChromaHit`。调用方一行 `search(kbIds,query,topK,minScore)` 等同 LangChain `similarity_search`。
