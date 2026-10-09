# My_Agent 多智能体对话平台

基于 **Spring Boot 4 + Spring AI 2.0** 的多智能体（Multi-Agent）对话平台：支持自定义智能体、智能路由、参数追问补全、动态规划编排、双层长期记忆、知识库 RAG、多模态图片理解与工具调用，前端为内置静态页面，开箱即用。

## 功能特性

### 用户与权限

- **登录鉴权**：JWT（Hutool 签发，HMAC-SHA256）。token 只装身份与有效期，**权限每次请求回库取**，因此改角色 / 停用账号立即生效，不必等 token 过期。
- **用户与角色管理**（`/user.html`，限 ADMIN）：用户 CRUD + 口令重置、角色 CRUD。口令只存 BCrypt 哈希，列表与详情都不回显。
- **默认拒绝自锁**：不能停用或删除当前登录账号；摘掉自己最后一个 ADMIN 角色会被拒 —— 系统始终保留至少一个启用状态的管理员。
- **操作审计（谁把谁的权限改成了什么）**：用户/角色的增删改都往 `audit_log` 落一行（`/user.html` 第三个 tab 查看，限 ADMIN）。权限变更此前只在业务表上留下**结果**（`sys_user_role` 换了一行、`sys_user.status` 从 1 变 0），结果不说明是谁干的；审计要回答的是这个**过程**。
  - **只增不改**：没有更新/删除入口，清理只能由 DBA 直接操作数据库 —— 一个能通过接口删审计的系统等于没有审计。
  - **明细先脱敏再落库**：`detail` 写入前统一过 `PiiMasker`（手机号 / 身份证 / 邮箱 / 银行卡遮中段），否则审计表自己就成了敏感信息聚集地。**副作用明说**：脱敏按「内容长什么样」判定、不区分字段，所以用户资料里的邮箱也会被遮（`zh******@example.com`）—— 要看原值去用户列表，审计只负责说明「改过什么」。**口令绝不进明细**（只记「更新口令：xxx」）。
  - **明细只写真的变了的项**：全量罗列会让明细塞满无变化的噪声，一眼看不出这次到底动了什么；角色 ID 列表**输出前排序**，免得「只是顺序变了」被记成一次角色变更。
  - **失败不拖垮业务**：落库异常只记 ERROR（日志能看到哪个动作没留痕），业务继续。记录点在业务写操作**之后**，因此不会出现「操作没生效、审计却记了一笔」的假记录（这不等于事务保证 —— 本模块的 service 方法都没有 `@Transactional`）。

### 对话与智能体

- **自定义智能体**：页面创建任意角色（翻译、天气、教育数据分析、代码助手…），配置人设提示词、模型、温度与可用工具；提示词自动汇总到 `agent_code.md`。
- **智能路由**：未绑定智能体的会话由 LLM 三态路由（命中智能体 / 普通对话 / 正在回答追问），并携带最近上下文识别「北京呢？」这类承接上一轮的短追问。
- **参数追问补全**：智能体用 `paramSchema`（JSON 数组）声明参数；缺失必填项时自动追问（上限 3 轮，常量 `ClarifyState.MAX_ASKED` 唯一来源），参数齐全才正式回答。跟进任务会继承上一轮已明确的参数（如「今天」→ 日期=今天）。**追问状态显式落库**（`conversation.clarify_state`，JSON `{agentId, asked, request, question, params}`），跨轮稳定、跨重启一致；历史重放**降级为兜底**——老会话没有这一列时行为与改造前一致，不因升级而回退。**为什么要落库**：此前每轮都靠扫历史重放推导「已问几次 / 已确认哪些参数」，一旦原始请求被摘要压缩滑出窗口、或被标「不参与记忆」，重放就会**算错**（追问次数从头算、已确认参数丢失）。`agentId` 即**换智能体作废**判据（路由转向别的智能体时状态自然失效，无需额外清理），解绑智能体时一并清空；`clearMessages` 也清（消息没了、追问失去对象），而「重置记忆」**刻意不清**（消息仍在、追问仍可答）。
- **动态规划（Planner）**：会话级 🧭 开关开启后，由 LLM 运行时把用户目标拆成多智能体步骤并**按依赖并行**执行；执行过程实时展示、不写入记忆。规划模式与绑定智能体互斥。**任务状态持久化**：每轮规划落库 `task`/`task_step`，服务重启/中断后可点「继续执行」显式续跑剩余步骤（不重新规划）。
- **先看计划（可选，规划模式的子开关）**：再开「先看计划」后，规划**只产出计划就暂停**，页面给出可交互的计划卡片（步骤清单 + 「执行计划」按钮），确认无误才开跑。执行复用的就是上面那条**断点续跑**通路——计划已作为 RUNNING 任务落库、步骤全 PENDING，所以后端没有第二套执行入口；计划明细同时也作为正文推过一遍，刷新后卡片消失但内容仍可回看。默认关闭（规划完直接执行，与改造前一致）。
- **规划模板（把「怎么排」沉淀成资产）**：跑顺的一次规划可在计划卡片上点「存为模板」，其**步骤骨架**（智能体 + 指令 + 依赖快照）即成为可复用资产；下次同类目标在输入区「模板」里选一个、填本次目标，就按骨架生成计划——**零模型调用**，省掉一次规划往返，生成后仍可用卡片上的「编辑」逐条微调再执行。模板**只存「怎么排」、不存「做什么」**（目标每次都不同，故套用时必填），也不含各步产出与任何运行态：存的是**快照 JSON** 而非引用 `task_step`（那张表会随局部重规划删改、重排行，引用式模板会被连带破坏）。按用户隔离，仅本人可见；套用同样只做「落库」，执行仍走断点续跑。
- **步骤审批点（让「跑一半停下来问你」成为能力）**：计划卡片上可给任意步骤勾选「需审批」，执行到该步**先暂停并等待批准**，页面弹出审批卡片（批准并继续 / 终止计划）。**关键是这套关卡复用现成通路**：批准后走的就是断点续跑（`POST /api/chat/task/resume`），没有第二套执行逻辑；终止走 `POST /api/chat/task/cancel`，把任务收成 `CANCELLED`。审批是「允不允许跑」、状态是「跑到哪了」，两者正交（`approval_required` / `approved` 两列独立于 `status`）。
- **执行中干预（暂停 / 跳过卡住的步骤）**：审批是「计划里就写好的关卡」，这两个是「跑起来之后才决定」的出口，都不能靠新建一套执行逻辑实现，故都只改库、由用户点「继续执行」走现成的断点续跑。
  - **暂停（`task.pause_requested`）**：请求置位后，执行循环在下一个**层边界**停止推进（任务仍是 RUNNING、剩余步骤仍是 PENDING），之后可改步 / 跳步再续跑。**它不是硬中断，回执刻意不说「已暂停」**——同一层是 `CompletableFuture` 并行 join，硬中断一个跑到一半的调用只会留下半截产出、还得从头再问；回执文案写「当前正在执行的那一层跑完后停止推进」，前端原样转述。暂停位由**续跑入口**负责清零（不清就会出现「点了继续、立刻又停」，且之后每轮一进去就停）。
  - **跳过（`PENDING`/`FAILED` → `SKIPPED`）**：没有这个出口，一个反复失败的步骤会把整个任务**永久卡死**——重试次数一旦用尽，执行侧只把它当作「前驱失败」，而后续依赖它的步骤永远凑不齐前驱、每一轮续跑都在同一处空转。前端在步骤重试用尽时于提示条上明说「第 N 步（某智能体）重试已用尽，不会自行恢复」并给出「跳过第 N 步」；执行中不显示跳过（先暂停再跳，后端也只收 `PENDING`/`FAILED`）。
  - 提示条改为返回**步骤明细**（`RunningTaskView`：每步的智能体名 / 状态 / 错误 / 是否重试用尽）而不只是「已完成 1/4 步」——用户看得到进度却不知道卡在哪、也没有可点的动作，那两句话等于没回答。
- **成本配额（按用户 × 自然日）**：可选的用量护栏 —— 打开后每轮对话开始前汇总「该用户今天花了多少 token」（`agent_trace` 回答侧 + `llm_usage` 裸调用侧，口径与成本看板一致），超限**明确返回 429 并说明已用/上限/何时重置**，接近上限（默认 90%）时在回复前推一条预警。ADMIN 默认豁免。**超限不许静默降级**（不换小模型、不截断历史、不假装成功）；统计出错则 fail-open 放行并落 WARN（配额是治理手段、不是正确性保障，宁可放过也不因统计故障挡住正常使用）。
- **并行评审（多智能体对同一问题并行作答 + 裁决）**：会话级 ⚖ 开关开启后，本轮先由 LLM 从智能体库里挑若干**候选**（会话已绑定智能体时它固定占一席，其余按问题类型选），各候选**互相不可见地**独立作答，再由裁决者综合成**一份**最终回答——答案是「综合」而非「挑一份」，从而减少单次作答的偏斜。候选与最终答案一起推给前端（候选走 `review` 事件、只作展示不落记忆），让「答案是怎么来的」可追溯。
  - **候选互不可见**：互相看得见就退化成串行接力（后答者会跟着前面的思路走），拿不到「多解」。**共用同一份检索素材**（RAG 命中 + 跨会话回忆只取一次）既控成本（否则 N 份检索），也保证「谁答得好」里不混进「谁拿到的资料多」。
  - **与规划互斥**：两者都是「编排形态」（规划决定**怎么拆**、评审决定**谁来答**），同时开启会让「本轮走哪条链路」取决于读取顺序。故开启任一方会自动关掉另一方（前端开关同步联动）。
  - **失败全部显式**：候选不足 2 个 → 播报后回落普通单智能体；只有 1 个成功 → 播报「仅 1 个候选成功作答，直接采用它的回答（未做综合）」；全部失败 → 明确回「并行评审失败」；裁决失败 → 播报后采用第 1 份。**没有一种情况会静默假装成功**。
  - 每次候选与裁决都是真实模型往返，按 `REVIEW` 用途计入成本看板。
- **跨会话搜索（回忆「你在别的会话里说过什么」）**：会话级 🔎 开关开启后，每轮先用 LLM 从本轮问题里抽出检索关键词，在**本人其他会话**的历史消息里做关键词召回（SQL 层强制按 `user_id` 过滤并排除当前会话），命中的片段连同会话标题、角色、时间一起注入上下文；前端同时展示「🔎 回忆到的历史」列表。**为什么是关键词而不是向量**：会话消息逐轮写入，走向量检索意味着每条消息落库都要多一次 embedding 调用并新增一套副本维护链路，而现有向量设施是围绕知识库块建的、共用同一空间会让「知识库命中」与「历史回忆」互相干扰。代价是**换个说法就召回不到**（字面匹配），这是明确接受的边界。降级不静默：未提取到关键词 / 未回忆起 / 回忆起 N 条 / 召回失败，四种情况都有明确播报。
  - **语义能力由两头补，不靠向量副本**：① **召回侧提词扩展** —— 抽词提示词要求产出 3~8 个词、分「原词」与「同义说法」两类（`平均分 → 均分 / 成绩 / 分数`），把「换个说法」在这次就翻译成字面；② **排序侧跨会话语义重排** —— 字面命中的候选再交由 `RerankService` 做 query↔片段的交叉编码打分，按 `agent.rag.rerank-min-score` 过滤并重编号（`agent.cross-session.semantic-rerank` 默认开）。这样「意思到了但用词不同」有机会被捞回来，而不必为每条消息存一份向量。
  - **仍然会漏的边界**（照实说）：措辞完全不同、且提词模型也没想到同义说法的片段，两端都够不着 —— 这是「不做向量副本」换来的成本，明确接受。
  - 重排不可用时按原字面顺序取 `k` 条（不是「取满 `recall` 条」）：有重排时能多给候选、靠重排筛；没重排时给再多也只是噪声，故候选数随重排可用性变化，而**阈值口径不变**。
- **内容安全护栏（可选的输入/输出侧拦截）**：`agent.safety.enabled=true` 后，输入侧在配额闸门之前按规则检查（超长或命中 → **明确 422 拒绝、零模型调用**），输出侧在推送与展示前检查（命中 → 替换为提示文案）。
  - **它不是「在提示词里写一句安全要求」**：那种做法把「守不守法」交给模型自己判断，拦不住明确不该出现的内容，也拿不到任何可观测的拦截记录。护栏是代码里的确定性判断。
  - **边界要说清**：规则是**正则字面匹配、不是语义审核** —— 换个说法、加个谐音就能绕过，且规则只进配置（改规则不改代码）。规则命中只记 WARN、**不落原文**（拦截记录本身不该成为敏感内容的新副本）。
  - **替换只作用于推送与展示**：普通对话的助手消息由记忆 Advisor 在模型返回时就已落库，输出侧护栏跑在它之后，因此库里仍是模型原始输出。要让落库也替换得把护栏下沉进 Advisor 改写 response（会牵动 token / 工具元数据重建），本版本刻意不做。
- **双层记忆**：短期窗口（`chat_message` 原文，受 token 预算与条数下限约束）+ 长期滚动摘要（`conversation.summary` / `core_facts`）；超窗历史异步压缩合并，**先推回复、后处理记忆**。
- **长期记忆可视化与编辑**：双层记忆此前是纯黑盒 —— 压缩由后端异步完成，用户既看不到「它记住了什么」，也无法纠正记错的内容。顶栏 🧠 记忆把摘要、长期事实（逐条）与旧版归档摊开可改，并给出「已压缩 N / M 条」的覆盖度（直接回答「它为什么还记着那么早的事」）。**水位 `summarized_count` 只读不可改**：它是「压缩到第几条」的执行游标，手改会让下次合并从错误位置继续；要重置得走「重置全部记忆」（三列一起归零、历史消息保留，下次超窗从头重新摘要）。
- **记忆注入透明化 + 单条禁用**：此前「这一轮到底往 prompt 里塞了哪些历史」只在追踪里看得到路由 / 工具 / token，记忆这一段是黑盒。现在两处可见：**记忆面板**多出「当前窗口（本轮会注入的历史）」逐条清单（角色 / 前 60 字预览 / **实际进上下文的字符数**），与摘要、长期事实一起回答「这次是靠原文记起来的、还是靠摘要记起来的」；**追踪弹窗**每轮多出「本轮注入记忆」块，给出窗口逐条 + 窗口 / 摘要 / 长期事实三段字符数（刻意分开计 —— 混成一个总数就答不了上面那个问题）。另有**单条消息开关**：某条消息可标「不参与记忆」，此后它既不进窗口、也不参与摘要，但**历史里仍然看得见**（「不进记忆」≠「删掉」，展示侧刻意不过滤）。
- **「改参与状态」会重置摘要游标（副作用明说）**：`summarized_count` 是「已压缩到第几条」的执行游标，而窗口可见构成刚被这次操作改变，游标不动就指向了错误位置 ⇒ 标记/取消标记时把**游标**归零。摘要与长期记忆的**内容保留**（否则用户会莫名丢掉「我是谁 / 我的偏好」），代价是下次超窗时重新整理一遍。前端在操作后 `alert` 明说这一点。
- **长期事实条目（逐条可看 / 可改 / 可删）**：长期记忆此前是一段由模型异步合并出来的长文本（`conversation.core_facts`），用户既无法逐条纠正，也看不出哪一条是「它自己整理的」、哪一条是「我明确告诉它的」。现在改为**逐条承载**（新表 `conversation_fact`）：记忆面板按主题分组列出每条事实，每条都带**来源标签**（手动 / 自动），可单独改、单独删、也可手动新增。**旧的归档文本保留不动**（面板里标为「旧版事实归档（不再自动更新）」），并在**首次合并时被当作输入拆成条目**——所以升级本身不丢任何记忆，也不需要迁移脚本。
  - **两条来源待遇不同**：**自动**条目每次合并**按 diff 重写**（不在新清单里的即视为过时删除，这是淘汰旧事实的唯一通路）；**手动**条目**合并绝不覆盖、绝不删除**。用户手改一条自动条目会让它**转成手动**（= 认领），此后自动合并再也动不了它——这才是「我纠正过的事，别再给我改回去」。
  - **条目的生命周期（置信度 / 有效期 / 被取代）**：一条事实不是「写进去就永远一样」，三列刻画它的当前效力。
    - **置信度 1~5**（`confidence`）：**不是模型打的分**，而是「这条被确认过几次」的可见化 —— 用户手写起始 5、模型整理起始 3，此后**每次合并里仍然被列出就 +1**（封顶 5）。于是最近还在被反复确认的条目自然浮到高分区，只被提过一次的臆测停在低位；面板按它排序，用户一眼看出哪条更可信。
    - **有效期**（`expires_at`，可空 = 永不过期）：这是「下周三要交报告」这类**有时限的事实**的归宿 —— 此前它们会一直躺在提示词里，成了模型眼中永远的待办。过期后**不再注入**，但**不自动删除**，仍在面板上标「已过期」等用户处理。**是否过期由服务端算好下发**（`expired` 字段）：前端不重复判断时钟，否则时区/时钟差会让同一条在两个地方得到不同结论。
    - **被取代**（`status` + `superseded_by`）：同主题出现新说法时，旧条目置 `SUPERSEDED` 并记下**被哪条取代**，不再注入但**留档可查**（面板上「被……取代」带回链）。这不等于删除 —— 语义变了（旧说法当时是对的）与写错了（该删）是两回事。
    - **有效期字段的「空值即清除」语义明说**：`PUT` 时传 `null` 表示**清除有效期**（重新永久有效），而不是「保持原值」—— 表单上「有效期」留空，用户的直观预期就是「不过期」。
  - **注入优先级**：只要还有条目，就注入条目；条目被删光才回退读旧归档。**手动条目永不被删**，重置记忆也只清自动条目。
  - 同一会话内按 `MD5(主题 + 事实)` 去重；手动新增撞上同一条已存在的自动条目时不报错，而是把它**认领**为手动（用户的意图就是「我要它留着」，与自动条目的诉求一致）。
- **线上回答自评（元认知）**：模型在答完之后**给自己这一轮打分**（1~5 分 + 是否答到问题 / 是否言之有据 / 问题短语 / 一句话说明），落进 `agent_trace`。**它回答的是「答得对不对」，与「跑得动吗 / 快不快」是两件事**，因此和链路追踪同源、同样只作旁路——自评失败只记 WARN，绝不影响对话。
  - **默认关闭，两条触发通路**：① **按比例抽检**（`agent.self-eval.sample-rate`，按 `traceId` 哈希采样，因此**可重现**——同一轮重跑结论一致，便于对账）；② **用户点踩强制自评**，**不看开关、不看采样率**（用户既然明确说这轮有问题，就不该因为"没抽到"而没有任何留档）。
  - **「未自评」与「评了低分」必须分得开**：对话页追踪详情里，无自评的那轮显式写「本轮未自评」并解释原因（而不是留白或显示 0 分——留白会被读成这轮答得很差）；可观测面板把「已自评 / 未自评 / 低分」三个数**并列**给出，且**低分率的分母是"已自评"而不是全量**——否则把采样率调低就会让指标自动变好看，那是指标自欺。
  - 「是否有依据」「是否答到问题」在明细读不出来时是**`null` 而不是 `false`**：「明细没采到」与「模型明确说没答到」是两件事。
- **定时 / 周期任务（让一句话按周期自动跑）**：顶栏 ⏰ 定时里建「一句话 + cron」，到点自动跑一轮，结果留在**承载会话**里、完成或失败都推一条通知。
  - **执行体就是一次普通对话**：以任务归属用户的身份、在承载会话里把 `prompt` 问一遍（走 `ChatService.chat`）。于是智能路由 / 规划 / RAG / 记忆 / 工具 / 追踪**全都原样继承** —— 定时任务自己不需要知道这些能力的存在。这不是取巧：任何「另起一套执行逻辑」的做法都会让定时任务的产出与手动问一次的结果不一致，而那种不一致极难排查。**承载会话在首次执行时自动创建**（标题 = 任务名）并回填，之后一直复用 —— 用户想回看「上次跑出什么」，去的是个有正常标题的会话，而不是一个隐藏对象。
  - **抢占式触发**（`next_run_at` 的 CAS）：先把 `next_run_at` 推进到下一次、**只有它仍等于读到的旧值时才更新成功**，抢到的线程才执行。这样多实例部署或某次执行超过轮询间隔时，同一时刻也只有一个执行者，且不会因「上一轮没跑完」重复触发。配 `max-per-tick`（单轮最多执行几个到期任务，防同一时刻堆积打满模型额度）。
  - **按用户隔离**：`/api/schedule` 只要求登录、不校验角色 —— 它是「自己的会话自己排期」，天然按 `user_id` 过滤（越权一律 404），与成本/可观测那类**跨会话全站视角**（限 ADMIN）不是一回事。
  - **执行失败不影响下一次**：失败只记 `lastStatus`/`lastResult` 并推通知（`next_run_at` 早已推进，不会卡死），不静默 —— 一个悄悄不再执行的任务比一个报错的任务更难发现。
  - **已知边界**：定时执行跑在调度线程上，**不经过** `ChatController` 的配额闸门（与「后台异步调用不被拦截」一致）—— 配额只保护交互式入口。
- **阈值告警 + 周期报告（把「要人主动去看」的指标变成主动推送）**：`AlertService` 定时聚合可观测面板的口径，越线就落一条 `ALERT` 通知、按周期落一条 `SYSTEM` 运行报告 —— 出口复用 P0 的通知铃铛，前端无需新增入口。
  - **为什么是轮询聚合而不是「每轮结束顺手判一次」**：判定的是**窗口聚合口径**（近 N 天的成功率 / 平均耗时），它没有单一触发时刻，本轮失败不构成「窗口质量不达标」；一轮一算既重复计算，又会让告警依据的数与面板上展示的数**不是同一个** —— 那是最坏的情况。
  - **三道防噪**（告警一旦变成噪声就等于没有告警）：`min-sample` 样本不足不判定（小流量下 1 次失败就是 33% 成功率）、`cooldown-minutes` 冷却期内同一项只报一次（指标是持续状态）、**空窗口不发报告**（天天推一条「0 轮」会训练人忽略通知）。
  - **收件人是全部 ADMIN**（跨会话全站聚合属运营信息）；一个 ADMIN 都查不到时改为广播并记 WARN —— 广播不完美，但比「告警没人收到」强，且日志里有明确线索。「没告警」必须能区分「指标正常」与「功能没跑」，所以收件人查不到 / 报告无数据一律记日志。
  - 判定与「按需自检」走**同一个 `run()` / `sendReport()`** —— 两条路径各写一份判定，迟早漂移成「自检说没问题、实际不告警」。调度整体吞异常：`@Scheduled` 任务抛异常会**静默停摆**（Spring 不重启已停的定时任务），那是最难发现的一类故障。
  - 每周期的判定口径与配置见 `application.yaml` 的 `agent.alert.*`；`@Scheduled` 的间隔 / cron 直接写在注解上（`${...}` 占位符），不入配置类。
- **流式输出**：SSE 推送 `token`（正文，进记忆）、`progress`（执行过程，不进记忆）、`citations`（引用来源）、`plan`（待确认计划，见「先看计划」）、`approval`（触到审批关卡，见「步骤审批点」）、`review`（并行评审候选，见上）、`recall`（跨会话回忆命中，见上）、`ping`（心跳）、`error` 等事件，前端逐字渲染。**前置链也有实时反馈**：发送后到首个 token 之间，路由判定 / 参数抽取 / 检索问句改写各环节都会先推一条 `progress`，不再是一片空白干等。`review` / `recall` 都排在正文之前：它们是「结论怎么来的 / 用了什么素材」，先给依据再给结论。
- **对话分支（编辑重发 / 重新生成不删历史）**：与 DeepSeek 一致的形态——对某一轮「重新生成」、或对某条用户消息「编辑重发」，**不删旧版本**，而是给这一轮**再开一个版本**；那条**提问**气泡上出现「n / m ‹ ›」版本切换器，点箭头原地翻看同一轮提问的多个版本（提问与其后的助手回复作为一个「分支组」一起换）。这与早前「先截断再重发」只差一个取舍：截断是「旧答案当场消失、原思路回不去」，分支是「旧版本留在库里可翻回」。**开新版本发生在消息确实落库之后**（流式之前只分组、算版本号，绝不提前失效旧版本）——否则附件失败 / 配额拦截 / 发送中断都会让那一轮凭空消失。
- **分支的落库口径**：`chat_message` 上用三列刻画 —— `turn_group_id`（同轮多版本共用，UUID）、`turn_version`（组内序号，从 1 连续递增，故前端可直接令 `versionCount = version`）、`turn_active`（当前生效版本，同组至多一个为 1）。**`turn_group_id IS NULL` 即「从未分叉」**，存量数据零回填、无需迁移。**读取侧四处共用同一过滤**（`turn_group_id IS NULL OR turn_active = 1`）：喂 prompt 的历史、记忆窗口、消息总数、摘要切片——任一处漏掉都会让「摘要水位按物理行数推进」与「注入侧按生效版本取」错位，裂出既不摘要也不注入的记忆空洞。新版本行还会**把 `created_at` 锚回该组首条的时间**，否则按 `created_at, id` 排序时它会掉到后面几轮之后。
- **切版本 / 开新版本会重置长期记忆水位**：注入模型的历史整段换了，旧摘要即失效（与「截断重发」同一语义）；切换只改 `turn_active`（先全灭同组、再点亮目标），不动作答内容。
- **会话导出**：顶栏 ⬇ 导出把当前会话导出为 Markdown（消息全文 + 附件文件名 + RAG 引用来源与相关度）。后端只回文本、由前端拼 Blob 下载 —— 下载必须带 `Authorization`，而浏览器对裸链接的导航请求带不上这个头，走文件通道只会 401。附件刻意只留文件名不留 URL：导出文件要自包含，指向本机 `/files/**` 的链接换台机器就是死链。
- **链路追踪**：每轮对话的路由来源、规划步骤、改写后检索问句、RAG 命中、工具调用（参数/结果/token/耗时）异步落库 `agent_trace`；页面 🔍 追踪弹窗按**每页 10 条**分页查看本会话最近 50 轮，**只显示自己名下会话的记录**（管理员可切到「全部会话」看全站）。
- **可观测面板（仅 ADMIN）**：跨会话聚合 `agent_trace` 回答「整体运行得怎么样」——成功率 / 平均耗时 / 平均 token、按天·按形态·按处理方来源·按智能体拆解、以及最慢的 N 轮（定位瓶颈）；另有一块**回答质量（模型自评）**：已自评 / 未自评 / 低分 / 平均分四张卡 + **低分轮次表**（分数升序，最差的先看；只列定位信息，明细点进对话页看），低分线由后端 `agent.self-eval.low-score-threshold` 下发、前端不写死。独立页 `/observability.html`，与成本看板（讲「花了多少钱」）互补：本面板讲「跑得多快、成不成、答得对不对」。接口与入口都限 ADMIN（跨会话全站聚合口径）。
- **成本看板（仅 ADMIN）**：全量成本口径——除「回答本身」（`agent_trace`）外，路由判定/参数抽取/查询改写/任务规划/视觉识别/记忆合并/跨会话召回（`RECALL`）/并行评审（`REVIEW`）这些裸 `ChatModel` 调用也各自记入 `llm_usage`（按用途 `purpose` 拆解）；页面 💰 成本弹窗按天趋势 + 按用途聚合展示（近 7/30/90 天）。接口与入口都限 ADMIN（全站聚合口径，按人拆分无意义）。

### 知识库与多模态

- **知识库 RAG（会话级纯开关 + 自动多库）**：会话开启「📚 RAG」后，每轮自动检索「通用知识库（`agent_id` 为空）＋路由/绑定智能体的专属库」，多库一次合并检索并注入编号上下文；关闭即完全不检索。三段式检索：**粗排召回 → 精排（DashScope text-rerank）→ 编号注入**，精排不可用时降级「向量分截断」。检索前会用最近若干轮历史做**多轮查询改写**（指代消解），首轮/未开 RAG 自动跳过。
- **混合检索（向量 + 关键词双路，RRF 融合）**：粗排阶段不再是单路向量 —— 同时跑**向量召回**（Chroma 优先、MySQL 余弦回退）与**关键词召回**（`LIKE` 多词项，按命中词数降序），再用 **RRF**（`1/(k+rank)` 按**名次**融合）收敛回 `recall-k` 条交精排。
  - **为什么用 RRF 而不是加权求和**：两路的分数**尺度不同**（向量是余弦 0~1 且高位拥挤，关键词是命中词数 0~N），加权需要离线标定系数、换个 embedding 模型就失效；RRF 只看名次，天然免疫尺度差异，`k=60` 让「两路都在中游」压过「一路单独第一」——**共识优先**。
  - **中文切词零依赖**：不引词典（保持「跑个 jar」就能用），按标点/字符类分段、汉字取相邻 2-gram 滑窗（`TermExtractor`）。跨词边界的噪声 gram（如「绩怎」）在真实文档里几乎不连续出现，因此不带来误召回。
  - **关键词路只在精排可用时参与**：RRF 分**不是相似度**，离开精排就没法与余弦阈值比对。精排不可用时 `refine` 兜底分支会**按余弦重排一次**，让「关键词专属候选（score=0）被阈值自然滤掉」—— 降级路径因此**精确退化为改造前的纯向量行为**，不因新增一路而变宽松。
  - 开关 `agent.rag.keyword-recall-enabled`（默认开）、词项数 `keyword-recall-k`、扫描上限 `keyword-recall-scan-cap`、`rrf-k`。截断扫描时会 WARN 并带上真实总数（不静默）。引用条目上会标 `matchedBy`（`vector` / `keyword` / `both`），前端对关键词命中显示「词命中」、双路命中显示「双路」徽章 —— 默认的纯向量命中不标记，免得每条都挂一个没有信息量的角标。
- **以「文件」为管理单元**：上传 → 解析 → 分片 → 向量化入库并登记（`kb_file`）；支持 4 种分片策略（fixed / paragraph / recursive / markdown）与重叠字数、**重新分片**（不重传换策略）、**同名重传=替换**。
- **向量存储双写**：MySQL 留档（源，向量以 JSON 文本存 `kb_chunk`）+ Chroma 加速副本。检索优先 Chroma（余弦 TopK），不可用/无命中自动降级 MySQL 余弦（有界扫描，防 OOM）；`POST /api/kb/chroma/sync` 幂等回填副本。Chroma 写入统一延后到**事务提交之后**，避免 MySQL 回滚后副本失配。
- **多模态图片理解**：输入框 🖼 支持多选图片（≤5 张 / 单张 ≤10MB），由视觉模型（默认 `qwen-image-2.0-pro-2026-06-22`，可配）识别成中文 caption 拼入本轮上下文；走 Spring AI 原生多模态（裸 `ChatModel` + `UserMessage.media`，per-request 覆盖模型、多图并发识别）。**原始二进制不进会话存储**——caption 仅当轮可见。
- **引用回链（从角标一路点回原文）**：回答正文里的 `[n]` 是**可点击角标**，点一下会展开该轮的「引用来源」并高亮第 n 条；来源条目上的「原文」再进一步，按 `chunkId` 拉出**被引用的那段知识块正文**。此前只有「库名 · 文件名 · 相关度」，用户无法判断一句话是文档里写的还是模型编的。取块失败（块被删或重新分片，这在几个月前的老引用上很正常）**明确 404 并说明原因**，不返回空白块——空白块会被读成「文档里本来就是空的」。角标处理只改正文文本段，`<pre>`/`<code>`/`<a>` 内的 `[n]`（代码、数组下标、链接文字）与图表 JSON 一律不动。
- **文档解析**：附件与知识库支持 txt / md / markdown / csv / json / xml / yml / properties / log / sql 文本，以及 pdf（PDFBox）、docx / xlsx（POI）。
- **知识库图片入库（多模态）**：知识库上传遇图片（png / jpg / jpeg / gif / webp / bmp / tif / tiff / heic / avif）时，先由视觉模型识别成中文文字描述、再按普通文本切块入库 —— 让「一张图里的信息」也能被 RAG 检索到。**图片判据单例**（`VisionService.isSupportedImage`，对话附件与知识库共用同一份 MIME / 扩展名规则，并**排除 svg**），杜绝「同一张 png 在附件里能识别、在知识库里被拒」。**失败语义与对话附件刻意不同**：对话附件单图失败降级为占位文本（一张图没认出来不该让整轮对话失败），知识库入库失败则**直接让该文件失败**——把「[图片识别失败：超时]」当知识块存进去，等于往检索结果里灌噪声，比报错糟得多。

### 工具调用

- **全局能力池**：天气查询、日期解析、SQL 安全查询、表结构查看、样例数据、SQL 预检、ECharts 图表、文本直方图。
- **智能体转交（`call_agent`）**：把一个子任务转交给另一个更合适的智能体执行 —— 让「多智能体协作」不再只有规划模式一条路，普通对话里也能用。工具描述里动态列出当前可转交的智能体清单（每轮现查，新建/删除智能体立刻生效）；转交是黑盒，主智能体只拿到子智能体的最终产出。
  - **白名单专属**：`tools_json` 为 `NULL`（=挂全量）时**不会**带上它 —— 转交是策略性能力，让翻译/闲聊类智能体凭空获得「可以把活推给别人」的选项容易被误用，且会一次性改变所有既有智能体的行为。要用就在`tools_json` 里显式写 `["call_agent", ...]`。
  - **只做一层**：子智能体执行时挂的是它自己的静态工具，不含 `call_agent`，因此「A 转给 B、B 再转给 C」不会发生，天然无递归与调用环。代价是子智能体不能继续向下转交。
  - 每次转交是一次真实模型往返，按 `SUBAGENT` 用途计入成本看板。
- **智能体转交（`handoff_agent`）与 `call_agent` 的分工**：两者都叫「把活交给别人」，但**调用方期待的返回不一样** —— `call_agent` 要**子智能体的产出**（黑盒，主智能体拿到最终结果继续往下答），`handoff_agent` 是**把这一轮整个让出去**（主智能体不再作答，由目标智能体直接面对用户）。混用一个工具会让模型在两件事之间摇摆：它既要「拿结果回来」又想「甩给别人」，最终往往给出一段既不是回答也不是转交的糊弄话。
  - **同样是动态工具**（候选随库变化、实例依赖调用方），同样**白名单专属**（`NULL` 全量下不挂）；`handoff_agent` 还会在会话上写一条**转交记录**（走 `agent_trace`），这样「这轮到底是谁答的」在追踪里查得到。
- **工具审批闸门（让「要不要执行这个工具」变成一次要用户点头的请求）**：开 `agent.tool-approval.enabled` 并列出需审批的工具名后，这些工具的调用在**执行前**被拦下并落一条 `PENDING` 待确认记录，同时在对话里说明「这次调用需要你确认」。用户在审批列表点「批准并执行」后，用**既有的「重新生成」通路**重跑同一轮 —— 那时闸门放行、工具真正执行。**批准是带时效的**（`expire-minutes`，默认 30 分钟）：过期的 `APPROVED` 由后端折算回 `PENDING` 重新确认。
  - **形态是「拦截 + 落记录」，不是「挂起等按钮」**：工具调用发生在 Spring AI 内部、SSE 早已建立，**没有「中途暂停等人点按钮」的位置**。硬造一个挂起点意味着要在模型调用链里塞一个等待原语，并承担「用户一直不点怎么办 / 连接断了怎么办」这一整套状态。拦截式没有这些问题，代价是用户批准后要重跑一轮（不是从半路续上）—— 这条链路**不新增任何发送逻辑**，也**不假装能从半路续上**。
  - **绝不向模型抛异常**：工具结果里冒异常会打断整轮，用户看到的是 500 而不是「需要确认」。闸门自身故障时返回一段可读说明（**fail-closed**：宁可挡住并让用户知道，也不静默放行 —— 静默放行会让闸门变成摆设）。
  - **用装饰器统一包一层，不改各工具**：工具一批来自 `@Tool` 反射、一批来自 MCP（构造期才拿到实例），逐个改就得每个都记得「先查审批」，**漏一个就是静默敞口**。故统一在装配点包一层，覆盖注解式、动态式与将来新增的一切工具（`ToolCallback` 的两个 `call` 重载都要覆写 —— 只覆写单参会**不报错、只失效**）。
  - **拒绝的效力范围明说**：拒绝后**本会话内一直挡住**（不是只挡这一次），要恢复得在审批列表里「撤销拒绝」—— 否则模型下一轮照调不误，用户会以为拒绝没生效。
  - **展示状态由后端折算**（`effective`），前端不自己判过期：库里的 `status` 是「当时决定过什么」，`effective` 才是「现在算不算数」，两者分开才能既留档又正确。列表接口只要求登录、按会话隔离 —— 它是「我自己会话里的工具放不放行」，与跨会话运营视角（限 ADMIN）不是一回事。
- **有界工具循环**：Spring AI 默认的「模型调工具」循环是无上限的，模型反复调同一个工具会死循环拖垮 token。已用自定义 Advisor 装上三道刹车——**轮数上限**（默认 10 轮，覆盖「查表→查数→画图」合理长链）+ **连续重复检测**（默认连续 3 次同名同参即停）+ **单轮 token 预算**（`round-budget-tokens`，默认 0 = 不启用；读本轮累计用量，规划模式下跨步骤累加）。前两道拦的是「死循环」，第三道拦的是「每一步都合规、合起来烧穿一轮」——多步规划 + 长工具链可以完全绕过前两道而不违反其中任何一条；它与成本配额（用户 × 自然日）是两个维度：配额拦「一天烧太多」，这里拦「一轮烧太多」。三道都走**软刹车**（追加「基于已有信息作答」指令，不抛错中断），因为硬中断会把这一轮已花掉的调用结果全丢掉、还得从头再问（且下一轮仍撞同一个上限），代价是**它不是硬上限**（刹车后仍会多一次模型调用，规划模式下每个被刹住的步骤各一次）。触发写 WARN 日志（含 `traceId`，可在服务端日志检索整轮链路）并经进度通道播报，不静默。配置见 `application.yaml` 的 `agent.tool-call.*`。
- **按智能体装配**：`agent.tools_json` 控制白名单 —— `NULL`/空 = 挂全量、`[]` = 不挂、`["名"]` = 白名单（按 `@Tool` 名匹配，未指定 name 时即方法名）。未知名忽略、非法 JSON 回退全量。唯一例外是 `call_agent`：它是**动态工具**（实例依赖「调用方是谁」，候选清单随库变化，无法在启动期注册），只认白名单显式声明，`NULL` 全量下不挂。
- **安全护栏**：SQL 工具仅允许只读 `SELECT`/`WITH`，白名单表名（`SqlSafety.ALLOWED_TABLES` 的 10 张业务表：student / class / teacher / subject / course / score / semester / exam / period / course_arrangement）、拒绝多语句与可执行注释、结果行数上限。业务表与 agent 系统表**同库**，故必须用白名单**显式放行**（黑名单挡不住新表），`conversation`/`chat_message`/`agent` 等系统表一律不可读。
- **MCP 远端工具**：官方 `spring-ai-starter-mcp-client` 接入的 MCP server 工具经 `McpToolSource` 收进**同一个能力池**（前端分组显示为 `MCP`），与本地工具一样按 `tools_json` 装配。默认**不声明任何 server**，即「不接入」——启动行为与未引入 MCP 时一致。
  接一个 server：在 `application-local.yaml` 写 `spring.ai.mcp.client.stdio.connections.<名>`，`command` 用可执行文件绝对路径、`args` 首项为 server 入口、其后为允许读写的沙箱根目录（**不要用 `npx`**，Windows 上是批处理包装、且依赖 PATH 与网络）。工具名以 server 返回为准，看 `GET /api/agent/tools` 的 `MCP` 分组。

### 提示词回归评测

- **改提示词前后各跑一批**：`prompts.yaml` 里 17 个模板（路由判定、参数抽取、查询改写、规划、评审、自评、跨会话提词…）都是 LLM 行为契约，改一个词可能悄悄修好 A、弄坏 B。评测把这层「跑批 + 断言」补齐 —— 用例集声明「什么输入应得什么结果」，跑批走真实链路，逐条给通过 / 失败 / 配置错误。
- **改动自动门禁（改了提示词，别指望自己记得跑批）**：启动时对 `prompts.yaml` 取**内容指纹**（SHA-256），与上次记录比对；变了就按最近一批评测给出**判定并推通知**，顶栏/评测页出横幅。
  - **两个维度一起看**：`verdict`（那一批评测的结论）与 `verified`（**这批结论对应的指纹，和当前文件是不是同一个**）。指纹变了但还没跑批时 `verified=false` —— 此时**哪怕上一批是 `PASS` 也按「未验证」渲染**。把它并进 `PASS` 会让「改了没验」被读成「验过了没问题」，那是门禁最不该给出的错觉。
  - `verdict` 五态：`PENDING`（变更后尚未跑批）/ `PASS` / `DEGRADED`（相对上一批出现**新增失败**）/ `STILL_FAILED`（无新增失败，但仍有未通过的用例）/ `ERROR`（跑批或读指纹本身失败）。`STILL_FAILED` 单独一态：它比 `DEGRADED` 好（没有变差）、又比 `PASS` 差（还有红的），混进任一方都会给出错误信号。
  - **为什么默认「只提示不自动跑批」**：跑批是**真实模型调用**（花钱、有耗时，13 条用例 = 13 次 LLM 请求），在启动路径上自动发起会让「起个服务」变成一件有成本的事。门禁的职责是**把「你改了契约但没验证」这件事显式化**，不是替你花钱。
  - 指纹只记录**内容**（不看 mtime / 文件大小）—— 改回原样应视为「没变」；判定失败一律落 `ERROR` 而不是假装 `PASS`（静默放行的门禁等于没有门禁）。
- **三态不混算**：**失败** = 提示词质量问题；**配置错误** = 用例自己写错（例如断言引用了不存在的智能体编码）—— 单列一档，否则用例维护失误会被误读成「模型变笨了」。
- **批次对比看 broken**：每批落库，`/api/eval/compare` 直接给出 `fixed`（上批挂→本批过）与 `broken`（**上批过→本批挂**）。改完提示词先看 `broken` 有没有变长，比看总通过率更能定位回归。
- **零侵入**：不碰对话链路 —— 评测复用现成的路由 / 规划能力，只是换个入口调用并断言结果。用例集是 `classpath:eval-cases.yaml`，加用例不改代码。
- **仅 ADMIN**：`/api/eval/**` 整类带 `@RequireRole(ADMIN)` —— 「跑一批」发起的是**真实模型调用**（13 条用例 = 13 次 LLM 请求），消耗计入 `llm_usage` 成本流水，与成本看板同一性质。只读的 `/cases`、`/batches`、`/compare` 本可单独放宽，但它们只服务于「跑批」这一件事，没有独立使用场景，故整类收敛；前端顶栏入口按同一角色显隐。
- **消息反馈 → 回归用例（把线上翻车变成可重复跑的断言）**：助手回复上点 👎 并选问题分类（答非所问 / 编造内容 / 路由或规划不对 / 其他）+ 备注，必要时一键「保存为回归用例」。**反馈的价值不在「记录」，而在「可复用」**——一条躺在表里的点踩只是留档，转成断言之后它才会在下次改提示词时替用户把问题再问一遍。
  - **一人对一条消息一票**（唯一键 `message_id + user_id`）：改主意是**改票**（原地覆盖）而不是追加历史——否则「先踩后赞」会在库里留下两条互相矛盾的记录，转用例时不知该信哪条。点赞会**清掉问题分类**，不留「点赞 + 编造」这种自相矛盾的组合。
  - **能自动断言什么，必须说清（别高估）**：转出的用例只预填**可确定的部分**——那一轮实际路由到了哪个智能体 / 计划里包含哪几个智能体（按 `conversation_id + user_message` 匹配最近的 `agent_trace` 还原）。所以选「路由或规划不对」生成的用例**有真实断言价值**（把「这条输入以后不该再走那条路」钉住）；选「答非所问 / 编造」生成的用例天然**钉不住答案质量**，用户写的备注全程只作**人工排查线索**。理由与评测的既有立场一致：判答案好坏得让 LLM 当裁判，裁判自身不稳定，回归结果会失去可比性。
  - **yaml 是只读种子，库内表承接增量**：`eval-cases.yaml` 打包进 jar 后运行时写不了，故新增 `eval_case` 表承接运行时用例；`/api/eval/cases` 返回两者**合并后**的视图，**同名以 yaml 为准**（种子是人工审校过的，不该被一条自动记录静默顶掉），`/api/eval/cases/db` 只看库内增量。
  - 匹配不到追踪记录时**明确报错**而不是生成一条没有断言的用例——后者每次跑批都会红，用的人很快就学会无视它。转用例限 ADMIN（库内用例是全局资产，且只有能跑批的人才能验证它）。

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
  pii:    {enabled: true}
           # 数据侧脱敏：chat_message 正文 / agent_trace 留档 / 应用日志 三处同开同关，默认开启。
           # 单一开关是刻意的 —— feedback.user_input 要逐字匹配 agent_trace.user_message，
           # 两处可分别开关会让该匹配静默失效。关掉 = 明文落库，好处是模型此后能读到完整号码。
  review: {candidates: 3, max-candidates: 5, timeout-seconds: 90}
           # 并行评审：每轮候选智能体个数（下限 2）、配置上限、单候选超时（超时按弃权处理）。
           # 候选越多越贵（N 次模型调用 + 1 次裁决），开启评审前先掂量。
  cross-session: {enabled: true, recall-limit: 50, top-k: 3, max-keywords: 8, snippet-chars: 300, semantic-rerank: true}
           # 跨会话搜索：全局开关 + SQL 候选上限 + 注入条数 + 提词关键词数上限 + 片段截断字数 + 语义重排。
           # enabled=false 时该功能整体短路（会话开关拨了也不生效，零模型调用、零 SQL）。
           # max-keywords 含「同义说法」，故比纯原词提词时要多；semantic-rerank 复用 agent.rag.rerank-min-score 作阈值。
  rag:    {rerank-enabled: true, recall-k: 20, top-k: 3, rerank-min-score: 0.20,
           min-score: 0.25, recall-min-score: 0.10, fallback-max-chunks: 2000,
           query-rewrite-enabled: true, query-rewrite-history-size: 6,
           keyword-recall-enabled: true, keyword-recall-k: 20, keyword-recall-scan-cap: 2000, rrf-k: 60}
           # keyword-recall-*：混合检索的关键词一路（扫描上限有界，防 TEXT 列无界扫描）；
           # rrf-k：RRF 常数，越大越强调「两路都命中」的共识。关键词一路只在精排可用时参与。
  prompt-gate: {enabled: true, auto-run: false, fail-on-broken: true}
           # 提示词改动门禁：比对 prompts.yaml 内容指纹并绑定最近一批评测结论。
           # auto-run=false 是刻意的 —— 跑批是真实模型调用，不该让「起服务」变成有成本的事。
  schedule: {enabled: true, poll-interval-ms: 30000, max-per-tick: 3, max-result-chars: 500}
           # 定时任务调度器：轮询间隔决定「最坏晚多久执行」；单轮最多执行几个到期任务，防同一时刻堆积打满额度。
  notify: {webhook-url: "", connect-timeout-ms: 3000, read-timeout-ms: 5000, min-level: WARN}
           # 通知外发：webhook-url 为空=只落库不外发；min-level 是外发门槛（INFO 全量外发会把群刷爆，等于把告警变噪声）。
  tool-approval: {enabled: false, tools: [], expire-minutes: 30}
           # 工具审批闸门：enabled 关闭时连一次额外查询都没有；tools 是需审批的工具名清单（如 [query, call_agent]）；
           # expire-minutes 只作用于「已批准」（0=永不过期），「已拒绝」一票到底。
  alert:  {enabled: true, window-days: 1, min-sample: 10, min-success-rate: 0.90, max-avg-elapsed-ms: 15000,
           max-low-score-count: 5, cooldown-minutes: 60, report-enabled: true, report-days: 1}
           # 阈值告警 + 周期报告：看「现在怎么样」而不是「这周平均怎么样」，故 window-days 默认 1。
           # min-sample 防「1 次失败=33%」；cooldown-minutes 防持续状态每题重报；空窗口不发报告。
           # check-interval-ms / report-cron 由 @Scheduled 占位符直接读取，不进配置类（见 application.yaml）。
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
    # secret 在这里**不写**（连空占位符都不留）：内置默认值 = 把签名密钥随源码分发，
    # 拿到源码的人可离线伪造任意账号（含 ADMIN）的 token。来源二选一：
    # 环境变量 JWT_SECRET（openssl rand -hex 32）或 application-local.yaml 的 app.jwt.secret。
    # 两处都没配（或不足 32 字节）→ 启动生成随机密钥并打 WARN，重启后已签发 token 全失效。
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
  - **权限模型**：需 `ADMIN` 角色的接口用 `@RequireRole(SysRoleCode.ADMIN)` 声明（注解紧贴接口/控制器，不存在一份与代码脱节的路径清单），不通过返回 403 `ROLE_DENIED`。当前共**六处**：`/api/user/**`、`/api/role/**`（用户与角色管理）、`/api/audit/**`（操作审计）、`/api/cost/**`（成本看板）、`/api/eval/**`（提示词评测）与 `/api/observability/**`（可观测面板）。判据是「**有无独立使用场景 / 是否跨会话聚合**」而不是「花不花钱」：成本看板与可观测面板是跨会话全站聚合口径（按人拆分无意义）；评测「跑一批」会发起真实模型调用、消耗计入成本流水；操作审计记的是全站管理动作、本身就是安全留档，给任意登录用户看等于把「谁改过权限」公开。其余接口只要求已登录。
  - **数据侧安全（PII 脱敏 + 只增不改的审计）**：两件事一起做才算完整 —— 一边不给敏感信息造新副本，一边让越权改动留得下痕迹。
    - **`PiiMasker` 识别并遮住手机号 / 身份证 / 邮箱 / 银行卡的中段**（保留首尾，**替换不改变文本长度**：日志里的偏移量与截断长度都不受影响；没命中时**原样返回同一实例**；**幂等**——遮过的文本再遮一次不会二次变化，所以可以对「来源不确定是否已脱敏」的字段无条件再过一遍）。**别高估它的能力**：它是**正则字面匹配**，不识别姓名 / 地址 / 护照 / 车牌（中文姓名与地址没有可靠正则，硬写会大面积误伤），也不做语义判断。
    - **脱敏发生在「持久化边界」，覆盖九处落库 + 一个日志出口**：① `chat_message` 消息正文（`saveMessages` / `saveClarifyExchange`，含自动生成的会话标题）；② `agent_trace` 追踪留档（`plan_json` / `tool_calls` 的 args/result 走结构化脱敏，`citations_json` **刻意不遮** —— 引用片段来自知识库文档，属企业自有资料）；③ `audit_log.detail`；④ `conversation.summary` 与 `core_facts`（**滚动摘要没有删除入口、且每轮注入 prompt**，用户手写与模型复述都要遮）；⑤ `tool_approval.user_message` + `input_json`（**经前端审批卡片原样回显**）；⑥ `notification.title` + `content`（**唯一出站到本项目控制范围之外**的通道，webhook 会外发钉钉 / 企微）；⑦ `conversation_fact` 的手动新增与自动合并两条路径（hash 用遮后的文本，不破坏「同内容同 hash」的去重语义）；⑧ `clarify_state`（触发追问的原始请求）；⑨ 转交覆写的助手消息（走 update 直写、绕过消息落库入口，不遮就会留下一条明文并破坏「该表正文一律已遮」这个不变量）。**为什么不在「收到输入的那一刻」就遮**：工具调用、检索、本轮 prompt 都要用原文，提前遮会让模型这一轮就看不到号码。
    - **JSON 列必须走「结构化脱敏」（`PiiJsonMasker`），不能整串替换**：整串遮会让 PII 落在数字值位置时产出无引号串，JSON 非法、追踪面板直接解析失败。两个容易踩的坑：Hutool 的 `JSONUtil.parse` 会**静默丢弃所有 JSON null**（`{"a":null,"b":1}` 变 `{"b":1}`、数组里的 null 元素被删后下标整体前移），所以对象用 `parseObj`；Hutool 里 JSON null 读出来是 `JSONNull` 实例且它的 `toString()` 就是字符串 `"null"`，不单独处理会被当成普通文本脱敏、存回成字符串 `"null"` —— 把 null 变成了文本。
    - **唯一的运行期副作用**：`chat_message` 遮过之后会被 `DbChatMemory` 读回、注入**下一轮**上下文 —— 模型此后只看到 `138****8000`。工具调用与检索都在脱敏**之前**执行完毕，不受影响；追踪只用于展示与排查，也不受影响。这是刻意的取舍：**若业务需要模型在后续轮次复述完整号码**，把 `agent.pii.enabled` 关掉即可（代价是上述各处明文落库）。
    - **全部接入点共用一个开关 `agent.pii.enabled`（默认 true），不提供分别开关**：因为 `feedback.user_input`（取自 `chat_message`）要**逐字匹配** `agent_trace.user_message`，两处一旦可分别开关，该匹配会**静默失效**（表现为「反馈转用例偶尔失败」，日志只留一句 WARN，极难归因）。一致性由结构保证，而不是靠人记住。
    - **仍未覆盖**（别以为比实际更强）：知识库文档及其 Chroma 向量副本（`kb_chunk`、`kb_file` 的解析原文）、上传的附件文件，以及 `sys_user` 这类业务数据本身的字段（要看原值去用户列表）。`llm_usage` 只记 token 计数与模型名、无正文，无泄漏面。
    - **审计表 `detail` 在写入前过一遍 `PiiMasker`**：审计把「被改的东西」抄了一份留档，原样抄会让审计表自己变成敏感信息的新聚集地。**副作用要说清楚**：脱敏按「内容长什么样」判定、不区分字段，所以用户资料里的邮箱也会被遮（`zh******@example.com`）—— 要看原值去用户列表，审计只负责说明「改过什么」。**口令绝不进明细**（只记「更新口令：xxx」）。
    - **审计只增不改**：应用**没有**更新 / 删除审计的接口，清理只能由 DBA 直接操作数据库 —— 一个能通过接口删审计的系统等于没有审计。
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
│   ├── config/         # CorsConfig / GlobalExceptionHandler / MybatisPlusConfig(分页插件) / SchedulingConfig(定时任务线程池)
│   ├── result/         # RestResult / PageResult（统一响应与分页契约）
│   ├── util/           # PiiMasker(识别与遮中段，纯函数、幂等) / PiiJsonMasker(结构化，只替换字符串值) / TextClip(截断，标记算进预算)
│   ├── log/            # PiiMaskingConverter（logback %pii 转换符：应用日志脱敏）
│   ├── BaseBO          # 分页入参基类：缺省 10 条、上限 100
│   └── exception/      # AiBusinessException / AiErrorCode
├── ai/                 # AI 多智能体平台
│   ├── controller/     # ChatController(SSE) / ConversationController / AgentController
│   │                   # / KnowledgeBaseController / AttachmentController / TraceController
│   │                   # / NotificationController(/api/notification) / ScheduledTaskController(/api/schedule)
│   │                   # / ToolApprovalController(/api/tool-approval) —— 后三个只要求登录（按用户/会话隔离）
│   ├── service/        # ChatService(编排门面) / ConversationService / AgentService
│   │                   # / KbService / KbSearchService / ChunkingService / QuotaService(成本配额)
│   │                   # / CrossSessionSearchService(跨会话召回) / ContentSafetyService(内容护栏)
│   │                   # / NotificationService(通知出口) / ScheduledTaskService(定时任务)
│   │                   # / ToolApprovalService(审批闸门) / PromptGateService(提示词门禁)
│   │                   # / AlertService(阈值告警+周期报告) / ObservabilityService / SelfEvalService
│   ├── agent/          # AgentRouter(智能路由) / ParamFillingService / PlannerService
│   │                   # / MemoryMergeService / PromptService / QueryRewriteService
│   │   └── handler/    # RoundHandler + AgentRoundHandler / PlannerRoundHandler
│   │                   # / ReviewRoundHandler(并行评审) / RoundResult
│   ├── chat/           # ChatComposer(请求装配：人设/记忆/材料/工具/RAG)
│   ├── advisor/        # ToolUsageLoggingAdvisor / RoundTraceAdvisor
│   ├── tool/           # ToolRegistry / ToolProvider(注解式) / ToolCallbackSource(动态工具接缝)
│   │                   # / McpToolSource(MCP) / SubAgentTool(call_agent) / HandoffTool(handoff_agent)
│   │                   # / ToolApprovalGate(工具审批闸门) / WeatherTools / DateResolver
│   │                   # / SqlQueryTool / SqlSafety / SqlSchemaTool / ChartTool
│   ├── util/           # Rrf(按名次融合两路召回) / TermExtractor(中文 2-gram 切词)
│   ├── memory/         # DbChatMemory(Spring AI ChatMemory 的 DB 实现)
│   ├── trace/          # RoundTrace / TraceService(异步落库) / LlmUsageService
│   ├── infrastructure/ # attachment / chroma(客户端+副本同步) / document / rerank / vision
│   ├── config/         # ExecutorConfig(线程池) / ChatMemoryConfig / ToolCallingConfig 等 AI 专用
│   ├── properties/     # 全部 @ConfigurationProperties（19 个；由主类 @ConfigurationPropertiesScan 按包自动注册）：
│   │                   # MemoryProperties / RagProperties / VisionProperties / PromptProperties / QuotaProperties
│   │                   # / SafetyProperties / ReviewProperties / CrossSessionProperties / ToolCallProperties
│   │                   # / PlannerProperties / EvalProperties / SelfEvalProperties
│   │                   # / PromptGateProperties / ScheduleProperties / NotifyProperties
│   │                   # / ToolApprovalProperties / AlertProperties
│   └── entity/ mapper/ dto/ enums/ constant/
├── system/             # 用户管理系统（登录鉴权 + 用户/角色 CRUD + 操作审计）
│   ├── security/       # JwtTokenService(签发/解析) / JwtAuthInterceptor(拦 /api/**)
│   │                   # / AuthContext(当前用户) / @RequireRole / PasswordHasher(BCrypt)
│   ├── controller/     # AuthController(/api/auth) / SysUserController(/api/user)
│   │                   # / SysRoleController(/api/role) / AuditController(/api/audit) —— 后三个整个控制器限 ADMIN
│   ├── service/        # AuthService / SysUserService / SysRoleService / AuditService（+ impl）
│   ├── entity/ mapper/ dto/ vo/ constant/   # AuditLog / AuditAction（动作编码常量）
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
├── prompts.yaml         # 全部提示词模板（agent.prompt.*）；内容指纹即「提示词门禁」的比对对象
├── logback-spring.xml   # 唯一改动：官方默认 pattern 上把 %m 与异常栈用 %pii() 包一层（日志脱敏）
├── sql/schema.sql       # 建表（对话/知识库/智能体/规划/评测/通知/定时/审批/门禁/审计，幂等）
├── sql/alter.sql        # 存量库补列（幂等；**新建的表也要写进来**，存量库不会重跑 schema.sql）
├── sql/system.sql       # 用户/角色表 + 初始角色（幂等）
├── mapper/edu/*.xml     # 教务关联查询 SQL（namespace 对应 edu.mapper）
├── mapper/ai/*.xml      # AI 侧显式 SQL：知识块关键词召回 / 工具审批 / 追踪 / 成本 / 可观测 / 评测 / 跨会话召回（禁 @Select 注解，一律落 XML）
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
| 参数追问 | `paramSchema` 声明参数；LLM 从有界历史抽取已确认取值；缺失必填生成 `🔎 还需补充信息`，上限 `ClarifyState.MAX_ASKED`（3 次，常量唯一来源，追问文案与前端展示共用）。**状态显式落库** `conversation.clarify_state`（`{agentId,asked,request,question,params}`），历史重放降级为兜底（老会话无该列时行为不回退）。参数累积语义：**已落库快照为底、本轮新抽取覆盖**（用户中途改口新值胜出）；`belongsTo(agentId)` 作「换智能体即作废」判据，`unbindAgent` / `clearMessages` 一并清列 |
| 记忆体系 | 窗口 = 原文预算 + **条数下限**（防单条超预算导致窗口塌缩）+ 单条截断；`DbChatMemory` 与 `MemoryMergeService` 共用同一 `MemoryProperties` 口径。**记忆可视化编辑**（`GET/PUT/DELETE /api/chat/conversation/{id}/memory`）：可查看与订正 `summary` / `core_facts`；手改**只覆盖内容列、不动水位**（`summarized_count` 是执行游标不是展示字段，跟着手改会让下次自动压缩从错位继续），要回退水位只能走「重置全部记忆」（三列归零、消息保留）。**窗口构成与注入清单同源**：窗口切分只有 `DbChatMemory.snapshot(recent, props)` 一处实现，真实注入（`get`）与观测（`MemoryViewService`）都调它 —— 各写一份必然漂移，而错误的透明化比黑盒更糟。**单条禁用**（`PUT /api/chat/message/{id}/memory-excluded`）：`memory_excluded=1` 的条目不进记忆侧三处查询，但展示侧照常可见；改参与状态会**重置摘要游标、保留摘要内容**（可见序列变了、覆盖范围要重算，但内容清了用户会莫名丢事实） |
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
| 混合检索 | `KbSearchService` 粗排改双路：向量（Chroma 优先 / MySQL 余弦回退）+ 关键词（`KnowledgeChunkMapper.searchByKeyword`，多词项 `LIKE`，`ORDER BY 命中数 DESC`），再 `Rrf.fuse` 按**名次**融合（`1/(k+rank)`，k 默认 60）收敛到 `recall-k`。**选 RRF 不选加权求和**：两路分数尺度不同（余弦 0~1 高位拥挤 vs 命中词数 0~N），加权要离线标定且换 embedding 模型即失效；RRF 免疫尺度，`k=60` 让「两路都在中游」压过「一路单独第一」。**关键词路只在精排可用时参与**：RRF 分不是相似度，离开精排没法和余弦阈值比；精排不可用时兜底分支按余弦重排一次，使降级路径**精确退化为改造前的纯向量行为**。中文切词用 `TermExtractor` 做 2-gram 滑窗（零词典依赖） |
| 事实生命周期 | `conversation_fact` 用三列刻画「这条现在还起不起作用」：`confidence`（1~5，用户手写起始 5 / 合并起始 3，**每次合并里仍被列出就 +1** 封顶 5 —— 是「被确认过几次」而非模型打分）、`expires_at`（过期**不再注入但不自动删**）、`status` + `superseded_by`（`SUPERSEDED` = 被同主题新说法取代，不注入但留档可回溯源）。`expired` 由**服务端算好**下发（前端自己判时钟，时区/时钟差会让同一条在两处结论不同）；`PUT` 传 `expiresAt=null` 是**清除有效期**（重新永久有效）而不是保持原值 |
| 工具审批闸门 | `ToolApprovalGate` 是**装饰器**：在装配点给名单内的工具包一层（`ChatComposer` 挂工具那一刻），覆盖注解式 / 动态式 / 将来新增的一切工具 —— 逐个改工具就得每个都记得「先查审批」，漏一个就是静默敞口。形态是**拦截 + 落记录**（不是挂起：工具调用跑在模型内部，没有「中途等人点按钮」的位置）：命中就给模型一句可读的话让它收口，并落 `tool_approval`；用户批准后用**既有的「重新生成」通路**重跑同一轮，那时放行。**批准带时效**（`decided_at + expire-minutes`），过期由后端**折算**成 `effective=PENDING`（不落列：它是时间的函数，落列就得有定时任务去刷）。**拒绝本会话内一直挡住**。**决断的 UPDATE 带前置态**（`WHERE id AND status = PENDING`，按 affected 判定）—— 否则先点「拒绝」再点「批准」时后到的 update 会静默覆盖、连 `decided_by/at` 都被覆盖无从追溯；0 行按「状态已变更」返 409。**绝不向模型抛异常**（否则整轮 500，用户看到的是错误而不是「需要确认」）；闸门自身故障时 fail-closed |
| 调度器 | 三个 `@Scheduled`（告警自检 / 定时任务扫描 / 周期报告）**默认会共用 Spring Boot 内建的单线程调度池**，而定时执行是**在调度线程上同步跑真实模型对话** —— 一个慢任务会让其余两个整段不执行、cron 型报告错过触发点**当天永久丢失**（Spring 不补跑）、多实例下 `next_run_at` 的 CAS 只保证「同一时刻一个执行者」保证不了「一个周期一次」⇒ 短周期任务**重复执行重复计费**。故显式注册 `ThreadPoolTaskScheduler`（`SchedulingConfig`，pool 4 + 停机等待收尾）。**推论：新增 `@Scheduled` 就是新增一份线程池资源占用** |
| 定时任务 | `ScheduledTaskService` 按 `next_run_at <= now AND enabled = 1` 扫描（`next_run_at` 由 cron **预先算出并落库**，不是每次现算），到点把 `prompt` 当一条用户消息送进**承载会话**（首次执行时自动创建、标题=任务名；执行体就是 `ChatService.chat`，故路由/规划/RAG/记忆/工具/追踪原样继承 —— 另起一套执行逻辑会让产出与手动问一次不一致）。**抢占式触发**：先把 `next_run_at` 推进、**CAS 成功者才执行**，多实例或超时也不会重复触发；`max-per-tick` 限单轮执行数。失败只记状态+通知（`next_run_at` 早已推进，不会卡死）。**按 user_id 隔离、不校验角色**。**边界**：跑在调度线程上，**不过配额闸门**（配额只保护交互式入口） |
| 阈值告警 / 周期报告 | `AlertService` 定时聚合 `ObservabilityService.summary` 的口径，越线落 `ALERT` 通知、按周期落 `SYSTEM` 运行报告，出口复用通知铃铛。**必须轮询聚合**：判定的是窗口口径，本轮失败不构成「窗口质量不达标」，一轮一算会让告警依据的数与面板展示的数不是同一个。**三道防噪**：`min-sample` 样本不足不判定、`cooldown-minutes` 冷却期内不重报、空窗口不发报告。判定与「按需自检」**共用 `run()` / `sendReport()`**（两条路径各写一份迟早漂移）。`@Scheduled` 整体吞异常——调度线程抛异常会**静默停摆**，那是最难发现的故障 |
| 操作审计 | `AuditService.record` 由业务侧在动作**之后**显式调用（不引 AOP：注解式审计最难查的恰恰是「这个方法到底生效没有」，显式调用让记录点一眼可见）。`detail` 写入前过 `PiiMasker` 遮中段（手机号 / 身份证 / 邮箱 / 银行卡），**口令绝不进明细**；明细只写**真的变了**的项，角色 ID 列表输出前排序（免得「只是顺序变了」被记成一次变更）。落库异常只记 ERROR 不拖垮业务；**只增不改**，读接口整类限 ADMIN。放在 `system` 模块而不是 `ai`：依赖方向是单向的 `ai → system`，审计记录的是本模块的写路径，放 ai 会把依赖变成双向 |

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

### 操作审计（需 ADMIN）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/audit/page?page=&size=&action=` | 审计记录分页（**按 id 倒序**，即时间倒序；`action` 可筛单个动作编码，不传即全部）。`size` <1 按 20、>100 截断到 100 |

> **只有这一个接口**：审计表**只增不改**，没有写入 / 修改 / 删除入口 —— 写入由业务侧在动作发生时调用 `AuditService.record`，
> 清理只能由 DBA 直接操作数据库（一条有权重、有痕迹的路径），不该由应用随手提供。
> **返回实体而不是 VO**：与 `SysUser` 必须走 VO 的情况不同（那个藏的是 `password`），审计记录本身就是「给人看的记录」，
> 没有需要隐藏的字段 —— 明细在**写入时**就已脱敏，而不是靠读取时过滤（读取侧再遮一层只会造成「库里存着原文」的错觉）。
> 动作编码见 `AuditAction`：`CREATE/UPDATE/DELETE_USER`、`UPDATE_PASSWORD`、`CREATE/UPDATE/DELETE_ROLE`。

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
| GET | `/api/chat/history?conversationId=` | 读取会话历史消息（仅本人）；每条带 `id` 与 `memoryExcluded`（供前端渲染单条「不参与记忆」开关） |
| PUT | `/api/chat/message/{messageId}/memory-excluded` | 单条消息标记「不参与记忆」：body `{excluded}`（true=不参与 / false=恢复；**字段缺失返回 400**，显式 false 是正常取消标记）。此后该条既不进记忆窗口、也不参与摘要，但历史里仍可见。**副作用**：会重置该会话的摘要游标（`summarized_count` 归零，摘要与长期记忆内容保留），响应 `{excluded, memoryCursorReset}` 告知是否动了游标（幂等：同值重复设置返回 `memoryCursorReset=false`）。消息不属于本人会话一律 **404**（与「消息不存在」不可区分，避免用 ID 探测） |
| POST | `/api/chat/conversation/{id}/branch` | **开新版本**：为第 `keepCount` 条（1 基，必须是 user 提问）那一轮分组并返回 `{groupId, version}`；未分组则新建组、旧轮记第 1 版、返回 `version=2`，已分组则 `max(turn_version)+1`。**只分组、不改生效版本**（失效延后到落库后）；缺请求体 400（分支位置没有安全默认值；仅本人） |
| POST | `/api/chat/conversation/{id}/turn` | **切换版本**：body `{groupId, version}`，把该组其余版本 `turn_active` 置 0、目标置 1，并重置记忆水位；版本不存在 404（仅本人） |
| PUT | `/api/chat/message/{messageId}/feedback` | **提交 / 改票**一条消息反馈：body `{rating, reason, comment}`（`rating` ∈ `UP`/`DOWN`，`reason` 仅 DOWN 时有意义 ∈ `ANSWERS_OFF`/`FABRICATED`/`ROUTING`/`OTHER`，`comment` ≤500 字；任一项非法 **400**）。**一人对一条消息一票，改票原地覆盖不追加**；`UP` 会把 `reason` 清空。首次提交时快照那一轮的用户输入（`userInput`，取不到即 null）。只接受**助手消息**（对 user 消息反馈 400）；消息不属于本人会话一律 **404** |
| GET | `/api/chat/conversation/{id}/feedback` | 该会话下的全部反馈（按提交顺序），供前端按 `messageId` 合并到消息上回显「已反馈」与预填原因/备注。**刻意不做进 `/history` 响应**：历史是「说了什么」、反馈是「怎么看这句话」，变化频率与读取时机都不同，合成会让每次翻历史都白拉一遍反馈 |
| GET | `/api/chat/conversation/{id}/export` | 导出会话为 Markdown，返回 `{filename, content}` 由前端拼 Blob 下载（仅本人） |
| GET | `/api/chat/conversation/{id}/memory` | 读取记忆快照（仅本人）：`summary` / `coreFacts`（**旧版归档**，不再自动更新）/ `summarizedCount` / `messageCount` / `excludedCount`（被标「不参与记忆」的条数）/ `window`（**本轮会注入的窗口逐条**：`role` / `preview` / `chars`，与真实注入同一份 `DbChatMemory.snapshot`）/ `facts`（**长期事实逐条**：`id` / `topic` / `fact` / `source` / **`confidence`** / **`expiresAt`** / **`expired`**（服务端算好的布尔，前端不重复判时钟）/ **`status`**（`ACTIVE`/`SUPERSEDED`）/ **`supersededBy`** / 时间） |
| PUT | `/api/chat/conversation/{id}/memory` | 覆写摘要与旧版归档（body `{summary, coreFacts}`，**不动水位**；空白即清空该字段；仅本人） |
| DELETE | `/api/chat/conversation/{id}/memory` | 重置长期记忆：摘要 / 旧版归档 / 水位三列归零，**并作废自动整理出的 `SOURCE_MERGE` 事实条目**，历史消息与**手动条目**保留（仅本人） |
| POST | `/api/chat/conversation/{id}/facts` | 新增一条长期事实，body `{topic, fact, expiresAt?}`（`topic` 归一到白名单：身份 / 偏好 / 待办 / 背景 / 其它；空或超长 **400**；`expiresAt` 可空 = 永不过期）。`source` / `fact_hash` / `confidence` / `status` 由服务端决定，**不接受客户端指定**。若与某条自动条目完全重合则把它**转成手动**（认领）而非报重复；仅与已有手动条目重复才 400（仅本人） |
| PUT | `/api/chat/conversation/{id}/facts/{factId}` | 修改一条长期事实（主题、内容、有效期都可改）。**改过的条目从「自动」转为「手动」**，此后自动合并不再覆盖或淘汰它；`expiresAt` 传 `null` 表示**清除有效期**（重新永久有效），不是「保持原值」；条目不属于该会话一律 **404**（仅本人） |
| DELETE | `/api/chat/conversation/{id}/facts/{factId}` | 删除一条长期事实（自动 / 手动都可删）。删掉自动条目后它**不会**被下一次合并自动加回（合并输入只含当前条目 + 新增内容）；条目不属于该会话一律 **404**（仅本人） |
| POST | `/api/chat/task/resume` | SSE 流式续跑未完成任务（显式按钮触发）：回填已完成步骤、只跑剩余步骤。**五条通路共用它**；入口先清 `pause_requested` |
| GET | `/api/chat/task/running?conversationId=` | 查询当前会话的 RUNNING 任务（无则 404）；返回 `RunningTaskView`（含**步骤明细**：每步 `index`/`agentCode`/`agentName`/`status`/`error`/`retryExhausted`，智能体名现查不落库快照），供「继续执行」提示条显示「卡在哪一步、能不能跳过」 |
| POST | `/api/chat/task/pause` | 请求暂停 `{conversationId}`：置 `task.pause_requested=1`，执行循环在**下一个层边界**停止推进（任务仍 RUNNING、剩余步骤仍 PENDING）。回 `{ok, pauseRequested, message}`，**措辞刻意不说「已暂停」**（同层是并行 join，硬中断只会留下半截产出）；任务不存在 404 |
| POST | `/api/chat/task/step/skip` | 人工跳过某步 `{conversationId, stepIndex}`（`PENDING`/`FAILED` → `SKIPPED`，跳过原因写入 `error` 列）；只改库**不自动开跑**，回 `{ok, stepIndex, doneSteps, totalSteps}`。状态不是 PENDING/FAILED 一律 400（带上当前状态） |
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

### 定时任务（仅需登录，按用户隔离）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/schedule` | 本人的全部定时任务（含已停用），带 `lastRunAt`/`lastStatus`/`lastResult`/`nextRunAt`，供前端看「上次跑成没跑成」 |
| POST | `/api/schedule` | **新建（无 `id`）或修改（带 `id`）**：`{id?, name, cron, agentId?, prompt, enabled?, notifyOn?}`。**cron 非法直接 400**。**没有 `conversationId`** —— 承载会话在首次执行时自动创建（标题 = `name`）并回填 |
| DELETE | `/api/schedule/{id}` | 删除；不存在或非本人一律 **404** |
| PUT | `/api/schedule/{id}/toggle?enabled=` | 启用 / 停用（不删任务、只停调度） |
| POST | `/api/schedule/{id}/run` | **立即执行一次**（不影响既定排期）。会发起真实模型调用，但触发者是本人、花的是本人的额度，故不另设 ADMIN 门槛 |

> **不校验角色**：任务是**个人资产**，按 `user_id` 隔离，越权一律 **404**（与「不存在」不可区分）—— 与成本/可观测那类跨会话全站视角（限 ADMIN）不是一回事。
> **cron 非法 / 承载会话被删**等失败会写进 `lastStatus`/`lastResult` **并推一条通知**，不静默 —— 一个悄悄不再执行的任务比一个报错的任务更难发现。

### 通知（仅需登录，本人 + 广播）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/notification?unreadOnly=` | 本人的通知 + `user_id IS NULL` 的广播，倒序（`unreadOnly=true` 只回未读）。类型 `type` ∈ `ALERT` / `SYSTEM` / `SCHEDULED_TASK` / `PROMPT_GATE`，级别 `level` ∈ `INFO` / `WARN` / `ERROR` |
| GET | `/api/notification/unread` | 未读数 `{count}`，供顶栏铃铛轮询（**不是拉列表**：轮询只该读一个整数，不该每十几秒拖回全部正文） |
| POST | `/api/notification/{id}/read` | 标记单条已读，回 `{ok}`。**不存在 / 非本人可见 / 已读都返回 `ok=false` 而不报错**（幂等语义：重复点「已读」不该弹错） |
| POST | `/api/notification/read-all` | 全部已读（含广播），回 `{ok, marked}` —— 报出本次实际标记了几条 |

> **为什么要有通知出口**：定时任务完成、聚合指标越线、提示词门禁出结论 —— 这些事都发生在「用户不在对话里」的时候，没有出口就只能靠人主动去翻。
> **广播（`user_id IS NULL`）也可能有归属诉求**：它投给所有人，而 `GET` 只回「本人 + 广播」，所以别人的定向通知不会漏给自己、也不会误投。
> 落库失败**绝不静默**（`push` 的调用方都是「业务已经发生、需要告知用户」的场景），记 ERROR 并可配 webhook 外发。

### 工具审批闸门（仅需登录，按会话隔离）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/tool-approval?conversationId=` | 审批记录（`ToolApprovalDto` 含后端折算好的 `effective`：库里的 `APPROVED` 过了 `expireMinutes` 就折回 `PENDING`），不传 `conversationId` 即本人的全部 |
| POST | `/api/tool-approval/{id}/approve` | 批准该次工具调用，body `{note?}`（可空）。**批准带时效**（`expireMinutes`，默认 30 分钟） |
| POST | `/api/tool-approval/{id}/reject` | 拒绝，body `{note?}`；**本会话内一直挡住**，要恢复得走下面的 `reset` |
| POST | `/api/tool-approval/{id}/reset` | 撤销拒绝（把已决记录恢复成待确认） |

> **不校验角色**：它是「我自己会话里的工具放不放行」，按会话隔离；与跨会话运营视角（限 ADMIN）不是一回事。
> **展示状态由后端折算**（`effective`），前端不自己判过期 —— 库里的 `status` 是「当时决定过什么」、`effective` 才是「现在算不算数」，
> 两者分开才能既留档又正确（前端自己判时钟，时区/时钟差会让两边结论不同）。
> **副作用是刻意的**：拒绝后不是「只挡这一次」—— 否则模型下一轮照调不误，用户会以为拒绝没生效。

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
| POST | `/api/kb/{id}/upload` | 上传文件（multipart `files` + `chunkStrategy` + `overlap`），同名重传=替换。**支持图片**：png / jpg / jpeg / gif / webp / bmp / tif / tiff / heic / avif 先由视觉模型识别成文字描述再入库，**识别失败即该文件失败**（不落占位文本） |
| GET | `/api/kb/{id}/files` | 文件列表 |
| POST | `/api/kb/{id}/files/{fileId}/rechunk` | 重新分片 `{"strategy","overlap"}`（缺省沿用文件当前值） |
| DELETE | `/api/kb/{id}/files/{fileId}` | 删除文件及其知识块 |
| POST | `/api/kb/chroma/sync` | 幂等回填 Chroma 副本 |
| GET | `/api/kb/chroma/status` | Chroma 连接状态 / 空间 / 向量条数 |

### 链路追踪

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/trace?conversationId=&limit=` | 追踪列表（时间倒序，默认 50 条、上限 200）。**只回当前登录用户名下会话的记录**；不传 `conversationId` 表示「不限会话」，但**不是**「不限用户」 |
| GET | `/api/trace/{traceId}` | 单轮追踪详情；不存在**或不属于当前用户**一律 404（二者不可区分，防拿 traceId 探测他人记录）。响应含 `selfEval`（**线上回答自评**：`score` 取自 `self_eval_score` 列恒有值；`answered`/`grounded`/`issues`/`comment`/`trigger` 取自明细 JSON，**明细读不出来时 `answered`/`grounded` 为 `null` 而不是 `false`**）。`selfEval` 为 `null` = 该轮未自评 |

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
| GET | `/api/observability/summary?days=` | 近 N 天（默认 7、上限 90）运行质量聚合：总览（轮次/成功率/平均耗时/token）+ 按天·按形态·按来源·按智能体 + 慢轮 Top N + **回答质量（自评聚合：`evaluated`/`unevaluated`/`lowScore`/`avgScore`，`coverage` 与 `lowRate` 为派生值）+ `lowScoreThreshold`（低分线，由 `agent.self-eval.low-score-threshold` 下发）+ `lowRounds`（低分轮次，分数升序、最多 20 条，只给定位信息、不含自评明细）** |

> **仅 ADMIN 可访问**（`ObservabilityController` 标 `@RequireRole(ADMIN)`）。与成本看板同一收敛逻辑：跨会话全站聚合，
> 逐条看某会话的链路细节走「🔍 追踪」（已按归属隔离），全站质量看这里。独立页 `/observability.html`，
> 顶栏「📊 可观测」入口按同一角色显隐。
> 指标口径：成功率 = 1 − `error` 轮占比；耗时取 `elapsed_ms` 平均（本轮从进编排到产出回复的**总耗时**，
> 不细分路由/改写/参数抽取的分段耗时 —— 那需要加列，暂不做）。
> **自评口径**：「已自评 / 未自评 / 低分」三个数**并列**给出，**低分率的分母是"已自评"而非全量轮次** ——
> 否则调低采样率会让低分率自动变好看，那是指标自欺。「未自评」（默认关闭时的常态）与「评了低分」（质量信号）
> 是两件事，页面上有专门的一句说明，别把 `105` 读成「105 轮答得差」。空窗口时自评四数全 0、阈值照常返回
> （阈值是配置不是数据），面板仍渲染 —— 藏掉面板就分不清「一次都没自评」和「后端没有这个字段」。

### 提示词回归评测（仅 ADMIN）

> 改 `prompts.yaml` 前后各跑一批，看「上一批通过、本批失败」清单（`broken`）有没有变长。
> 跑批走**真实链路**（真实模型调用），消耗计入成本看板，会话 ID 固定标记为 `__eval__`，不与真实对话混算。
> **整类需 `ADMIN` 角色**：跑批会花钱，属运营视角动作；前端顶栏「🧪 评测」入口按同一角色显隐。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/eval/cases?scenario=` | 列出用例集（`scenario` 可筛 `ROUTE` / `PLAN`，不传即全部）。**返回 yaml 种子与库内增量合并后的视图**，同名以 yaml 为准（解析失败 400，不当「0 条用例」静默放过） |
| GET | `/api/eval/cases/db` | **只看库内用例**（含已停用，倒序）：`id` / `name` / `scenario` / `input` / `expectJson` / `source` / `feedbackId` / `enabled`。展示与删除的入口 |
| POST | `/api/eval/cases/from-feedback` | 把一条用户反馈转成库内用例 `{feedbackId}` → 回新用例（`EvalCaseEntity`）。断言**自动预填但只填可确定的部分**（实际路由到哪个智能体 / 计划里有哪些智能体）；反馈不存在 404、**已转过 400**（不重复生成）、无用户输入快照或匹配不到追踪记录 400（不生成没有断言的用例） |
| DELETE | `/api/eval/cases/db/{id}` | 删除库内用例（不存在 404）。只影响后续跑批，历史批次里已落库的结果不受影响 |
| POST | `/api/eval/run?scenario=` | 跑一批：并发执行 + 单条超时（默认 60s），返回 `{batchId, total, passed, failed, configErrors, costMs, results[]}` |
| GET | `/api/eval/batches` | 历史批次摘要（近 20 批，更旧的自动清理） |
| GET | `/api/eval/batches/{batchId}` | 某批次的逐条结果 |
| GET | `/api/eval/compare?from=&to=` | 两批对比：`fixed` / `broken` / `stillFailed` / `onlyFrom` / `onlyTo` |

> 用例集文件默认 `classpath:eval-cases.yaml`（`agent.eval.cases-file` 可改）。用例格式：
> `name`（用例名）、`scenario`（`ROUTE` / `PLAN`）、`input`（用户输入）、`pendingQuestion`（可选，模拟上一轮追问）、
> `expect`（断言 JSON）。**断言只写可确定的部分**——路由类断言 `{"noRoute":true}` 或 `{"agentCode":"A002"}`，
> 规划类断言 `{"containsAgents":["A001"]}`，别断言模型措辞（那是在测模型，不是在测提示词）。
> **用例有两个来源**：yaml 是打包进 jar 的**只读种子**（运行时写不了），`eval_case` 表承接运行时增量
> （当前唯一入口是 `POST /api/eval/cases/from-feedback`，即「把一条用户反馈转成用例」）。
> 同名以 yaml 为准，两侧在 `loadCases` 里合并、命中同名时记 WARN。

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
| `conversation` | id, **user_id**, title, agent_id, planner, **planner_confirm**, agent_bind_source, rag_enabled, **review_enabled**, **cross_session**, summary, summarized_count, core_facts, **clarify_state** | 会话：**归属用户（按 user_id 隔离，仅本人可见）**、绑定智能体、规划开关 / 规划「先看计划」开关、RAG 开关、并行评审开关、跨会话搜索开关、滚动摘要与**旧版事实归档**（`core_facts` 自「长期事实条目」上线后**不再自动更新**，改由下表逐条承载；仍作为迁移输入与条目清空后的回退来源）。**规划与评审互斥**（都是编排形态，开启任一方会自动关掉另一方）。**`clarify_state`** 是参数追问（澄清）的显式状态 JSON（`{agentId,asked,request,question,params}`，NULL = 无进行中的追问）—— 落库以摆脱「每轮扫历史重放推导」：历史被摘要压缩或被标「不参与记忆」后，重放会算错已问次数与已确认参数；`agentId` 作「换智能体即作废」判据，`unbindAgent` / `clearMessages` 时清空 |
| `conversation_fact` | id, conversation_id, topic, fact, **source**, **fact_hash**, **confidence**, **expires_at**, **status**, **superseded_by**, created_at, updated_at | **长期事实逐条**（唯一键 `uk_conv_fact(conversation_id, fact_hash)`）。`topic` 归一白名单：身份 / 偏好 / 待办 / 背景 / 其它。**`source` 决定合并时怎么对待它**：`MERGE`（模型整理）每次合并**按 diff 重写**（不在新清单里的即视为过时删除 —— 这是淘汰旧事实的唯一通路）；`USER`（用户手加 / 手改 / 重合认领）**合并绝不覆盖也绝不删除**。**生命周期三列**：`confidence`（1~5，用户手写 5 / 合并 3，每次被重新确认 +1，封顶 5）、`expires_at`（可空 = 永久；过期不注入但**不自动删**）、`status` + `superseded_by`（`SUPERSEDED` = 被同主题新说法取代，不注入但留档，并记下被谁取代）。`fact_hash = MD5(topic + fact)` 做同会话内去重 —— **不用 `fact` 本身做唯一键**：`VARCHAR(500) utf8mb4` 已超 InnoDB 索引键长上限。**无外键约束** ⇒ 删会话时由服务层显式清理 |
| `chat_message` | id, conversation_id, role, content, attachments_json, citations_json, **turn_group_id**, **turn_version**, **turn_active**, **memory_excluded**, created_at | 消息明细；附件元数据与引用来源**独立列**，不进记忆、不占 token。**分支版本三列**：`turn_group_id`（同轮多版本共用 UUID，NULL=从未分叉）/ `turn_version`（组内序号，从 1 连续递增）/ `turn_active`（当前生效版本，同组至多一个为 1）；读取侧统一 `turn_group_id IS NULL OR turn_active = 1`，存量数据零回填。**`memory_excluded`**（默认 0）只作用于**记忆侧三处**（`getRecentHistory` / `countMessages` / `getMessagesRange` 统一走 `memoryVisible()`），展示侧 `getHistory` 与导出**刻意不过滤** —— 「不进记忆」不等于「删掉」 |
| `agent` | id, name, agent_code, icon, description, system_prompt, param_schema, tools_json, model, temperature, avatar_color | 智能体：人设、参数清单、工具白名单、模型/温度覆盖 |
| `kb` | id, name, agent_id, description, doc_count, chunk_strategy, chunk_overlap | 知识库；`agent_id` 为空即通用全局库 |
| `kb_chunk` | id, kb_id, content, source, embedding, created_at | 知识块；`embedding` 为向量 JSON 文本（MySQL 源） |
| `kb_file` | id, kb_id, file_name, file_type, chunk_strategy, chunk_overlap, size_bytes, chunk_count, raw_text | 以文件为管理单元；`raw_text` 支持不重传重新分片 |
| `agent_trace` | trace_id, conversation_id, mode, route_source, agent_code, user_message, retrieval_query, plan_json, **memory_json**, tool_calls, kb_hit_count, citations_json, prompt_tokens, completion_tokens, total_tokens, elapsed_ms, **self_eval_score**, **self_eval_json**, status | 纯旁路可观测表，删掉不影响对话。`mode` 现有 `agent` / `planner` / `review` 三态，`route_source` 相应有 `REVIEW`（并行评审），可观测面板的「按形态 / 按处理方来源」分布会自动多出这两档。**`memory_json`** 是本轮注入的记忆构成快照（窗口逐条 + 三段字符数，事实段键名为 `factsChars`），**NULL = 未采集**（旁路观测，采集失败只记 WARN、留空即可）；只在 agent 形态采集，规划 / 评审留空。**`self_eval_score`**（1~5，`NULL` = 未自评：未命中采样 / 回答过短 / 调用失败）**单独成列**而非只塞 JSON —— 可观测面板的「低分轮次」要按它过滤与聚合，JSON 里解析不出索引；`self_eval_json` 存明细（`answered` / `grounded` / `issues` / `comment` / `trigger`），与分数同生共死，一列过滤一列细看。自评同样是旁路：失败只记 WARN |
| `llm_usage` | trace_id, conversation_id, purpose, model, prompt_tokens, completion_tokens, total_tokens, created_at | 裸 LLM 调用成本流水（全量成本口径）：路由/参数抽取/查询改写/计划生成/视觉/记忆合并/智能体转交/跨会话召回/并行评审各记一条，按用途拆解 |
| `task` | id, conversation_id, user_goal, status, total_steps, done_steps, result, **pause_requested**, created_at, updated_at | 规划任务：一轮规划落库一条，状态机 `RUNNING→DONE/FAILED/CANCELLED`；单会话单 RUNNING。**`pause_requested` 不是状态、是「让执行循环在下一个层边界自停」的一次性信号**（与「单会话单 RUNNING」的不变量同处一张表，不引入第二种状态源）；清零责任在续跑入口 |
| `task_step` | id, task_id, step_index, agent_code, instruction, depends_on, status, retry_count, output, error, citations_json, **approval_required**, **approved**, started_at, finished_at | 任务步骤：逐步增量提交产出；`FAILED` 续跑重试一次，累计 ≥2 判确定性失败。`approval_required=1` 的步骤执行前先暂停等待批准（`approved` 记批准与否）—— 这两列**独立于 `status`**：status 说「跑到哪了」、审批说「允不允许跑」。跳过（`SKIPPED`）另有两种来源：**智能体已被删除**（系统自动）与**用户手动跳过**，原因都写进 `error` 列 |
| `task_template` | id, user_id, name, description, steps_json, source_task_id, use_count, created_at, updated_at | 规划模板：**按 user_id 隔离**（仅本人可见，越权一律 404）。`steps_json` 是步骤骨架的**快照**（`[{agentCode,instruction,dependsOn}]`），不引用 `task_step`——那张表会随局部重规划删改/重排行 |
| `scheduled_task` | id, **user_id**, name, cron, agent_id, prompt, **conversation_id**, enabled, notify_on, last_run_at, last_status, last_result, **next_run_at** | 定时任务：**按 user_id 隔离**（越权一律 404）。`cron` 是 Spring 六段式；`agent_id` 为空即走智能路由；`conversation_id` **在首次执行时自动创建并回填**。**`next_run_at` 由 cron 预先算出并落库**（不是每次扫描都现算）—— 扫描就是一句 `WHERE enabled = 1 AND next_run_at <= now ORDER BY next_run_at`，复合索引 `idx_sched_due` 让过滤与排序一趟走完。**重叠触发由 `next_run_at` 的 CAS 挡住**（推进成功的那个线程才执行），`last_status=RUNNING` 只是「这一轮正在进行」的展示态。失败原因写 `last_result` 并推通知，不静默 |
| `notification` | id, **user_id**, type, level, title, content, ref_type, ref_id, read_at, created_at | 通知出口：用户不在对话里时发生之事的送达。**`user_id` 为 NULL = 全员广播**（系统级），非 NULL 为定向；读取一律「本人 + 广播」。`type` ∈ `SYSTEM` / `SCHEDULED_TASK` / `ALERT` / `PROMPT_GATE`，`level` ∈ `INFO` / `WARN` / `ERROR`（决定前端配色与外发门槛）。索引 `(user_id, created_at)` 供未读轮询走。**读口径 ≠ 写口径**：广播（`user_id IS NULL`）**可见但不可标记已读** —— `read_at` 是单列，表达不了「每人各自读过」，让任一登录用户点一下就把全体共享的告警/门禁结论抹掉且不可逆 |
| `tool_approval` | id, **conversation_id**, agent_id, tool_name, input_json, user_message, status, note, **decided_by**, decided_at, used_count, last_used_at, created_at | 工具审批闸门：**会话级**「该工具是否放行」的决断留痕（唯一键 `uk_conv_tool(conversation_id, tool_name)` —— 同一会话同一工具只有一条，因为拒绝的效力是「本会话内一直挡住」，不是逐次记流水）。`input_json` 原样留档供用户判断「它到底要干什么」；`decided_by` 留痕决断人；`used_count` 回答「批了之后真的用了吗」。**过期不落列**：`APPROVED` 的时效由 `decided_at`（空则退 `created_at`）+ `agent.tool-approval.expire-minutes` **现算**成 `effective` 下发 —— 「已过期」若落成列就得有定时任务去刷它，而它本来就是时间的函数。`REJECTED` **永久有效**（用户说过不行，只有显式撤销才改变，不搞「过一会儿自动放行」）|
| `prompt_snapshot` | id, **fingerprint**, batch_id, total, passed, failed, config_error, broken_count, fixed_count, verdict, detail_json, created_at | 提示词门禁快照：把 `prompts.yaml` 的**内容指纹**与某一批评测结论绑在一起。`fingerprint` 只回答「变了没有」、不回答「改了哪句」（存全文 diff 会变成第二处真相）。`verdict` ∈ `PENDING` / `PASS` / `DEGRADED` / `STILL_FAILED` / `ERROR`；**「这批结论还算不算数」由指纹与当前文件比对现算**（`verified`），不落列 |
| `message_feedback` | id, **message_id**, conversation_id, user_id, rating, reason, comment, **user_input**, **eval_case_id**, created_at, updated_at | 消息反馈（👍/👎）：**一人对一条消息一票**（唯一键 `message_id + user_id`，改票原地覆盖不追加）。`user_input` 是那一轮的用户输入**快照**（与 `agent_trace.user_message` 同一取舍：会话被删、消息被改都不该让反馈失效）；`eval_case_id` 既是「已转成哪条用例」的记录，也是防重复转的标记 |
| `eval_case` | id, name, scenario, input, pending_question, expect_json, **source**, feedback_id, enabled, created_at | **库内回归用例**（`eval-cases.yaml` 之外的运行时增量，唯一键 `name`）。`source` 区分 `YAML` / `FEEDBACK`。为什么要单独一张表：yaml 打包进 jar 后运行时写不了，而「点踩 → 转成回归用例」必须在运行时新增；yaml 退化为只读种子，本表承接增量，两者在 `/api/eval/cases` 合并、同名以 yaml 为准 |
| `eval_result` | id, batch_id, case_name, scenario, input, expected, actual, passed, config_error, failure, detail, cost_ms, created_at | 提示词回归评测逐条结果；`batch_id` 分组一批，`config_error=1` 为「用例本身写错」单列一档；只保留最近 20 批，更旧的跑完即清 |
| `sys_user` | id, username, password, nickname, email, status, last_login_at, created_at, updated_at | 登录账号；`password` 为 BCrypt 哈希（自带盐），`status=0` 停用后已签发 token 立即失效 |
| `sys_role` | id, code, name, description | 角色；`code` 是授权判定依据（`@RequireRole` 比的是它），不可修改 |
| `sys_user_role` | id, user_id, role_id, created_at | 用户-角色多对多授权，权限取并集；无外键约束，删除用户时由服务层清理 |
| `audit_log` | id, **user_id**, **username**, action, target_type, target_id, detail, ip, created_at | 管理操作审计（**只增不改**：无更新/删除入口，清理只能由 DBA 直接操作库）。`username` 是**快照**（`sys_user` 那行可能被改名甚至删除，只留 `user_id` 会让记录变成一串读不懂的数字 —— 审计必须自解释）；`detail` 是变化描述（**超长在写入前截断并留省略标记**，不是靠列宽静默截掉）；`target_id` 存字符串以兼容非数字主键。**`action` 取值见 `AuditAction`**（新增/修改/删除用户、重置口令、新增/修改/删除角色）|

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

- **顶栏**：会话列表（含 🧭 规划标记）、当前会话徽标（🧭 规划模式 / 📚 RAG）；右侧动作区**整体靠最右**，依次为 🔍 追踪（无会话时不显示）、⬇ 导出（无会话时不显示）、🧠 记忆（无会话时不显示）、💰 成本（仅 ADMIN）、🧪 评测（仅 ADMIN）、📊 可观测（仅 ADMIN，跳 `/observability.html`）、🎓 教务系统（跳 `/edu.html`，与 edu 页的「前往 AI 对话」互为对称入口）、⏰ 定时（定时/周期任务）、🔔 通知（未读数角标 + 面板）、以及**登录用户区**。靠右由容器 `.header-actions` 统一负责（`margin-left:auto` + `gap`），各按钮不自带 `margin-left` —— 否则「追踪」这类条件渲染的按钮一缺席，整组就会塌回标题旁边。按角色显隐的入口读页面级 `isAdmin`（setup 时从 `Auth.hasRole('ADMIN')` 取一次存进 `ref`）—— `Auth.getUser()` 读 localStorage、不是响应式的，模板里直接调它只会求值一次。
- **登录用户区（四页统一，只有一个用户名）**：顶栏不再出现裸露的「退出」按钮 —— 点击用户名展开下拉：**个人信息 / 修改口令 / 用户管理（仅 ADMIN）/ 退出登录**。四页（index / chat / edu / user）都是 `js/auth.js` 渲染到 `data-auth-nav` 挂载点的**同一份实现**，页面自身不含登录逻辑；细节见「登录」一节。
- **输入区**：🖼 图片多选（≤5 张）、📎 文档上传、📚 RAG 开关、🧭 规划开关（绑定智能体的会话置灰）、以及规划开关开启时出现的 **「先看计划」开关**（规划只产出计划并暂停，确认后才执行）。处于「编辑重发」态时，输入区顶部会出现**编辑提示条**（原消息已填回输入框，右侧一个取消按钮），发送即先截断再重发。输入区底部另有**配额刻度**（`v-if="quota.enabled && !quota.exempt"`）：显示「已用 / 上限」，用量到 90% 时整条转警示色 —— 只在配额开关打开且本人不豁免时出现，平时不占位置。输入区底部还有**参数补全提示**（`⏳ 参数补全中（N/3）：补齐后自动继续`，数据来自会话列表返回的 `clarifyAsked` / `clarifyMax`）：这是「本轮停在一次追问上」的交互态，讲的是输入区的事，故**长在输入区而不在顶栏**——顶栏那排是会话级开关（规划 / 评审 / RAG / 跨会话），且顶部行已排满，塞进去会把标题挤折行；它与「智能体会话暂不支持规划模式」提示**互斥**（`v-else-if`，一条槽位只显示一条）。
- **对话分支（版本切换器）**：鼠标悬停任一消息气泡浮现动作区（默认 `opacity:0`，`.msg:hover` 才显示）—— assistant 消息给「重新生成」，user 消息给「编辑重发」，以及**该轮已有多版本时挂在提问上的「n / m ‹ ›」切换器**（切一次提问与回答一起换）。**每条消息另有「不参与记忆」开关**（已标记时显示「不参与记忆 ✓」并高亮；未落库的消息 / 请求进行中一律置灰）。**助手回复上还有「👎 反馈」**：已评价时按钮高亮（点踩用危险色弱底，与「不参与记忆」的主色弱底区分开），点开展开问题分类 + 备注表单（表单就长在消息下方，不弹窗——评价的对象就在眼前，弹窗会把它挡掉）。**重新生成** / **编辑重发**都先调 `POST …/branch` 开新版本，再走现成的流式通路发送（不复制发送逻辑）；开版本失败会中止并保留编辑态（不静默丢掉用户已改的内容）。**切换器只在 `versionCount > 1` 时出现**（未分叉的轮不挂），首 / 末版本对应的箭头置灰。生成中这些按钮一律不出现——正在写入的那一轮尚未落库，此时切换只能拿到一份对不上眼前所见的历史。
- **执行中干预（提示条上的暂停 / 跳过）**：有未完成任务时顶部提示条除「继续执行 / 重新规划」外，按状态多出两个动作。**某步重试用尽**时提示条右侧明确写出「第 N 步（某智能体）重试已用尽，不会自行恢复」，并给出「跳过第 N 步」（点击前弹确认，讲清「该步产出为空、依赖它的后续步骤拿不到这段输入」）。**执行中**（`canPause`）则出现琥珀色「暂停」，点击后回执写明这是「当前正在执行的那一层跑完后停止推进」而非立即暂停。两个动作都只改库，之后一律点「继续执行」走断点续跑。
- **引用回链（气泡内）**：正文里的 `[n]` 是蓝色可点角标，点一下展开该轮「引用来源」并把第 n 条高亮（2 秒后自动褪去，用背景闪烁而非描边 —— 不改行高就不会让列表滚动位置跳动）；找不到对应序号会明确提示「可能是模型自行标注的角标」，而不是点了没反应。来源条目右侧的「原文」按钮打开**引用原文弹窗**（库名 / 文件名 / 块号 + 知识块正文按原样 `pre-wrap` 展示），取块失败时弹窗内显示后端给的原因；追踪弹窗的引用来源同样带这个入口。
- **计划卡片（「先看计划」开启时）**：规划暂停后，AI 气泡内呈现计划卡片 —— 步骤清单（序号 / 智能体 / 指令 + 每步一个「需审批」勾选框）+ 「执行计划」按钮，右上角标 `待确认 / 执行中… / 已执行`。点按钮走的就是断点续跑通路。刷新后卡片消失（`plan` 事件不落库），但**顶部「执行计划」提示条仍在** —— 它是计划卡片之外的第二入口，保证刷新后仍能接着执行。**勾选「需审批」的步骤执行到时会停下来**，页面弹出**审批卡片**（刻意的琥珀色调，与蓝色计划卡片区分「等你决定」）：显示第几步 / 共几步、智能体名与指令，并提供「批准并继续」与「终止计划」两个动作。勾选状态**失败会回滚到原值**（不让界面显示成已生效）。
- **记忆面板（🧠 记忆）**：把此前完全黑盒的双层记忆摊开 —— 显示滚动摘要、旧版事实归档、覆盖度（`summarized_count` / 消息总数）与两个可编辑文本框（摘要在上、归档在下）。可**订正内容**（保存只覆盖这两个字段、不动水位），也可**重置全部记忆**（三列归零、**并作废自动整理出的事实条目**、历史消息与手动条目保留）。面板内向用户明说：改内容不影响历史消息、重置后记忆会从头重新压缩。**另有「当前窗口（本轮会注入的历史）」清单**：逐条显示角色 / 前 60 字预览 / **实际进上下文的字符数**（是截断之后的长度，不是库内原文长度），窗口构成由 `DbChatMemory.snapshot` 算 —— 与真实注入**同一份算法**，各写一份必然漂移，而错误的透明化比黑盒更糟。被标「不参与记忆」的条数在窗口下方单列提示。**面板最上方是「长期事实（逐条）」区块**：按主题分组列出每条事实，每条带**来源标签（手动 / 自动）**与行内「改 / 删」，底部一行是「主题下拉 + 输入框 + 添加」。**改一条自动条目后它的标签立刻翻成「手动」** —— 这是「我纠正过的事，别再给我改回去」在界面上的唯一证据；板块下写明注入优先级（「只要还有条目，下面的『旧版归档』就不再注入」）与淘汰规则（手动条目永不被删），否则「删掉一条自动条目」和「下次它又冒出来」在用户眼里是随机的。
- **会话导出（⬇ 导出）**：把当前会话导出为 Markdown（含每轮的 user/assistant 正文、附件文件名、RAG 引用来源）。**不走 `/files/**` 文件通道** —— 下载要带 `Authorization`，而浏览器对 `<a href>` 导航带不上该头，走文件通道必 401；故后端只回 `{filename, content}`，前端拼 `Blob` 下载。
- **智能体管理页**：智能体列表 / 新建 / 编辑（人设、参数 schema、工具勾选、模型与温度、配色）；工具栏右侧另有**导出 / 导入**，把智能体当资产搬进搬出。导出为 JSON 数组（`AgentPortable`，**不含 id / 时间戳、不含专属知识库内容**）；导入按 `agent_code` 匹配，冲突策略可选**跳过或覆盖**，且**逐条容错**（单条脏数据只记进 `errors`、不让整批失败）。`overwrite` 必须**保留本地 id**——`agent.id` 被 `conversation.agent_id` 引用，换 id 会切断会话归属。
- **知识库页**：库/文件管理、上传与重新分片、分页查看知识块、Chroma 状态条与「同步本库」。上传 `accept` 含图片后缀（自动识别为文字描述再入库），空态与提示文案都写明「支持 txt / md / csv / pdf / docx / xlsx / 图片」。
- **追踪弹窗**：路由来源、规划步骤、检索问句、RAG 命中、工具调用、token 与耗时，**本轮注入记忆**（窗口逐条 + 窗口 / 摘要 / 事实三段字符数；`memory_json` 为 NULL 时明确显示「未采集」，与「注入为空」是两种文案），以及**回答自评**（折叠行上先给一个 `⭐ N` 徽标，展开后是分数 + 触发来源标签「点踩强制 / 采样抽检」+ 是否答到问题 / 是否言之有据 / 问题短语 / 说明）。**未自评的那一轮显式显示「本轮未自评 —— 自评按采样率抽检（默认关闭），用户点踩会强制评一次」，不留白、不显示 0 分**（留白会被读成这轮答得很差）。**可见范围**：默认只显示当前登录用户名下会话的记录（后端按会话归属过滤，非本人记录按不存在处理）；**ADMIN 额外有一个「本会话 / 全部会话」切换**，可查看全站追踪。工具栏文案会随范围实时变化，空态也按范围给不同措辞——「本会话没有」和「全站都没有」是两回事。列表**每页 10 条**，底部页码条显示「共 N 轮 · 第 x / y 页」，翻页后自动滚回列表顶部；聚合统计条始终基于**全量**记录（它回答「这个范围的总体情况」，不是「这一页」）。明细展开态按 `traceId` 记录 —— 用列表下标记会在翻页后串页（第 1 页第 3 条与第 2 页第 3 条共用一个展开态）。**分页与范围无关**：本会话与全部会话共用同一套分页（后端也只有 `GET /api/trace` 一个接口，`conversationId` 只是过滤参数）；页码条按「有数据」渲染而不是「页数 > 1」—— 本会话多半不足一页，若按页数判断会整个不显示，看起来像「只有全部会话才分页」。1 页时只出页码信息、翻页按钮组隐藏。
> **为什么规划 / 评审模式下注入清单留空（「未采集」）**：一轮内多步、多候选各自注入的是**同一个窗口**，一份清单代表不了任何一次；给个看着合理的数字比不给更糟。注入清单只在「整轮只注入一次」的形态（agent）采集，采集点在业务侧 `ChatService.runRound`（`ChatMemory.get` 只收 conversationId、拿不到追踪句柄），调的是与真实注入**同一个** `snapshot`，且采集时机紧贴模型调用、期间无写入。
- **成本看板弹窗（💰 成本，仅 ADMIN）**：全量成本按天趋势（堆叠柱）+ 按用途拆解（饼图），近 7/30/90 天切换。入口按 ADMIN 角色显隐（后端 `@RequireRole(ADMIN)`，普通账号连入口都不渲染）。
- **评测弹窗（🧪 评测，仅 ADMIN 可见）**：场景切换（全部 / 路由 / 规划）、用例条数、▶ 跑一批 → 三态统计（通过 / 失败 / 配置错误，逐条左边框绿 / 红 / 琥珀区分）+「最近两批对比」（`已修复` / `新增失败`，后者红底加粗，是改提示词后最先要看的一行）+「**来自反馈的用例**」（库内增量，逐条显示输入与预填断言，可删除）+ 历史批次列表。跑批走真实调用、计入成本看板。
- **可观测面板（📊 可观测，独立页 `/observability.html`，仅 ADMIN）**：六张总览卡（轮次 / 成功率 / 平均耗时 / 平均 token / 总 token / 活跃天数）+ **回答质量（模型自评）**（四张卡：已自评 / 未自评 / 低分 / 平均分，副标题给低分线 · 覆盖率 · 低分率，配一句「『未自评』与『低分』是两件事」的说明；下面接**低分轮次表**，分数升序、列出自评分 / 形态 / 来源 / 智能体 / 耗时 / 会话 / 用户输入，明细点进对话页看）+ 按天趋势表 + 三个分布块（按形态 / 按处理方来源 / 按智能体，横向占比条）+「最慢的 N 轮」明细表（红/绿徽标区分失败/正常，`user_message` 截断展示）。近 7/30/90 天切换。**空窗口时自评面板照常渲染四个 0**（藏掉面板就分不清「一次都没自评」和「后端没有这个字段」），低分轮次表则整块消失。非 ADMIN 打开只显示「仅管理员可用」提示，数据也不请求。
- **定时任务弹窗（⏰ 定时）**：列表显示任务名 / 周期 / 状态 / 上次执行（含**结果摘要**，失败时给出原因）/ 下次执行，可行新建、编辑、启停、删除与**「立即执行一次」**（不影响既定排期）。表单里 cron 有常用周期快捷选项，承载会话可下拉选自己的会话。**失败原因必须显示在列表上**：一个悄悄不再执行的任务，比一个报错的任务更难发现。
- **通知面板（🔔）**：铃铛带未读数角标（`GET /api/notification/unread` 轮询，只读一个整数、不拉列表），点开是通知列表 —— 每条给**类型标签**（告警 / 系统 / 定时 / 门禁，按级别染色：`ERROR` 红、`WARN` 琥珀、`INFO` 蓝）+ 标题 + 时间 + 正文摘要，可点进关联对象，另有「全部已读」。**类型映射在界面上必须落到文案**（`ALERT`→告警 而不是把编码铺出来），**两条告警的级别配色必须不同** —— 若「成功率跌破阈值」与「平均耗时偏高」同色，列表里就分不出轻重。**周期报告用「系统」类型而不是「告警」**：日常摘要不该占用「告警」这个信号位。
- **工具审批列表**：命中审批闸门的工具调用会被**拦下**、落一条待确认记录，本轮对话里会说明「这次调用需要你确认」。审批列表按会话展示每条记录的**工具名 / 入参 / 触发它的用户原话 / 状态**，给「批准并执行 / 拒绝」；已拒绝的可「撤销拒绝」。文案要讲清两件事：**批准是有时效的**（过期需重新确认），**拒绝在本会话内一直挡着**（不是只挡这一次）—— 否则用户会以为拒绝没生效。**批准后走的是「重新生成」通路重跑同一轮**（不新增发送逻辑，也不假装能从半路续上）：因为工具调用跑在模型内部，那里没有「暂停等人点按钮」的位置。
- **操作审计（`/user.html` 第三个 tab，仅 ADMIN）**：见上文「用户管理」。
- **教务系统**（`/edu.html`，独立入口）：10 张业务表（科目/老师/班级/学生/学期/课程/节次/排课/考试/成绩）的增删改查 + 4 个关联查询看板（学生成绩明细、成绩统计、班级课表、考试日程），复用深色色板，与 AI 对话页分离。单表页与关联查询页共用同一套顶部搜索条（文本输入 + 外键/枚举下拉 + 查询/重置，按 `tableMeta.search` / `queryMeta.search` 声明渲染）、序号列、右下分页（首页/上一页/下一页/尾页/跳页[/每页条数]）；新增与删除走自绘弹窗。

- **登录**：登录界面全站只有一份 —— 结构在 `js/auth.js`（`Auth.openLogin()`），样式在 `css/auth.css`（`auth-` 前缀变量与类名，与各页样式互不污染）。
  - **首页**（`/index.html`）右上角「登录」按钮：点击**就地弹框**（不跳页），登录后原地变为用户名的下拉菜单（个人信息 / 修改口令 / 用户管理 / 退出登录）。首页自身不含登录逻辑，只放一个 `data-auth-nav` 挂载点。
  - **受限页**（chat / edu / user）**先锁住页面、再确认登录态，最后才决定是否渲染页面**：`Auth.requireLogin()` 在 `<head>` 里同步把整页盖住（`html.auth-locked` + 一句「正在校验登录状态…」），无 token 直接弹登录框；有 token 也先向 `GET /api/auth/me` 确认，**只有确认有效才解除遮罩放行渲染**。所以直接打开 `/chat.html`、`/edu.html` 不会先闪一眼未登录的空壳页（此前只看本地 token、页面照常渲染，等首个接口 401 才弹框，观感是「先进去再被踢出来」）。弹的是**整页观感的登录框**（不透明底，不会把空壳页透出来），登录成功后自动重载当前页；关掉弹框会回到首页 —— 受限页在没有登录态时数据全 401，留在空壳页面上没有意义。运行中 401 同样弹框并提示「登录状态已失效」。需要登录才能走的链接加 `data-auth-required` 即可，点击时由 `Auth.guardNavigation()` **先确认登录态再放行**：未登录就地弹框，已登录也先向 `GET /api/auth/me` 确认 token 仍有效（token 会被服务端单方面作废 —— 过期、账号停用、换密钥重启），确认通过才跳转，避免「先跳进去、再被踢出来」。
  - `/login.html` 只是弹框的「整页模式」外壳（调 `Auth.mountLoginPage()`），保留它是给未登录的深链访问一个落地地址；登录成功后回跳 `?redirect=`，只接受站内路径，防开放重定向。
  - token 存 `localStorage`（键 `my_agent_token`）；退出登录 = 前端丢弃 token（服务端无会话可销毁）。
  - **用户菜单（顶栏唯一入口）**：点击用户名展开下拉，共四项 —— **个人信息**（弹框展示登录名 / 昵称 / 邮箱 / 角色 / 状态 / 最近登录 / 创建时间；先用本地会话快照渲染、再回源 `GET /api/auth/me` 覆盖，避免昵称或角色被改后仍显示旧值。资料**只读**，修改走管理员的「用户管理」）、**修改口令**（弹框三个口令字段，前端即时校验 6~64 位 / 两次一致 / 与原口令不同，提交 `POST /api/auth/password`；成功后**不自动关闭弹框、也不清本地 token** —— 口令要留给用户确认一眼，且 JWT 无状态、改密不影响已签发的 token）、**用户管理**（仅 ADMIN，服务端另有 `@RequireRole` 兜底）、**退出登录**（危险色，置于分隔线之下）。下拉的收起路径三条：再点触发器、点页面空白、按 Esc。
- **用户管理**（`/user.html`，仅 ADMIN）：左侧切换「用户管理 / 角色管理 / 操作审计」。用户列表支持关键词/状态/角色筛选，可行新增、编辑（角色为复选框多选）、重置口令、删除；角色列表支持编码/名称筛选，可新增、编辑（编码锁定）、删除。口令全程只写不读，界面上不提供查看。**操作审计**按时间倒序列出「谁在什么时候把谁的什么改成了什么」（时间 / 操作者 / 动作 / 对象 / 明细 / 来源 IP），可按动作编码筛选，**没有修改或删除入口**；动作列显示的是**文案**（新增用户 / 修改角色…）而不是原始编码，明细里的手机号与邮箱在**入库时**就已脱敏 —— 页面顶部那句「只读留痕，不提供修改/删除」必须留着，否则会被当成「为什么不能删旧记录」的缺陷。

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
- **配置约定**：新增自定义配置段必须写在**顶层**（历史上曾因缩进错误被挂到 `spring:` 下导致整段静默失效）；配置类只需是**顶层类 + `@ConfigurationProperties`**，放在 `org.luo` 包下即由主类的 `@ConfigurationPropertiesScan` **自动注册**，不必改任何清单。两条边界要记牢：**包外**（`org.luo` 以外）与**嵌套内部类**扫描不到，这两种情况需用 `@EnableConfigurationProperties(XxxProperties.class)` 显式补。约束的由来：本项目配置类都是 record / 普通类 + 构造器注入，**漏注册 = 没有 bean = 启动直接失败**，而 `mvn package` 仍 BUILD SUCCESS —— 编译验证挡不住，故新增 `*Properties` 后跑 `.workbuddy/tools/check_properties_registered.py`（判「是否落在扫描包内 / 是否显式登记」，含包外与嵌套两类漏网检测）。
- **DDL 约定**：变更表结构须同步更新 `sql/schema.sql`（建库权威定义）与 `sql/alter.sql`（存量库补列），两处**列状态与建表定义保持一致**。**新增表也必须写进 `alter.sql`**（存量库不会重跑 `schema.sql`），且要放在**文件最前** —— 后段有 `ALTER TABLE task/task_step`，表不存在会先失败在那里。核对按「`schema.sql` 里有哪些表 / 列」全量过，不按「本轮改了几张表」抽查。
- **生效方式**：改动 Java / 配置 / SQL 需重启服务；改动前端静态资源后 `mvn compile` 同步，浏览器 Ctrl+F5。
- **已知缺陷**：Spring AI 2.0.0 的 `stream()` 合并工具调用分片时抛 `NoSuchElementException`（`OpenAiChatModel$ChunkMerger` 对工具调用 `Optional` 直接 `.get()`）。带工具的对话必须用 `call()` 走完工具循环再切片模拟流式。
- `agent_code.md` 由系统自动生成（智能体提示词汇总，原子写入），修改请通过页面操作，勿直接编辑。
