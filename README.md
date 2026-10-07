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
- **先看计划（可选，规划模式的子开关）**：再开「先看计划」后，规划**只产出计划就暂停**，页面给出可交互的计划卡片（步骤清单 + 「执行计划」按钮），确认无误才开跑。执行复用的就是上面那条**断点续跑**通路——计划已作为 RUNNING 任务落库、步骤全 PENDING，所以后端没有第二套执行入口；计划明细同时也作为正文推过一遍，刷新后卡片消失但内容仍可回看。默认关闭（规划完直接执行，与改造前一致）。
- **规划模板（把「怎么排」沉淀成资产）**：跑顺的一次规划可在计划卡片上点「存为模板」，其**步骤骨架**（智能体 + 指令 + 依赖快照）即成为可复用资产；下次同类目标在输入区「模板」里选一个、填本次目标，就按骨架生成计划——**零模型调用**，省掉一次规划往返，生成后仍可用卡片上的「编辑」逐条微调再执行。模板**只存「怎么排」、不存「做什么」**（目标每次都不同，故套用时必填），也不含各步产出与任何运行态：存的是**快照 JSON** 而非引用 `task_step`（那张表会随局部重规划删改、重排行，引用式模板会被连带破坏）。按用户隔离，仅本人可见；套用同样只做「落库」，执行仍走断点续跑。
- **步骤审批点（让「跑一半停下来问你」成为能力）**：计划卡片上可给任意步骤勾选「需审批」，执行到该步**先暂停并等待批准**，页面弹出审批卡片（批准并继续 / 终止计划）。**关键是这套关卡复用现成通路**：批准后走的就是断点续跑（`POST /api/chat/task/resume`），没有第二套执行逻辑；终止走 `POST /api/chat/task/cancel`，把任务收成 `CANCELLED`。审批是「允不允许跑」、状态是「跑到哪了」，两者正交（`approval_required` / `approved` 两列独立于 `status`）。
- **成本配额（按用户 × 自然日）**：可选的用量护栏 —— 打开后每轮对话开始前汇总「该用户今天花了多少 token」（`agent_trace` 回答侧 + `llm_usage` 裸调用侧，口径与成本看板一致），超限**明确返回 429 并说明已用/上限/何时重置**，接近上限（默认 90%）时在回复前推一条预警。ADMIN 默认豁免。**超限不许静默降级**（不换小模型、不截断历史、不假装成功）；统计出错则 fail-open 放行并落 WARN（配额是治理手段、不是正确性保障，宁可放过也不因统计故障挡住正常使用）。
- **并行评审（多智能体对同一问题并行作答 + 裁决）**：会话级 ⚖ 开关开启后，本轮先由 LLM 从智能体库里挑若干**候选**（会话已绑定智能体时它固定占一席，其余按问题类型选），各候选**互相不可见地**独立作答，再由裁决者综合成**一份**最终回答——答案是「综合」而非「挑一份」，从而减少单次作答的偏斜。候选与最终答案一起推给前端（候选走 `review` 事件、只作展示不落记忆），让「答案是怎么来的」可追溯。
  - **候选互不可见**：互相看得见就退化成串行接力（后答者会跟着前面的思路走），拿不到「多解」。**共用同一份检索素材**（RAG 命中 + 跨会话回忆只取一次）既控成本（否则 N 份检索），也保证「谁答得好」里不混进「谁拿到的资料多」。
  - **与规划互斥**：两者都是「编排形态」（规划决定**怎么拆**、评审决定**谁来答**），同时开启会让「本轮走哪条链路」取决于读取顺序。故开启任一方会自动关掉另一方（前端开关同步联动）。
  - **失败全部显式**：候选不足 2 个 → 播报后回落普通单智能体；只有 1 个成功 → 播报「仅 1 个候选成功作答，直接采用它的回答（未做综合）」；全部失败 → 明确回「并行评审失败」；裁决失败 → 播报后采用第 1 份。**没有一种情况会静默假装成功**。
  - 每次候选与裁决都是真实模型往返，按 `REVIEW` 用途计入成本看板。
- **跨会话搜索（回忆「你在别的会话里说过什么」）**：会话级 🔎 开关开启后，每轮先用 LLM 从本轮问题里抽出检索关键词，在**本人其他会话**的历史消息里做关键词召回（SQL 层强制按 `user_id` 过滤并排除当前会话），命中的片段连同会话标题、角色、时间一起注入上下文；前端同时展示「🔎 回忆到的历史」列表。**为什么是关键词而不是向量**：会话消息逐轮写入，走向量检索意味着每条消息落库都要多一次 embedding 调用并新增一套副本维护链路，而现有向量设施是围绕知识库块建的、共用同一空间会让「知识库命中」与「历史回忆」互相干扰。代价是**换个说法就召回不到**（字面匹配），这是明确接受的边界。降级不静默：未提取到关键词 / 未回忆起 / 回忆起 N 条 / 召回失败，四种情况都有明确播报。
- **内容安全护栏（可选的输入/输出侧拦截）**：`agent.safety.enabled=true` 后，输入侧在配额闸门之前按规则检查（超长或命中 → **明确 422 拒绝、零模型调用**），输出侧在推送与展示前检查（命中 → 替换为提示文案）。
  - **它不是「在提示词里写一句安全要求」**：那种做法把「守不守法」交给模型自己判断，拦不住明确不该出现的内容，也拿不到任何可观测的拦截记录。护栏是代码里的确定性判断。
  - **边界要说清**：规则是**正则字面匹配、不是语义审核** —— 换个说法、加个谐音就能绕过，且规则只进配置（改规则不改代码）。规则命中只记 WARN、**不落原文**（拦截记录本身不该成为敏感内容的新副本）。
  - **替换只作用于推送与展示**：普通对话的助手消息由记忆 Advisor 在模型返回时就已落库，输出侧护栏跑在它之后，因此库里仍是模型原始输出。要让落库也替换得把护栏下沉进 Advisor 改写 response（会牵动 token / 工具元数据重建），本版本刻意不做。
- **双层记忆**：短期窗口（`chat_message` 原文，受 token 预算与条数下限约束）+ 长期滚动摘要（`conversation.summary` / `core_facts`）；超窗历史异步压缩合并，**先推回复、后处理记忆**。
- **长期记忆可视化与编辑**：双层记忆此前是纯黑盒 —— 压缩由后端异步完成，用户既看不到「它记住了什么」，也无法纠正记错的内容。顶栏 🧠 记忆把摘要与核心事实摊开可改，并给出「已压缩 N / M 条」的覆盖度（直接回答「它为什么还记着那么早的事」）。**水位 `summarized_count` 只读不可改**：它是「压缩到第几条」的执行游标，手改会让下次合并从错误位置继续；要重置得走「重置全部记忆」（三列一起归零、历史消息保留，下次超窗从头重新摘要）。
- **流式输出**：SSE 推送 `token`（正文，进记忆）、`progress`（执行过程，不进记忆）、`citations`（引用来源）、`plan`（待确认计划，见「先看计划」）、`approval`（触到审批关卡，见「步骤审批点」）、`review`（并行评审候选，见上）、`recall`（跨会话回忆命中，见上）、`ping`（心跳）、`error` 等事件，前端逐字渲染。**前置链也有实时反馈**：发送后到首个 token 之间，路由判定 / 参数抽取 / 检索问句改写各环节都会先推一条 `progress`，不再是一片空白干等。`review` / `recall` 都排在正文之前：它们是「结论怎么来的 / 用了什么素材」，先给依据再给结论。
- **对话分支（编辑重发 / 重新生成不删历史）**：与 DeepSeek 一致的形态——对某一轮「重新生成」、或对某条用户消息「编辑重发」，**不删旧版本**，而是给这一轮**再开一个版本**；那条**提问**气泡上出现「n / m ‹ ›」版本切换器，点箭头原地翻看同一轮提问的多个版本（提问与其后的助手回复作为一个「分支组」一起换）。这与早前「先截断再重发」只差一个取舍：截断是「旧答案当场消失、原思路回不去」，分支是「旧版本留在库里可翻回」。**开新版本发生在消息确实落库之后**（流式之前只分组、算版本号，绝不提前失效旧版本）——否则附件失败 / 配额拦截 / 发送中断都会让那一轮凭空消失。
- **分支的落库口径**：`chat_message` 上用三列刻画 —— `turn_group_id`（同轮多版本共用，UUID）、`turn_version`（组内序号，从 1 连续递增，故前端可直接令 `versionCount = version`）、`turn_active`（当前生效版本，同组至多一个为 1）。**`turn_group_id IS NULL` 即「从未分叉」**，存量数据零回填、无需迁移。**读取侧四处共用同一过滤**（`turn_group_id IS NULL OR turn_active = 1`）：喂 prompt 的历史、记忆窗口、消息总数、摘要切片——任一处漏掉都会让「摘要水位按物理行数推进」与「注入侧按生效版本取」错位，裂出既不摘要也不注入的记忆空洞。新版本行还会**把 `created_at` 锚回该组首条的时间**，否则按 `created_at, id` 排序时它会掉到后面几轮之后。
- **切版本 / 开新版本会重置长期记忆水位**：注入模型的历史整段换了，旧摘要即失效（与「截断重发」同一语义）；切换只改 `turn_active`（先全灭同组、再点亮目标），不动作答内容。
- **会话导出**：顶栏 ⬇ 导出把当前会话导出为 Markdown（消息全文 + 附件文件名 + RAG 引用来源与相关度）。后端只回文本、由前端拼 Blob 下载 —— 下载必须带 `Authorization`，而浏览器对裸链接的导航请求带不上这个头，走文件通道只会 401。附件刻意只留文件名不留 URL：导出文件要自包含，指向本机 `/files/**` 的链接换台机器就是死链。
- **链路追踪**：每轮对话的路由来源、规划步骤、改写后检索问句、RAG 命中、工具调用（参数/结果/token/耗时）异步落库 `agent_trace`；页面 🔍 追踪弹窗按**每页 10 条**分页查看本会话最近 50 轮，**只显示自己名下会话的记录**（管理员可切到「全部会话」看全站）。
- **可观测面板（仅 ADMIN）**：跨会话聚合 `agent_trace` 回答「整体运行得怎么样」——成功率 / 平均耗时 / 平均 token、按天·按形态·按处理方来源·按智能体拆解、以及最慢的 N 轮（定位瓶颈）。独立页 `/observability.html`，与成本看板（讲「花了多少钱」）互补：本面板讲「跑得多快、成不成」。接口与入口都限 ADMIN（跨会话全站聚合口径）。
- **成本看板（仅 ADMIN）**：全量成本口径——除「回答本身」（`agent_trace`）外，路由判定/参数抽取/查询改写/任务规划/视觉识别/记忆合并/跨会话召回（`RECALL`）/并行评审（`REVIEW`）这些裸 `ChatModel` 调用也各自记入 `llm_usage`（按用途 `purpose` 拆解）；页面 💰 成本弹窗按天趋势 + 按用途聚合展示（近 7/30/90 天）。接口与入口都限 ADMIN（全站聚合口径，按人拆分无意义）。

### 知识库与多模态

- **知识库 RAG（会话级纯开关 + 自动多库）**：会话开启「📚 RAG」后，每轮自动检索「通用知识库（`agent_id` 为空）＋路由/绑定智能体的专属库」，多库一次合并检索并注入编号上下文；关闭即完全不检索。三段式检索：**粗排召回 → 精排（DashScope text-rerank）→ 编号注入**，精排不可用时降级「向量分截断」。检索前会用最近若干轮历史做**多轮查询改写**（指代消解），首轮/未开 RAG 自动跳过。
- **以「文件」为管理单元**：上传 → 解析 → 分片 → 向量化入库并登记（`kb_file`）；支持 4 种分片策略（fixed / paragraph / recursive / markdown）与重叠字数、**重新分片**（不重传换策略）、**同名重传=替换**。
- **向量存储双写**：MySQL 留档（源，向量以 JSON 文本存 `kb_chunk`）+ Chroma 加速副本。检索优先 Chroma（余弦 TopK），不可用/无命中自动降级 MySQL 余弦（有界扫描，防 OOM）；`POST /api/kb/chroma/sync` 幂等回填副本。Chroma 写入统一延后到**事务提交之后**，避免 MySQL 回滚后副本失配。
- **多模态图片理解**：输入框 🖼 支持多选图片（≤5 张 / 单张 ≤10MB），由视觉模型（默认 `qwen-image-2.0-pro-2026-06-22`，可配）识别成中文 caption 拼入本轮上下文；走 Spring AI 原生多模态（裸 `ChatModel` + `UserMessage.media`，per-request 覆盖模型、多图并发识别）。**原始二进制不进会话存储**——caption 仅当轮可见。
- **引用回链（从角标一路点回原文）**：回答正文里的 `[n]` 是**可点击角标**，点一下会展开该轮的「引用来源」并高亮第 n 条；来源条目上的「原文」再进一步，按 `chunkId` 拉出**被引用的那段知识块正文**。此前只有「库名 · 文件名 · 相关度」，用户无法判断一句话是文档里写的还是模型编的。取块失败（块被删或重新分片，这在几个月前的老引用上很正常）**明确 404 并说明原因**，不返回空白块——空白块会被读成「文档里本来就是空的」。角标处理只改正文文本段，`<pre>`/`<code>`/`<a>` 内的 `[n]`（代码、数组下标、链接文字）与图表 JSON 一律不动。
- **文档解析**：附件与知识库支持 txt / md / markdown / csv / json / xml / yml / properties / log / sql 文本，以及 pdf（PDFBox）、docx / xlsx（POI）。

### 工具调用

- **全局能力池**：天气查询、日期解析、SQL 安全查询、表结构查看、样例数据、SQL 预检、ECharts 图表、文本直方图。
- **智能体转交（`call_agent`）**：把一个子任务转交给另一个更合适的智能体执行 —— 让「多智能体协作」不再只有规划模式一条路，普通对话里也能用。工具描述里动态列出当前可转交的智能体清单（每轮现查，新建/删除智能体立刻生效）；转交是黑盒，主智能体只拿到子智能体的最终产出。
  - **白名单专属**：`tools_json` 为 `NULL`（=挂全量）时**不会**带上它 —— 转交是策略性能力，让翻译/闲聊类智能体凭空获得「可以把活推给别人」的选项容易被误用，且会一次性改变所有既有智能体的行为。要用就在`tools_json` 里显式写 `["call_agent", ...]`。
  - **只做一层**：子智能体执行时挂的是它自己的静态工具，不含 `call_agent`，因此「A 转给 B、B 再转给 C」不会发生，天然无递归与调用环。代价是子智能体不能继续向下转交。
  - 每次转交是一次真实模型往返，按 `SUBAGENT` 用途计入成本看板。
- **有界工具循环**：Spring AI 默认的「模型调工具」循环是无上限的，模型反复调同一个工具会死循环拖垮 token。已用自定义 Advisor 装上三道刹车——**轮数上限**（默认 10 轮，覆盖「查表→查数→画图」合理长链）+ **连续重复检测**（默认连续 3 次同名同参即停）+ **单轮 token 预算**（`round-budget-tokens`，默认 0 = 不启用；读本轮累计用量，规划模式下跨步骤累加）。前两道拦的是「死循环」，第三道拦的是「每一步都合规、合起来烧穿一轮」——多步规划 + 长工具链可以完全绕过前两道而不违反其中任何一条；它与成本配额（用户 × 自然日）是两个维度：配额拦「一天烧太多」，这里拦「一轮烧太多」。三道都走**软刹车**（追加「基于已有信息作答」指令，不抛错中断），因为硬中断会把这一轮已花掉的调用结果全丢掉、还得从头再问（且下一轮仍撞同一个上限），代价是**它不是硬上限**（刹车后仍会多一次模型调用，规划模式下每个被刹住的步骤各一次）。触发写 WARN 日志（含 `traceId`，可在服务端日志检索整轮链路）并经进度通道播报，不静默。配置见 `application.yaml` 的 `agent.tool-call.*`。
- **按智能体装配**：`agent.tools_json` 控制白名单 —— `NULL`/空 = 挂全量、`[]` = 不挂、`["名"]` = 白名单（按 `@Tool` 名匹配，未指定 name 时即方法名）。未知名忽略、非法 JSON 回退全量。唯一例外是 `call_agent`：它是**动态工具**（实例依赖「调用方是谁」，候选清单随库变化，无法在启动期注册），只认白名单显式声明，`NULL` 全量下不挂。
- **安全护栏**：SQL 工具仅允许只读 `SELECT`/`WITH`，白名单表名（`SqlSafety.ALLOWED_TABLES` 的 10 张业务表：student / class / teacher / subject / course / score / semester / exam / period / course_arrangement）、拒绝多语句与可执行注释、结果行数上限。业务表与 agent 系统表**同库**，故必须用白名单**显式放行**（黑名单挡不住新表），`conversation`/`chat_message`/`agent` 等系统表一律不可读。
- **MCP 远端工具**：官方 `spring-ai-starter-mcp-client` 接入的 MCP server 工具经 `McpToolSource` 收进**同一个能力池**（前端分组显示为 `MCP`），与本地工具一样按 `tools_json` 装配。默认**不声明任何 server**，即「不接入」——启动行为与未引入 MCP 时一致。
  接一个 server：在 `application-local.yaml` 写 `spring.ai.mcp.client.stdio.connections.<名>`，`command` 用可执行文件绝对路径、`args` 首项为 server 入口、其后为允许读写的沙箱根目录（**不要用 `npx`**，Windows 上是批处理包装、且依赖 PATH 与网络）。工具名以 server 返回为准，看 `GET /api/agent/tools` 的 `MCP` 分组。

### 提示词回归评测

- **改提示词前后各跑一批**：`prompts.yaml` 里 11 个模板（路由判定、参数抽取、查询改写、规划、汇总…）都是 LLM 行为契约，改一个词可能悄悄修好 A、弄坏 B。评测把这层「跑批 + 断言」补齐 —— 用例集声明「什么输入应得什么结果」，跑批走真实链路，逐条给通过 / 失败 / 配置错误。
- **三态不混算**：**失败** = 提示词质量问题；**配置错误** = 用例自己写错（例如断言引用了不存在的智能体编码）—— 单列一档，否则用例维护失误会被误读成「模型变笨了」。
- **批次对比看 broken**：每批落库，`/api/eval/compare` 直接给出 `fixed`（上批挂→本批过）与 `broken`（**上批过→本批挂**）。改完提示词先看 `broken` 有没有变长，比看总通过率更能定位回归。
- **零侵入**：不碰对话链路 —— 评测复用现成的路由 / 规划能力，只是换个入口调用并断言结果。用例集是 `classpath:eval-cases.yaml`，加用例不改代码。
- **仅 ADMIN**：`/api/eval/**` 整类带 `@RequireRole(ADMIN)` —— 「跑一批」发起的是**真实模型调用**（13 条用例 = 13 次 LLM 请求），消耗计入 `llm_usage` 成本流水，与成本看板同一性质。只读的 `/cases`、`/batches`、`/compare` 本可单独放宽，但它们只服务于「跑批」这一件事，没有独立使用场景，故整类收敛；前端顶栏入口按同一角色显隐。

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

口令、API Key 与登录签名密钥 **不进仓库**，由根目录 `application-local.yaml`（已 gitignore）或环境变量提供：

```yaml
# application-local.yaml（本机私有，勿提交）
spring:
  datasource:
    password: 你的数据库口令
  ai:
    openai:
      api-key: 你的模型服务 API Key
app:
  jwt:
    secret: 你的登录签名密钥   # openssl rand -hex 32；不配则每次启动用随机密钥（重启即全员重新登录）
```

> **`app.jwt.secret` 只能写在本地文件或环境变量里。** 主配置 `application.yaml` **不含这个键**——也不留空占位符（空串会覆盖本地值，等于把密钥改没）。

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
  planner: {upstream-max-chars: 4000, upstream-total-chars: 12000, isolate-middle-steps: true}   # 前驱产出注入下一步的配额（防无界累积）+ 中间步骤隔离长期记忆
  eval:   {cases-file: classpath:eval-cases.yaml, timeout-seconds: 60}   # 提示词回归评测：用例集位置 + 单条超时
  quota:  {enabled: false, daily-tokens: 2000000, warn-ratio: 0.9, exempt-admins: true}
           # 成本配额：按「用户 × 自然日」给 token 消耗装上界。关着时闸门完全短路（不查库、不判断），行为与不配这节一致。
           # 超限明确报错（429），不静默降级。ADMIN 默认豁免 —— 否则管理员把额度用满后，连界面都进不去调回配额。
  safety: {enabled: false, max-input-chars: 0, blocked-message: 该内容不符合使用规范，已被安全策略拦截,
           input-patterns: [], output-patterns: []}
           # 内容安全护栏：默认关闭。input-patterns 超长/命中即拒绝（422，零模型调用）；
           # output-patterns 命中则把推送与展示的文本换成 blocked-message（库里仍是模型原始输出）。
           # 规则是正则字面匹配、不是语义审核；max-input-chars=0 表示不限制长度。
  review: {candidates: 3, max-candidates: 5, timeout-seconds: 90}
           # 并行评审：每轮候选智能体个数（下限 2）、配置上限、单候选超时（超时按弃权处理）。
           # 候选越多越贵（N 次模型调用 + 1 次裁决），开启评审前先掂量。
  cross-session: {enabled: true, recall-limit: 50, top-k: 3, max-keywords: 5, snippet-chars: 300}
           # 跨会话搜索：全局开关 + SQL 候选上限 + 注入条数 + 提词关键词数上限 + 片段截断字数。
           # enabled=false 时该功能整体短路（会话开关拨了也不生效，零模型调用、零 SQL）。
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

### 3.1 部署安全

- **接口访问控制**：登录鉴权是全项目**唯一**的接口闸门 —— `/api/**` 除 `POST /api/auth/login` 外都要求 `Authorization: Bearer <token>`，否则 401。把服务暴露到局域网/公网时，靠的就是它（外加反向代理/网关）。**旧的服务级密钥（`app.api-key` / `APP_API_KEY` → `ApiKeyInterceptor`）已移除**：它与登录流程冲突——启用后连 `POST /api/auth/login` 都会被拒（前端登录请求只带 `Authorization`，不带 `X-Api-Key`），用户根本进不来；而 `/api/**` 已由 JWT 全覆盖，机器调用同样得先登录，那道闸门不再提供额外保护。若仍需要「不登录即可调用的机器接口」，应单独开一条路径并显式放行，不要复用全局闸门。
- **登录鉴权（默认开启）**：`/api/**` 除 `POST /api/auth/login` 外都要求 `Authorization: Bearer <token>`，否则 401（带 `reason`，见下），前端会**就地弹出登录框**（`js/auth.js`，不跳页）并展示服务端给出的具体原因。置 `app.jwt.enabled=false` 只关掉**服务端**校验，**前端的守卫仍会拦**：`GET /api/auth/me` 在开关关闭后同样抛 401，前端拿不到「开关已关」这个状态，会一直弹登录框 —— 要真正回到无登录态，得把页面里的 `Auth.requireLogin()` 调用一并去掉。
  - **拒绝原因可区分**：401 响应体除 `message` 外带机器可读的 `reason`（`NO_TOKEN` / `MALFORMED` / `BAD_SIGNATURE` / `MISSING_EXPIRY` / `EXPIRED` / `USER_UNAVAILABLE`，403 为 `ROLE_DENIED`），拦截器同时落 WARN 日志。前端把服务端 `message` 原样展示，因此「没带凭证」与「凭证不被认（密钥换过）」不再被笼统的「登录已失效」掩盖。
  - **响应头形状与其余接口一致**：拦截器手写的 401/403 不再调 `setCharacterEncoding`（那会让 Tomcat 把响应头写成 `application/json;charset=UTF-8`，而 Jackson 输出的都是 `application/json`）—— 同一 API 两种形状容易被误读成「带 charset 的请求才 401」。JSON 按规范即 UTF-8，直接写字节。
  - **启动日志能回答「这次重启换没换密钥」**：`登录鉴权已启用：token 有效期 N 分钟…，签名密钥 N 字节 / 指纹 xxxxxxxx`。指纹是密钥的 SHA-256 前 8 位，只用于跨重启比对（密钥本身不进日志）。看到 `BAD_SIGNATURE` 先比这个：指纹**变了** ⇒ 那份 token 是更早密钥签发的，重新登录一次即可；指纹**没变** ⇒ 不是密钥问题，看下一条。
  - **同一份合法 token 被随机判为 `BAD_SIGNATURE`（并发验签不可靠，已修复）**：Hutool `HMacJWTSigner` 内部持有**单个** `javax.crypto.Mac`（非线程安全），且 `verify()` 的实现是「用同一个 signer 重新签一遍再比对字符串」。把 `JWTSigner` 当单例字段复用时，Tomcat 并发请求会互相污染 HMAC 计算 —— 表现正是「登录后随便点几下就被弹回登录框」，且**同一 token 有时 200 有时 401**，与密钥、有效期都无关。修复在 `JwtTokenService`：只存 `byte[] secret`，签发/校验各自现建一个 signer（`newSigner()`）。复现与回归脚本：`.workbuddy/tools/probe_token_concurrency.py`（并发打同一 token，统计 200/401 分布，修复后应全 200）。
  - **401 与 Content-Type 无关**（已实测：同一 token 下 `application/json` 与 `application/json;charset=UTF-8` 状态码完全相同）。用 `text/plain` 提交 JSON 会得到 **415**「请求体格式不受支持」，而不是 500。
  - **「连不上服务端」不等于「登录失效」**：页面级校验/链接守卫只在服务端明确 401 时才清 token；请求本身失败（服务端重启中）保留登录态，提示「无法连接服务端，请稍后重试」——把网络抖动当成凭证失效会导致一次无谓的重新登录。
  - **前端按 `reason` 决定要不要清登录态，且清之前先复核**：`js/auth.js` 维护 `TOKEN_FAILURE_REASONS` 白名单（`NO_TOKEN`/`MALFORMED`/`BAD_SIGNATURE`/`MISSING_EXPIRY`/`EXPIRED`/`NO_SUBJECT`/`USER_UNAVAILABLE`），**只有命中才视为「凭证真失效」**；`API_KEY_REQUIRED`（服务级密钥缺失）与非 401 一律**保留** token，只弹提示。命中白名单时也不立即清，先并发安全地 `GET /api/auth/me` 复核（`confirmSession()`，多请求共用一个 in-flight 调用），复核通过则连弹框都不弹 —— 单次 401 不再误踢用户。浏览器控制台可跑 `Auth.diagnose()` 打印本地 token 声明/剩余有效期（不含 token 本身）与服务端 reason。
  - **签名密钥**：取值必须是 ≥32 字节的固定串（生成：`openssl rand -hex 32`），来源是 `application-local.yaml` 的 `app.jwt.secret` 或环境变量 `JWT_SECRET`。**未配置或过短时每次启动都会生成新随机密钥**，表现是「一重启所有人都要重新登录」（启动会打 WARN）。注意环境变量 `JWT_SECRET` **显式设成空串**会覆盖本地文件里的值，等同于「未配置」。
  - **密钥不再有源码内置默认值**（历史遗留已修）：早期 `application.yaml` 里带一段固定的 64 位 hex 默认值，随源码分发意味着**拿到代码就能伪造任意账号（含 ADMIN）的 token**。已改为不在主配置写 `secret` 键，密钥只存在于 gitignore 的本地文件/环境变量；轮换密钥会让既有的所有 token 立即作废。
  - **有效期**：`app.jwt.expire-minutes` 必须是正数。该字段是 `int`，配置没绑上时默认 0，而 0 分钟意味着 token **一签发就过期** —— 表现是「登录成功后进入页面又让登录」，且不抛任何异常。启动时会打 WARN 并回落到默认 720 分钟。
  - **初始账号**：`sys_user` 表为空时启动自动创建 `admin`（口令取 `app.jwt.bootstrap-password`，默认 `admin123`），请在 `/user.html` 立即修改；表非空时该引导不再触发。
  - **权限模型**：需 `ADMIN` 角色的接口用 `@RequireRole(SysRoleCode.ADMIN)` 声明（注解紧贴接口/控制器，不存在一份与代码脱节的路径清单），不通过返回 403 `ROLE_DENIED`。当前共五处：`/api/user/**`、`/api/role/**`（用户与角色管理）、`/api/cost/**`（成本看板）、`/api/eval/**`（提示词评测）与 `/api/observability/**`（可观测面板）。后三者同属**运营视角**——成本看板与可观测面板都是跨会话全站聚合口径（按人拆分无意义），评测「跑一批」会发起真实模型调用、消耗计入成本流水，都是「会花钱/看全局的运维动作」，不该交给任意登录用户。其余接口只要求已登录。
  - **教务（`/api/edu/**`）刻意不做归属隔离**：12 个 controller（10 张业务表的增删改查 + 4 个关联查询 + 看板）**只要求已登录**，既不校验角色，也不做「教师只能看自己班」这类数据级过滤——因此**任何登录账号都能读写全部教务数据**（改 id 即可访问他人记录）。这是**明确的边界选择，不是遗漏**：本系统的登录/角色体系服务于「对话 + 用户管理」，教务是与之并列的独立业务域；要做归属隔离得先有「教师—班级」的归属规则和教师账号体系，凭空造一套不如不做。**若将来要多人共用，先拍板走哪条路**：角色级（写接口收进 `@RequireRole`，改动面 = 写方法 + 前端入口显隐）还是数据级（所有 edu mapper 强制按归属过滤，改动面 = 全部查询）——两者差别很大，别中途混用。
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
│   ├── result/         # RestResult / PageResult（统一响应与分页契约）
│   ├── BaseBO          # 分页入参基类：缺省 10 条、上限 100
│   └── exception/      # AiBusinessException / AiErrorCode
├── ai/                 # AI 多智能体平台
│   ├── controller/     # ChatController(SSE) / ConversationController / AgentController
│   │                   # / KnowledgeBaseController / AttachmentController / TraceController
│   ├── service/        # ChatService(编排门面) / ConversationService / AgentService
│   │                   # / KbService / KbSearchService / ChunkingService / QuotaService(成本配额)
│   │                   # / CrossSessionSearchService(跨会话召回) / ContentSafetyService(内容护栏)
│   ├── agent/          # AgentRouter(智能路由) / ParamFillingService / PlannerService
│   │                   # / MemoryMergeService / PromptService / QueryRewriteService
│   │   └── handler/    # RoundHandler + AgentRoundHandler / PlannerRoundHandler
│   │                   # / ReviewRoundHandler(并行评审) / RoundResult
│   ├── chat/           # ChatComposer(请求装配：人设/记忆/材料/工具/RAG)
│   ├── advisor/        # ToolUsageLoggingAdvisor / RoundTraceAdvisor
│   ├── tool/           # ToolRegistry / ToolProvider(注解式) / ToolCallbackSource(动态工具接缝)
│   │                   # / McpToolSource(MCP) / SubAgentTool(智能体转交，每轮现构)
│   │                   # / WeatherTools / DateResolver
│   │                   # / SqlQueryTool / SqlSafety / SqlSchemaTool / ChartTool
│   ├── memory/         # DbChatMemory(Spring AI ChatMemory 的 DB 实现)
│   ├── trace/          # RoundTrace / TraceService(异步落库) / LlmUsageService
│   ├── infrastructure/ # attachment / chroma(客户端+副本同步) / document / rerank / vision
│   ├── config/         # ExecutorConfig(线程池) / ChatMemoryConfig / ToolCallingConfig 等 AI 专用
│   ├── properties/     # MemoryProperties / RagProperties / VisionProperties / PromptProperties / QuotaProperties
│   │                   # / SafetyProperties / ReviewProperties / CrossSessionProperties
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
├── mapper/ai/*.xml      # AI 侧显式 SQL：追踪 / 成本 / 可观测 / 评测 / 跨会话召回（禁 @Select 注解，一律落 XML）
└── static/              # 前端（index.html / login.html / chat.html / edu.html / user.html + js / css）
```

### 一轮对话的编排流程

```
POST /api/chat/send | /stream
        │
        ▼
ChatController 前置闸门：内容安全（agent.safety 命中/超长直接 422，零模型调用）
        │
        ▼
ChatController 前置闸门：配额（agent.quota.enabled 时先算今日用量；超限直接 429，不进入编排）
        │
        ▼
ChatService（编排门面，同步 chat() / 流式 stream()）
   │
   ├─ 规划模式会话（conversation.planner=1）
   │    └─ PlannerRoundHandler：LLM 规划多智能体步骤 →（开启「先看计划」则在此暂停待确认）→ 按依赖并行执行
   │       （遇 `approval_required=1` 且未批准的步骤则在此暂停，推 `approval` 事件）→ 落库（只回传最后一步引用）
   │
   ├─ 评审模式会话（conversation.review_enabled=1，与 planner 互斥）
   │    └─ ReviewRoundHandler：选候选（绑定智能体固定入选 + LLM 补足）→ 候选并行独立作答 → 裁决者综合
   │       → 推 `review`（候选明细，在正文之前）→ 落库一对 user/assistant
   │
   └─ AgentRoundHandler（普通/智能体会话）
        ⓪ 前置链并行预取：RAG 查询改写 ∥ 路由 ∥ 参数抽取（三者并发，正式回答前 join）
        ① determineAgent   显式绑定 / CLARIFY 绑定 / 智能路由
        ② 话题切换预检      仅 CLARIFY 绑定，防止含城市词的新话题被误判为补全
        ③ decideClarify     参数抽取 → 缺失必填则追问；齐全则进入正式回答
        ④ buildRequest     人设 + 长期记忆 + 已确认参数 + 本轮附件材料 + 工具 + RAG 上下文
                            + 跨会话回忆（conversation.cross_session=1 时，推 `recall`）→ 主模型 call()
        └─ 收尾：推回复（输出侧内容安全在此过滤）→ 推 citations → 落库附件/引用 → 异步落库追踪 → 异步合并记忆
```

> 三条链路**互斥且按固定顺序判优先级**：规划 > 评审 > 普通。规划与评审都是「编排形态」，同时开启会让「本轮走哪条」取决于读取顺序，故开启任一方时后端顺手关掉另一方（`updatePlannerSwitch` / `updateReviewEnabled`），`selectHandler` 里再留一道同序防御。三条链路的收尾都走同一套出口，所以 `review` / `recall` / `citations` / 输出侧护栏**只有一个接线点**。

### 关键机制

| 机制 | 说明 |
|---|---|
| 绑定来源 `agent_bind_source` | `EXPLICIT`=用户显式选择（粘住不解绑）；`CLARIFY`=追问临时绑定（话题切换自动解绑）；空=自由路由 |
| 智能路由 | 裸 ChatModel 三态 JSON 决策（`{"route":true,"agentCode":"..."}` / `{"route":false}` / `{"route":false,"continue":true}`），Hutool 解析 |
| 参数追问 | `paramSchema` 声明参数；LLM 从有界历史抽取已确认取值；缺失必填生成 `🔎 还需补充信息`，上限 3 次；**状态每轮重放推导，不落库** |
| 记忆体系 | 窗口 = 原文预算 + **条数下限**（防单条超预算导致窗口塌缩）+ 单条截断；`DbChatMemory` 与 `MemoryMergeService` 共用同一 `MemoryProperties` 口径。**记忆可视化编辑**（`GET/PUT/DELETE /api/chat/conversation/{id}/memory`）：可查看与订正 `summary` / `core_facts`；手改**只覆盖内容列、不动水位**（`summarized_count` 是执行游标不是展示字段，跟着手改会让下次自动压缩从错位继续），要回退水位只能走「重置全部记忆」（三列归零、消息保留） |
| 对话分支 | 「重新生成」/「编辑重发」不再删历史，而是给该轮**再开一个版本**，旧版本留库可翻回。`chat_message` 三列：`turn_group_id` / `turn_version` / `turn_active`；`turn_group_id IS NULL` 即从未分叉（存量零回填）。**两段式**：`prepareBranch`（流式前只分组 + 算版本号，**不失效旧版本**）→ `markRoundBranch`（消息落库后打标，`marked == 0` 则旧版本保持生效）。**读取侧四处同口径**（`turn_group_id IS NULL OR turn_active = 1`）：`getHistory` / `getRecentHistory` / `countMessages` / `getMessagesRange`，漏一处即裂出记忆空洞。新版本 `created_at` 锚回该组首条时间以占回原位；切换 / 开版本重置记忆水位。重发**完全复用 `POST /api/chat/stream`**，不复制任何发送逻辑 |
| 步骤间产物传递 | 前驱产出注入下一步输入时按配额截断（`min(upstream-max-chars, upstream-total-chars / 前驱个数)`），超额保留前段 + 显式省略标注 + WARN；只作用于**注入**，不影响 `task_step.output` 落库与最终回复 |
| 规划模板 | 跑顺的规划可存成模板（`task_template.steps_json`）。**存快照 JSON、不引用 `task_step`**：那张表带 `status`/`output`/`retry_count` 等运行态列，且局部重规划（`replanTail`）会删改甚至重排行，引用式模板会被连带破坏。步骤只记 `agentCode` 不记展示名（智能体改名不该让模板失效），依赖落库前经 `TaskStep#strictPriorDeps` 净化 —— 只留严格前序，同时天然杜绝依赖环（所以不需要单独的环检测） |
| 子智能体上下文隔离 | 规划步骤的 system 分三档拼装：**首层**吃「已确认参数」、**末层（汇总步）**追加近期窗口历史、**中间步骤**默认不注入会话长期记忆（`conversation.summary` / `core_facts`）。中间步的活是「拿前驱产物做自己那一段」，会话级摘要往往是别的话题、反而把这一步带偏；开关 `agent.planner.isolate-middle-steps`（**RAG 资料不参与隔离**——每步 query 不同、按各自需要检索，与「会话记忆」不是一回事） |
| 提示词回归评测 | `prompts.yaml` 改动靠手感——评测把「跑批 + 断言」这层壳补上：`eval-cases.yaml` 声明用例（场景 `ROUTE` / `PLAN` + 期望 JSON），`EvalService` 并发跑真实链路并逐条判定，结果落 `eval_result`。三态分开：**通过** / **失败**（提示词质量问题）/ **配置错误**（用例本身写错，如引用了不存在的智能体——单列一档，不污染质量判断）。批次间可比：`/api/eval/compare` 输出 `fixed`（上一批失败、本批通过）与 `broken`（**上一批通过、本批失败**——改提示词最该先看的一屏） |
| 并行评审 | `ReviewRoundHandler` 是本项目的**第 7 次复用「一次落库 + 现成通路」接缝**：候选与裁决都是裸模型调用（`internalChatClient()`，不带记忆），最终回复仍由既有出口落库与推送，没有新增执行入口。候选并行跑在 `plannerStepExecutor` 上（与规划步骤同池 —— 两者互斥，不会互相抢），`future.get(timeout)` 超时即 `cancel(true)` 并按弃权处理。**候选互不可见、共用同一份 kb/recall**：互相可见会退化成串行接力；素材各取一次既控成本，也保证「谁答得好」里不混进「谁拿到的资料多」。落库的是**最终回答**、不是候选（候选走 `review` 事件，只展示） |
| 跨会话搜索 | `CrossSessionSearchService`：LLM 抽关键词 → `ChatMessageMapper.searchOwned` 做**归属过滤 + 排除当前会话**的字面召回 → 注入 system + 推 `recall`。**排序表达式写在 `ORDER BY` 里、命中数在 Java 侧重算**：若把命中数作为列返回，`resultType` 就得从实体换成 record，列数/列序必须严格对应（本项目踩过这类投影坑），为省几行 SQL 去换一个运行期才炸的雷不划算。关键词进 SQL 前必须清洗（剥编号引号 → 去 `%` `_` `\` → 限长），否则一个 `%` 就是全表匹配。召回结果**刻意不落库**：它是「本轮检索到了什么」，同一会话不同轮次结果完全不同，存下来只是一堆只对那一轮有意义的快照 |
| 内容安全 | `ContentSafetyService` 把输入/输出两侧的规则收在**一处**：规则在构造期一次编译成 `Pattern`（单条非法正则 WARN 后跳过，不让一条写错的规则拖垮整个功能），命中只记 WARN、**不落原文**。输入侧在 `ChatController` 的配额之前拦（超限/命中 → 422 `CONTENT_BLOCKED`，**零模型调用**）；输出侧在 `ChatService` 推送与展示前替换文案。**已知边界**：字面正则不是语义审核（换说法可绕过）、替换只作用于推送与展示（库里仍是模型原始输出，因为普通对话的消息由记忆 Advisor 在模型返回时就已落库）——两条都写进了类注释而不是假装没有 |
| 对话附件三通道 | ① 纯提问 `message` → 走记忆/路由，写 `chat_message.content`；② 解析文本 `material` → 注入**当轮** system，仅当轮可见；③ 展示元数据 `attachments_json`（不含正文）→ 仅供历史回看 |
| RAG 引用溯源 | `KbCitation` 与资料块**同趟产出**，出口三处同一份 JSON：落库 `chat_message.citations_json`、SSE `citations` 事件、`agent_trace.citations_json` |
| 流式策略 | Spring AI 2.0.0 的 `stream()` 在工具调用场景会崩（见「已知缺陷」），统一 `call()` 拿完整答案后按自适应分片模拟流式（总时长封顶 ≈2s） |
| 并发预取 | 前置链（路由/参数抽取/查询改写）由专用线程池并行，显著缩短首字节时延；预取只加速、不承担正确性，异常/超时一律回退用户原话 |
| 纯旁路追踪 | `agent_trace` 删掉后对话照常运行；写入在回复产出后异步、失败只记日志。**查询按会话归属过滤**（`JOIN conversation` 判定，ADMIN 走全量视角）—— 归属刻意不写进表：加 `user_id` 列就得把身份一路传进异步落库链路，会破坏这条「旁路」原则 |
| SSE 兜底 | 有限超时（默认 300s）+ 独立心跳调度器（默认 15s，专用线程池，不与打字机抢 Reactor 线程） |
| 工具注册 | 三类来源统一进 `ToolRegistry`：注解式（`ToolProvider` + `@Tool` 反射）、动态式（`ToolCallbackSource`，如 MCP 远端工具、启动后才知道有哪些）、以及**动态工具**（实例依赖调用方上下文，只登记元信息供勾选，由 `ChatComposer` 每轮现构 —— 目前只有 `call_agent`）；同名时注解式优先并告警 |
| 智能体转交 | `call_agent(agentCode, instruction)`：普通对话里也能把子任务转给别的智能体。候选清单每轮现查（增删智能体立刻生效）、白名单专属（`NULL` 全量不带它）、只做一层（子智能体不挂本工具 ⇒ 无递归）；子智能体产出按 6000 字上限截断 + 显式标注 |
| 先看计划 | 规划暂停时落库 task（RUNNING、步骤全 PENDING）但不执行，SSE 推 `plan` 事件 + 计划文本；确认走的就是断点续跑（`POST /api/chat/task/resume`）——「确认执行」与「中断续跑」共用同一条后端通路，没有第二套执行入口 |
| 步骤审批点 | `task_step.approval_required` / `approved` 两列，**独立于 `status` 状态机**：status 表达「跑到哪了」（PENDING/RUNNING/DONE…），审批表达「允不允许跑」，两者正交 —— 若把「待审批」做成一个 status 值，`markStepRunning`、`isSettled`、续跑重试判定等既有分支都得为它再加一个态。执行侧的改动只是「找 ready 步骤」处插一道闸门。**闸门粒度 = 整条流水线暂停**（不是只挡该步、兄弟步照跑）：后者会让用户看到「卡住那步后面的步骤先出了结果」，流水线因果顺序难解释，而「到此为止」更符合审批点的直觉。批准后复用断点续跑，取消审批时一并把 `approved` 归零（否则「先勾→批准→取消→再勾」会沿用旧批准，用户以为重新设了关卡、实际已被静默放行） |
| 成本配额 | `QuotaService` 按**用户 × 自然日**汇总 token（`agent_trace` + `llm_usage` 两表 `UNION ALL`，与成本看板同一口径，`SUM` 必须 `COALESCE` 兜零行 NULL）。**超限明确报错**（429 `QUOTA_EXCEEDED`，消息含已用/上限/重置时间），不换模型、不截断、不假装成功。开关 `agent.quota.enabled` 默认关；`warn-ratio` 到点推一条 `progress` 预警；`exempt-admins` 默认 true（管理员不被自己的护栏挡住）。**已知边界**：检查发生在每轮开始前 ⇒ 单轮可能小幅超出；`/api/eval/**` 跑批不经这道闸门（评测自带 ADMIN 限制）。统计异常时 fail-open 放行 + WARN —— 这不是「静默降级」：日志明确记了原因，且配额是治理手段而非正确性保障，不因统计故障挡住正常对话 |

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
| POST | `/api/chat/stream` | SSE 流式对话；事件 data 为 `{"token":"..."}` / `{"progress":"..."}` / `{"citations":[...]}` / `{"plan":"{...}"}` / `{"approval":"{...}"}` / `{"review":"{...}"}` / `{"recall":[...]}` 等 |
| POST | `/api/chat/attachment/process` | 批量解析附件：multipart `files` → `{"results":[{"type","filename","content","storedName","size","url"}]}`。**图片在此内部委托 `VisionService` 并发识别**（无独立视觉端点）；`type` ∈ `image`/`text`/`file` |

> 请求体 `ChatRequest`：`{conversationId, message, planner, attachments:[{type,content,filename,storedName,size}]}`；`planner` 非空时覆盖并写回会话形态。

### 会话

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat/conversation` | 开新会话（可传 `agentId` 绑定智能体，或 `planner:true` 建规划会话）；归属当前登录用户 |
| PUT | `/api/chat/conversation/{id}` | 重命名会话（仅本人） |
| PUT | `/api/chat/conversation/{id}/planner` | 更新规划开关 `{enabled}`（绑定智能体的会话不可开启；**开启时顺手把 reviewEnabled 置 false**；仅本人） |
| PUT | `/api/chat/conversation/{id}/planner-confirm` | 更新规划「先看计划」开关 `{enabled}`（开启后规划只产出计划并暂停，点「执行计划」才跑；仅本人） |
| PUT | `/api/chat/conversation/{id}/rag` | 更新 RAG 开关 `{enabled}`（仅本人） |
| PUT | `/api/chat/conversation/{id}/review` | 更新并行评审开关 `{enabled}`（**开启时顺手把 planner 置 false**，两者互斥；仅本人） |
| PUT | `/api/chat/conversation/{id}/cross-session` | 更新跨会话搜索开关 `{enabled}`（开启后每轮在本人其他会话里做关键词召回并注入；仅本人） |
| DELETE | `/api/chat/conversation/{id}` | 删除会话及全部消息（仅本人） |
| GET | `/api/chat/conversations` | **当前用户**的会话列表（按最近更新倒序） |
| GET | `/api/chat/history?conversationId=` | 读取会话历史消息（仅本人） |
| POST | `/api/chat/conversation/{id}/branch` | **开新版本**：为第 `keepCount` 条（1 基，必须是 user 提问）那一轮分组并返回 `{groupId, version}`；未分组则新建组、旧轮记第 1 版、返回 `version=2`，已分组则 `max(turn_version)+1`。**只分组、不改生效版本**（失效延后到落库后）；缺请求体 400（分支位置没有安全默认值；仅本人） |
| POST | `/api/chat/conversation/{id}/turn` | **切换版本**：body `{groupId, version}`，把该组其余版本 `turn_active` 置 0、目标置 1，并重置记忆水位；版本不存在 404（仅本人） |
| GET | `/api/chat/conversation/{id}/export` | 导出会话为 Markdown，返回 `{filename, content}` 由前端拼 Blob 下载（仅本人） |
| GET | `/api/chat/conversation/{id}/memory` | 读取长期记忆快照：摘要 / 核心事实 / 已压缩条数 / 消息总数（仅本人） |
| PUT | `/api/chat/conversation/{id}/memory` | 覆写摘要与核心事实（body `{summary, coreFacts}`，**不动水位**；空白即清空该字段；仅本人） |
| DELETE | `/api/chat/conversation/{id}/memory` | 重置长期记忆：摘要 / 核心事实 / 水位三列归零，历史消息保留（仅本人） |
| POST | `/api/chat/task/resume` | SSE 流式续跑未完成任务（显式按钮触发）：回填已完成步骤、只跑剩余步骤 |
| GET | `/api/chat/task/running?conversationId=` | 查询当前会话的 RUNNING 任务（无则 null，供「继续执行」提示条；仅本人） |
| PUT | `/api/chat/task/step` | 就地编辑待确认计划中尚未执行的某一步 `{conversationId, stepIndex, agentCode, instruction, dependsOn, approvalRequired}`；只收 `PENDING` 步骤，依赖只能指向更早的步骤；`approvalRequired` 为空表示不改该列 |
| POST | `/api/chat/task/approve` | 批准被审批关卡挡住的步骤 `{conversationId, stepIndex}`，然后由前端接着调 `/task/resume` 继续跑（此接口只落标记、不触发执行） |
| POST | `/api/chat/task/cancel` | 终止当前会话的 RUNNING 任务 `{conversationId}`，置为 `CANCELLED`（审批卡片上的「终止计划」） |
| POST | `/api/chat/task/replan` | 局部重规划：只重排第一个未成功步骤及其之后的一段，返回重排后的完整计划（不自动执行） |
| GET | `/api/chat/quota` | 当前用户的配额快照 `{enabled, exempt, used, limit, remaining}`（本人可见，不要求 ADMIN；`enabled=false` 时前端不显示刻度） |
| GET | `/api/chat/task/template/list` | 本人的规划模板列表（创建时间倒序，含步数与套用次数） |
| POST | `/api/chat/task/template/save` | 把某次任务的步骤骨架存为模板 `{taskId, name, description}`；步骤以**库里的当前形态**为准，不采信前端快照 |
| POST | `/api/chat/task/template/apply` | 套用模板 `{conversationId, templateId, goal}` → 按骨架落库新任务并返回计划 JSON（**不自动执行**） |
| DELETE | `/api/chat/task/template/{id}` | 删除本人模板；不存在或非本人一律 404 |

> **会话按用户隔离**：`/api/chat/**` 全部要求登录，会话归属 `conversation.user_id`。列表只回本人会话；对他人会话做读取/改名/开关/删除一律返回 **404**（与「会话不存在」不可区分，避免用 ID 探测）。身份在 HTTP 线程由 `AuthContext.require()` 取出后作为参数传入服务层 —— 流式执行体跑在弹性线程上，`ThreadLocal` 在那里已失效。
> **五条规划通路共用一个执行入口**：首次规划、「先看计划」确认、断点续跑、套用模板、**审批放行** —— 最终都落到 `POST /api/chat/task/resume`。原因是计划一旦落库，执行侧（`PlannerRoundHandler#resumeTask`）只按库里的 `task_step` 重建执行，所以「改库即生效」，**不存在第二套执行逻辑**；模板套用与审批批准因此都只做「落库/标记」这一步，真正的执行仍是同一条通路。
> **模板按用户隔离**：`/api/chat/task/template/**` 一律按 `user_id` 过滤，取不到即 **404**（与「不存在」不可区分，防拿 ID 探测他人模板）。

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
| GET | `/api/agent/export` | 导出全部智能体为可移植 JSON（剥掉自增 id 与时间戳；**不含**专属知识库内容） |
| POST | `/api/agent/import` | 导入智能体：body `{agents:[...], onConflict:"skip"\|"overwrite"}`；按 `agent_code` 匹配，逐条容错，单条失败不影响其余 |

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
| GET | `/api/kb/chunk/{chunkId}` | **单块原文**（引用回链「查看原文」用）→ `{chunkId, kbId, kbName, source, content}`，**不含 embedding**；块不存在 → 404 + 原因 |
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
| GET | `/api/trace?conversationId=&limit=` | 追踪列表（时间倒序，默认 50 条、上限 200）。**只回当前登录用户名下会话的记录**；不传 `conversationId` 表示「不限会话」，但**不是**「不限用户」 |
| GET | `/api/trace/{traceId}` | 单轮追踪详情；不存在**或不属于当前用户**一律 404（二者不可区分，防拿 traceId 探测他人记录） |

> **可见性**：`ADMIN` 不受归属限制 —— 它走全量视角（可看所有人的追踪，含已删除会话遗留的记录），前端追踪弹窗会相应多出「本会话 / 全部会话」切换。
> 归属判定在 SQL 层由 `JOIN conversation` 完成（`agent_trace` **没有** `user_id` 列 —— 加列就得把身份一路传进异步落库链路，会破坏「追踪是纯旁路、不影响对话逻辑」这条原则）。
> 附带效果：**删除会话不会删除 trace**，因此存在指向已删会话的孤儿记录；JOIN 天然查不出它们，普通用户不可见、ADMIN 仍可见，符合「追踪作为旁路留档」的定位。
> 指定 `conversationId` 且非 ADMIN 时，会先校验会话归属，不是自己的直接 404 —— 这样「会话不是你的」与「这个会话还没产生追踪」不会混为一谈。

### 成本看板（仅 ADMIN）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/cost/summary?days=` | 近 N 天（默认 30、上限 90）全量成本聚合：回答本身 + 裸调用按用途拆解 |

> **仅 ADMIN 可访问**（`CostController` 标 `@RequireRole(ADMIN)`，非管理员 403 `ROLE_DENIED`）。
> 为什么是管理员专属而不是「每人看自己的」：这张看板的口径是**全站聚合**，聚合成一行「今天花了多少 token」后就分不出是谁的，
> 按归属收敛在技术上就做不到；而「按人拆分」会暴露他人用量对比，产品上也没有这个诉求 —— 成本属运营视角数据。
> 前端顶栏「💰 成本」入口按同一角色显隐，普通账号看不到入口（不是点了才 403）。

### 可观测面板（仅 ADMIN）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/observability/summary?days=` | 近 N 天（默认 7、上限 90）运行质量聚合：总览（轮次/成功率/平均耗时/token）+ 按天·按形态·按来源·按智能体 + 慢轮 Top N |

> **仅 ADMIN 可访问**（`ObservabilityController` 标 `@RequireRole(ADMIN)`）。与成本看板同一收敛逻辑：跨会话全站聚合，
> 逐条看某会话的链路细节走「🔍 追踪」（已按归属隔离），全站质量看这里。独立页 `/observability.html`，
> 顶栏「📊 可观测」入口按同一角色显隐。
> 指标口径：成功率 = 1 − `error` 轮占比；耗时取 `elapsed_ms` 平均（本轮从进编排到产出回复的**总耗时**，
> 不细分路由/改写/参数抽取的分段耗时 —— 那需要加列，暂不做）。

### 提示词回归评测（仅 ADMIN）

> 改 `prompts.yaml` 前后各跑一批，看「上一批通过、本批失败」清单（`broken`）有没有变长。
> 跑批走**真实链路**（真实模型调用），消耗计入成本看板，会话 ID 固定标记为 `__eval__`，不与真实对话混算。
> **整类需 `ADMIN` 角色**：跑批会花钱，属运营视角动作；前端顶栏「🧪 评测」入口按同一角色显隐。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/eval/cases?scenario=` | 列出用例集（`scenario` 可筛 `ROUTE` / `PLAN`，不传即全部） |
| POST | `/api/eval/run?scenario=` | 跑一批：并发执行 + 单条超时（默认 60s），返回 `{batchId, total, passed, failed, configErrors, costMs, results[]}` |
| GET | `/api/eval/batches` | 历史批次摘要（近 20 批，更旧的自动清理） |
| GET | `/api/eval/batches/{batchId}` | 某批次的逐条结果 |
| GET | `/api/eval/compare?from=&to=` | 两批对比：`fixed` / `broken` / `stillFailed` / `onlyFrom` / `onlyTo` |

> 用例集文件默认 `classpath:eval-cases.yaml`（`agent.eval.cases-file` 可改）。用例格式：
> `name`（用例名）、`scenario`（`ROUTE` / `PLAN`）、`input`（用户输入）、`pendingQuestion`（可选，模拟上一轮追问）、
> `expect`（断言 JSON）。**断言只写可确定的部分**——路由类断言 `{"noRoute":true}` 或 `{"agentCode":"A002"}`，
> 规划类断言 `{"containsAgents":["A001"]}`，别断言模型措辞（那是在测模型，不是在测提示词）。

### 教务（仅需登录）

10 张业务表共用一套**命令式**接口；**只要求已登录，不校验角色、不按归属过滤**（原因见 §3.1）。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/edu/{表}/page` | 分页，筛选条件走 body。`{表}` ∈ `subjects` / `teachers` / `classes` / `students` / `semesters` / `courses` / `periods` / `arrangements` / `exams` / `scores` |
| GET | `/api/edu/{表}/list` | 下拉选项，只回 `{id, label}`（label 已由服务端拼成可读文案） |
| GET | `/api/edu/{表}/{id}` | 单条详情 |
| POST | `/api/edu/{表}/save` | 新增 |
| PUT | `/api/edu/{表}/update` | 编辑（id 在 body） |
| DELETE | `/api/edu/{表}/delete/{id}` | 删除（被其他表引用时拒绝） |

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/edu/dashboard` | 看板统计：总量 + 各年级学生分布 + 各科老师分布 |
| GET | `/api/edu/score-detail` | 学生成绩明细（join 学生/班级/科目/学期/考试），分页；可筛 `classId`/`subjectId`/`semesterId`/`keyword` |
| GET | `/api/edu/score-stats` | 成绩统计（按考试聚合人数/均分/最高/最低），分页；可筛 `classId`/`subjectId`/`semesterId` |
| GET | `/api/edu/timetable` | 班级课表（join 课程/科目/老师/节次），分页；可筛 `classId`/`teacherId`/`dayOfWeek` |
| GET | `/api/edu/schedule` | 考试日程（join 班级/科目/学期），分页；可筛 `classId`/`subjectId`/`semesterId` |

> 外键列的可读名由服务端 join 进 `{表}VO`，前端不做 id→名称映射，也没有集中的字典接口（详见「数据库表」末的教务说明）。

## 数据库表

| 表 | 关键列 | 说明 |
|---|---|---|
| `conversation` | id, **user_id**, title, agent_id, planner, **planner_confirm**, agent_bind_source, rag_enabled, **review_enabled**, **cross_session**, summary, summarized_count, core_facts | 会话：**归属用户（按 user_id 隔离，仅本人可见）**、绑定智能体、规划开关 / 规划「先看计划」开关、RAG 开关、并行评审开关、跨会话搜索开关、滚动摘要与核心事实。**规划与评审互斥**（都是编排形态，开启任一方会自动关掉另一方） |
| `chat_message` | id, conversation_id, role, content, attachments_json, citations_json, **turn_group_id**, **turn_version**, **turn_active**, created_at | 消息明细；附件元数据与引用来源**独立列**，不进记忆、不占 token。**分支版本三列**：`turn_group_id`（同轮多版本共用 UUID，NULL=从未分叉）/ `turn_version`（组内序号，从 1 连续递增）/ `turn_active`（当前生效版本，同组至多一个为 1）；读取侧统一 `turn_group_id IS NULL OR turn_active = 1`，存量数据零回填 |
| `agent` | id, name, agent_code, icon, description, system_prompt, param_schema, tools_json, model, temperature, avatar_color | 智能体：人设、参数清单、工具白名单、模型/温度覆盖 |
| `kb` | id, name, agent_id, description, doc_count, chunk_strategy, chunk_overlap | 知识库；`agent_id` 为空即通用全局库 |
| `kb_chunk` | id, kb_id, content, source, embedding, created_at | 知识块；`embedding` 为向量 JSON 文本（MySQL 源） |
| `kb_file` | id, kb_id, file_name, file_type, chunk_strategy, chunk_overlap, size_bytes, chunk_count, raw_text | 以文件为管理单元；`raw_text` 支持不重传重新分片 |
| `agent_trace` | trace_id, conversation_id, mode, route_source, agent_code, user_message, retrieval_query, plan_json, tool_calls, kb_hit_count, citations_json, prompt_tokens, completion_tokens, total_tokens, elapsed_ms, status | 纯旁路可观测表，删掉不影响对话。`mode` 现有 `agent` / `planner` / `review` 三态，`route_source` 相应有 `REVIEW`（并行评审），可观测面板的「按形态 / 按处理方来源」分布会自动多出这两档 |
| `llm_usage` | trace_id, conversation_id, purpose, model, prompt_tokens, completion_tokens, total_tokens, created_at | 裸 LLM 调用成本流水（全量成本口径）：路由/参数抽取/查询改写/计划生成/视觉/记忆合并/智能体转交/跨会话召回/并行评审各记一条，按用途拆解 |
| `task` | id, conversation_id, user_goal, status, total_steps, done_steps, result, created_at, updated_at | 规划任务：一轮规划落库一条，状态机 `RUNNING→DONE/FAILED/CANCELLED`；单会话单 RUNNING |
| `task_step` | id, task_id, step_index, agent_code, instruction, depends_on, status, retry_count, output, error, citations_json, **approval_required**, **approved**, started_at, finished_at | 任务步骤：逐步增量提交产出；`FAILED` 续跑重试一次，累计 ≥2 判确定性失败。`approval_required=1` 的步骤执行前先暂停等待批准（`approved` 记批准与否）—— 这两列**独立于 `status`**：status 说「跑到哪了」、审批说「允不允许跑」 |
| `task_template` | id, user_id, name, description, steps_json, source_task_id, use_count, created_at, updated_at | 规划模板：**按 user_id 隔离**（仅本人可见，越权一律 404）。`steps_json` 是步骤骨架的**快照**（`[{agentCode,instruction,dependsOn}]`），不引用 `task_step`——那张表会随局部重规划删改/重排行 |
| `eval_result` | id, batch_id, case_name, scenario, input, expected, actual, passed, config_error, failure, detail, cost_ms, created_at | 提示词回归评测逐条结果；`batch_id` 分组一批，`config_error=1` 为「用例本身写错」单列一档；只保留最近 20 批，更旧的跑完即清 |
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

- **顶栏**：会话列表（含 🧭 规划标记）、当前会话徽标（🧭 规划模式 / 📚 RAG）；右侧动作区**整体靠最右**，依次为 ⬇ 导出（无会话时不显示）、🧠 记忆（无会话时不显示）、🔍 追踪（无会话时不显示）、💰 成本（仅 ADMIN）、🧪 评测（仅 ADMIN）、📊 可观测（仅 ADMIN，跳 `/observability.html`）、🎓 教务系统（跳 `/edu.html`，与 edu 页的「前往 AI 对话」互为对称入口）、以及**登录用户区**。靠右由容器 `.header-actions` 统一负责（`margin-left:auto` + `gap`），各按钮不自带 `margin-left` —— 否则「追踪」这类条件渲染的按钮一缺席，整组就会塌回标题旁边。按角色显隐的入口读页面级 `isAdmin`（setup 时从 `Auth.hasRole('ADMIN')` 取一次存进 `ref`）—— `Auth.getUser()` 读 localStorage、不是响应式的，模板里直接调它只会求值一次。
- **登录用户区（四页统一，只有一个用户名）**：顶栏不再出现裸露的「退出」按钮 —— 点击用户名展开下拉：**个人信息 / 修改口令 / 用户管理（仅 ADMIN）/ 退出登录**。四页（index / chat / edu / user）都是 `js/auth.js` 渲染到 `data-auth-nav` 挂载点的**同一份实现**，页面自身不含登录逻辑；细节见「登录」一节。
- **输入区**：🖼 图片多选（≤5 张）、📎 文档上传、📚 RAG 开关、🧭 规划开关（绑定智能体的会话置灰）、以及规划开关开启时出现的 **「先看计划」开关**（规划只产出计划并暂停，确认后才执行）。处于「编辑重发」态时，输入区顶部会出现**编辑提示条**（原消息已填回输入框，右侧一间取消按钮），发送即先截断再重发。输入区底部另有**配额刻度**（`v-if="quota.enabled && !quota.exempt"`）：显示「已用 / 上限」，用量到 90% 时整条转警示色 —— 只在配额开关打开且本人不豁免时出现，平时不占位置。
- **对话分支（版本切换器）**：鼠标悬停任一消息气泡浮现动作区（默认 `opacity:0`，`.msg:hover` 才显示）—— assistant 消息给「重新生成」，user 消息给「编辑重发」，以及**该轮已有多版本时挂在提问上的「n / m ‹ ›」切换器**（切一次提问与回答一起换）。**重新生成** / **编辑重发**都先调 `POST …/branch` 开新版本，再走现成的流式通路发送（不复制发送逻辑）；开版本失败会中止并保留编辑态（不静默丢掉用户已改的内容）。**切换器只在 `versionCount > 1` 时出现**（未分叉的轮不挂），首 / 末版本对应的箭头置灰。生成中这些按钮一律不出现——正在写入的那一轮尚未落库，此时切换只能拿到一份对不上眼前所见的历史。
- **引用回链（气泡内）**：正文里的 `[n]` 是蓝色可点角标，点一下展开该轮「引用来源」并把第 n 条高亮（2 秒后自动褪去，用背景闪烁而非描边 —— 不改行高就不会让列表滚动位置跳动）；找不到对应序号会明确提示「可能是模型自行标注的角标」，而不是点了没反应。来源条目右侧的「原文」按钮打开**引用原文弹窗**（库名 / 文件名 / 块号 + 知识块正文按原样 `pre-wrap` 展示），取块失败时弹窗内显示后端给的原因；追踪弹窗的引用来源同样带这个入口。
- **计划卡片（「先看计划」开启时）**：规划暂停后，AI 气泡内呈现计划卡片 —— 步骤清单（序号 / 智能体 / 指令 + 每步一个「需审批」勾选框）+ 「执行计划」按钮，右上角标 `待确认 / 执行中… / 已执行`。点按钮走的就是断点续跑通路。刷新后卡片消失（`plan` 事件不落库），但**顶部「执行计划」提示条仍在** —— 它是计划卡片之外的第二入口，保证刷新后仍能接着执行。**勾选「需审批」的步骤执行到时会停下来**，页面弹出**审批卡片**（刻意的琥珀色调，与蓝色计划卡片区分「等你决定」）：显示第几步 / 共几步、智能体名与指令，并提供「批准并继续」与「终止计划」两个动作。勾选状态**失败会回滚到原值**（不让界面显示成已生效）。
- **记忆面板（🧠 记忆）**：把此前完全黑盒的双层记忆摊开 —— 显示滚动摘要、核心事实、覆盖度（`summarized_count` / 消息总数）与两个可编辑文本框（摘要在上、核心事实在下）。可**订正内容**（保存只覆盖这两个字段、不动水位），也可**重置全部记忆**（三列归零、历史消息保留）。面板内向用户明说：改内容不影响历史消息、重置后记忆会从头重新压缩。
- **会话导出（⬇ 导出）**：把当前会话导出为 Markdown（含每轮的 user/assistant 正文、附件文件名、RAG 引用来源）。**不走 `/files/**` 文件通道** —— 下载要带 `Authorization`，而浏览器对 `<a href>` 导航带不上该头，走文件通道必 401；故后端只回 `{filename, content}`，前端拼 `Blob` 下载。
- **智能体管理页**：智能体列表 / 新建 / 编辑（人设、参数 schema、工具勾选、模型与温度、配色）；工具栏右侧另有**导出 / 导入**，把智能体当资产搬进搬出。导出为 JSON 数组（`AgentPortable`，**不含 id / 时间戳、不含专属知识库内容**）；导入按 `agent_code` 匹配，冲突策略可选**跳过或覆盖**，且**逐条容错**（单条脏数据只记进 `errors`、不让整批失败）。`overwrite` 必须**保留本地 id**——`agent.id` 被 `conversation.agent_id` 引用，换 id 会切断会话归属。
- **知识库页**：库/文件管理、上传与重新分片、分页查看知识块、Chroma 状态条与「同步本库」。
- **追踪弹窗**：路由来源、规划步骤、检索问句、RAG 命中、工具调用、token 与耗时。**可见范围**：默认只显示当前登录用户名下会话的记录（后端按会话归属过滤，非本人记录按不存在处理）；**ADMIN 额外有一个「本会话 / 全部会话」切换**，可查看全站追踪。工具栏文案会随范围实时变化，空态也按范围给不同措辞——「本会话没有」和「全站都没有」是两回事。列表**每页 10 条**，底部页码条显示「共 N 轮 · 第 x / y 页」，翻页后自动滚回列表顶部；聚合统计条始终基于**全量**记录（它回答「这个范围的总体情况」，不是「这一页」）。明细展开态按 `traceId` 记录 —— 用列表下标记会在翻页后串页（第 1 页第 3 条与第 2 页第 3 条共用一个展开态）。**分页与范围无关**：本会话与全部会话共用同一套分页（后端也只有 `GET /api/trace` 一个接口，`conversationId` 只是过滤参数）；页码条按「有数据」渲染而不是「页数 > 1」—— 本会话多半不足一页，若按页数判断会整个不显示，看起来像「只有全部会话才分页」。1 页时只出页码信息、翻页按钮组隐藏。
- **成本看板弹窗（💰 成本，仅 ADMIN）**：全量成本按天趋势（堆叠柱）+ 按用途拆解（饼图），近 7/30/90 天切换。入口按 ADMIN 角色显隐（后端 `@RequireRole(ADMIN)`，普通账号连入口都不渲染）。
- **评测弹窗（🧪 评测，仅 ADMIN 可见）**：场景切换（全部 / 路由 / 规划）、用例条数、▶ 跑一批 → 三态统计（通过 / 失败 / 配置错误，逐条左边框绿 / 红 / 琥珀区分）+「最近两批对比」（`已修复` / `新增失败`，后者红底加粗，是改提示词后最先要看的一行）+ 历史批次列表。跑批走真实调用、计入成本看板。
- **可观测面板（📊 可观测，独立页 `/observability.html`，仅 ADMIN）**：六张总览卡（轮次 / 成功率 / 平均耗时 / 平均 token / 总 token / 活跃天数）+ 按天趋势表 + 三个分布块（按形态 / 按处理方来源 / 按智能体，横向占比条）+「最慢的 N 轮」明细表（红/绿徽标区分失败/正常，`user_message` 截断展示）。近 7/30/90 天切换。非 ADMIN 打开只显示「仅管理员可用」提示，数据也不请求。
- **教务系统**（`/edu.html`，独立入口）：10 张业务表（科目/老师/班级/学生/学期/课程/节次/排课/考试/成绩）的增删改查 + 4 个关联查询看板（学生成绩明细、成绩统计、班级课表、考试日程），复用深色色板，与 AI 对话页分离。单表页与关联查询页共用同一套顶部搜索条（文本输入 + 外键/枚举下拉 + 查询/重置，按 `tableMeta.search` / `queryMeta.search` 声明渲染）、序号列、右下分页（首页/上一页/下一页/尾页/跳页[/每页条数]）；新增与删除走自绘弹窗。

- **登录**：登录界面全站只有一份 —— 结构在 `js/auth.js`（`Auth.openLogin()`），样式在 `css/auth.css`（`auth-` 前缀变量与类名，与各页样式互不污染）。
  - **首页**（`/index.html`）右上角「登录」按钮：点击**就地弹框**（不跳页），登录后原地变为用户名的下拉菜单（个人信息 / 修改口令 / 用户管理 / 退出登录）。首页自身不含登录逻辑，只放一个 `data-auth-nav` 挂载点。
  - **受限页**（chat / edu / user）**先锁住页面、再确认登录态，最后才决定是否渲染页面**：`Auth.requireLogin()` 在 `<head>` 里同步把整页盖住（`html.auth-locked` + 一句「正在校验登录状态…」），无 token 直接弹登录框；有 token 也先向 `GET /api/auth/me` 确认，**只有确认有效才解除遮罩放行渲染**。所以直接打开 `/chat.html`、`/edu.html` 不会先闪一眼未登录的空壳页（此前只看本地 token、页面照常渲染，等首个接口 401 才弹框，观感是「先进去再被踢出来」）。弹的是**整页观感的登录框**（不透明底，不会把空壳页透出来），登录成功后自动重载当前页；关掉弹框会回到首页 —— 受限页在没有登录态时数据全 401，留在空壳页面上没有意义。运行中 401 同样弹框并提示「登录状态已失效」。需要登录才能走的链接加 `data-auth-required` 即可，点击时由 `Auth.guardNavigation()` **先确认登录态再放行**：未登录就地弹框，已登录也先向 `GET /api/auth/me` 确认 token 仍有效（token 会被服务端单方面作废 —— 过期、账号停用、换密钥重启），确认通过才跳转，避免「先跳进去、再被踢出来」。
  - `/login.html` 只是弹框的「整页模式」外壳（调 `Auth.mountLoginPage()`），保留它是给未登录的深链访问一个落地地址；登录成功后回跳 `?redirect=`，只接受站内路径，防开放重定向。
  - token 存 `localStorage`（键 `my_agent_token`）；退出登录 = 前端丢弃 token（服务端无会话可销毁）。
  - **用户菜单（顶栏唯一入口）**：点击用户名展开下拉，共四项 —— **个人信息**（弹框展示登录名 / 昵称 / 邮箱 / 角色 / 状态 / 最近登录 / 创建时间；先用本地会话快照渲染、再回源 `GET /api/auth/me` 覆盖，避免昵称或角色被改后仍显示旧值。资料**只读**，修改走管理员的「用户管理」）、**修改口令**（弹框三个口令字段，前端即时校验 6~64 位 / 两次一致 / 与原口令不同，提交 `POST /api/auth/password`；成功后**不自动关闭弹框、也不清本地 token** —— 口令要留给用户确认一眼，且 JWT 无状态、改密不影响已签发的 token）、**用户管理**（仅 ADMIN，服务端另有 `@RequireRole` 兜底）、**退出登录**（危险色，置于分隔线之下）。下拉的收起路径三条：再点触发器、点页面空白、按 Esc。
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
