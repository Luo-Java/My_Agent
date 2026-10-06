# 项目长期记忆（My_Agent）

> 只留**结论与红线**；长文取证与实现细节按主题下沉，别往这里堆。
> 前端/UI → `FRONTEND.md`；环境与工具坑 → `TOOLING.md`；架构与鉴权取证 → `ARCHITECTURE.md`；8 月及更早 → `ARCHIVE-2026-08.md`。

## 约定与偏好
- **包结构四层**：`org.luo.ai.*` / `org.luo.edu.*` / `org.luo.system.*` / `org.luo.common.*`（config：Cors·GlobalExceptionHandler·MybatisPlusConfig；result：RestResult·PageResult；exception：AiBusinessException·AiErrorCode；BaseBO）。主类 `MyAgentApplication` 在 `org.luo`，`@MapperScan` 三个 mapper 包；edu 关联查询 XML 在 `resources/mapper/edu/`。
- LLM JSON **禁用 Jackson**，一律 Hutool `JSONUtil`/`JSONObject`。
- **Maven**：`D:/software/Java/maven/3.9.16`，离线 `-o`；Git Bash 直跑 `bin/mvn` 必失败 → 用 `.workbuddy/memory/run_mvn.sh`（联网加 `ONLINE=1`）。
- **打包后不启动项目**：只到 `mvn package`，运行由用户在 IDE 自启；**不改 `target/`**（IDE 启动会重建）。
- **DDL 变更必须同步 `sql/schema.sql` 与 `alter.sql`**（schema=建库，alter=补存量；用户在 mysql cli 手动执行）。
- 构建零告警：varargs 用显式 `(Object[]) tools`（`@SuppressWarnings` 无效）。

## 用户系统（system）与登录鉴权
- 表 `sys_user` / `sys_role` / `sys_user_role`（多对多），DDL **独立在 `sql/system.sql`**（幂等，含初始角色 ADMIN/USER）；初始管理员由 `UserBootstrap` 在「表为空」时创建 —— BCrypt 哈希没法手写进 SQL。
- 链路：`JwtTokenService` + `JwtAuthInterceptor`（挂 `/api/**`）+ `AuthContext`（ThreadLocal，`afterCompletion` 必清）+ `@RequireRole`（读 `HandlerMethod` 注解，**不引 AOP**）+ `PasswordHasher`。取证见 `ARCHITECTURE.md` §一。
- **权限不入 token**：拦截器每请求回库 `loadLoginUser` 取状态+角色 ⇒ 改角色/停用立即生效。**至少留一个启用的 ADMIN**（删除/停用/摘角色共用 `assertAdminRemains`）。
- **ADMIN 专属接口共五处**：`/api/user/**`、`/api/role/**`、`/api/cost/**`、`/api/eval/**`、`/api/observability/**`；前端入口同步按角色显隐。**判断依据是「有无独立使用场景 / 是否跨会话聚合」，不是「这个接口花不花钱」** —— 成本与可观测是全站聚合、按人拆分做不到；评测的只读接口只服务于跑批一件事，单独放开只会多出一处边界。
- **edu 全模块有意不鉴权（2026-10-06 用户拍板定案）**：`/api/edu/**` 12 个 controller（10 张业务表 CRUD + 4 关联查询 + 看板）**只要求登录，不校验角色、不做归属过滤** ⇒ 任何登录账号可读写全部教务数据（含改 id 的 IDOR）。**这是明确边界，不是遗漏：不要再作为缺陷上报，也不要顺手加 `@RequireRole`。** 若将来多人共用需先拍板：角色级（写接口收权限，改动面 = 写方法 + 前端显隐）vs 数据级（引入「教师—班级」归属，改动面 = 全部 edu mapper）—— 二者不可混用。README §3.1 + API 一览「教务（仅需登录）」已注明。
- 对外只出 `SysUserVO`（`password` 标 `@JsonIgnore`），角色字段名是 **`roleCodes`**（前端 `Auth.hasRole` 读它，不是 `roles`）；`PageResult.ofMapped(IPage<?>,List<T>)` 专供实体分页→VO。登录态唯一模块 `static/js/auth.js`（token 存 `localStorage['my_agent_token']`），**四页顶栏用户区也由它渲染**（页面只放 `<span data-auth-nav>`，样式只在 `css/auth.css`）。
- **会话按用户隔离（2026-09-29）**：`conversation.user_id`（DDL 同 schema/alter，`INDEX idx_user`）；`ConversationService` 对外方法全带 `Long userId`，越权一律 **404**（与「不存在」不可区分，防 ID 探测）。**身份一律「HTTP 线程取出 → 当参数传下去」**：`AuthContext` 是 ThreadLocal，而 `ChatService.stream/resume` 跑在 `boundedElastic` 上、异步链路读不到；`ChatController` 里还要先 `checkAccess` 把越权挡在建立 SSE 之前（否则只剩一条 SSE `error` 事件、没有 404）。`resolveConversationId` 空值按用户派生 `"default-"+userId`。存量会话 `user_id=NULL` → 对所有人不可见。
- **追踪归属不得写进表（2026-09-30）**：`agent_trace` **不加 `user_id` 列** —— 加列就得把身份一路传进异步落库链路（`RoundTrace` → `ChatService` → `traceExecutor`），破坏「追踪是纯旁路」原则。归属一律查询侧 `JOIN conversation` 判定（`AgentTraceMapper.xml` 的 `selectOwned`/`selectOwnedByTraceId`）。`TraceService` 只暴露带 `userId`/`allUsers` 的重载，**无归属的旧重载已删**（不留「顺手调一下就漏」的入口）；`allUsers=true` 只由 `TraceController` 按 ADMIN 传入。`GET /api/trace/{traceId}` 不存在与非本人统一 **404**。
- **删会话不删 trace**：`deleteConversation` 只清 `chat_message` + `conversation`，`agent_trace` 留着 ⇒ 存在**孤儿记录**。JOIN 天然查不出 → 普通用户不可见、ADMIN 仍可见，是刻意的「旁路留档」。实测：15 条 trace → JOIN 后 13 条。

## 教务（edu）后端分层
> 完整约定（每表分层、命令式接口风格、`BaseBO` 分页口径、XML 规则、`EduQueryService`、`@Resource` 注入）**在 `ARCHITECTURE.md` §十**，改 edu 代码前先读。三条要记牢：
- **接口风格统一为命令式**：`POST /{表}/page`（筛选走 body）、`GET /{表}/list`（只回 `OptionVO{id,label}`）、`GET /{表}/{id}`、`POST /save`、`PUT /update`（id 在 body）、`DELETE /delete/{id}`。
- **分页口径唯一在 `BaseBO`**（缺省 10、上限 100），别在 controller 再声明一份。
- **下拉选项（`GET /{表}/list`）有统一上限（2026-10-06）**：`agent.edu.options-max-rows`（默认 500，`EduProperties`）。9 个 `options()` 一律「`probeLimit()`（上限+1，多取一条专门用来探测截断）→ `trim(rows, table)`（裁剪 + WARN，不静默）」。XML 的 4 个 `selectOptions` 首参是 `Page`，**LIMIT 由 `PaginationInnerInterceptor` 注入、XML 一行没动**（`new Page<>(1, n, false)` 的 false 关掉多余 count）；另 5 个走 `page(new Page<>(1, n, false), wrapper).getRecords()`。
- **mapper 的显式 SQL 一律落 XML**（`resources/mapper/<pkg>/XxxMapper.xml`），禁 `@Select` 等注解；**查询行类型化投影，不许回 `Map`**。

### mapper 结果投影的两类运行期陷阱（都在 `ARCHITECTURE.md` §十，改 mapper 必读）
- **A 类 · 列数/列序**：`resultType` 指向 record 时走**列序构造器映射** ⇒ **参数个数必须 == 结果集列数、顺序一致**，多一个就 `ExecutorException`（编译期无感）。**派生指标做成 record 方法 + `@JsonProperty`，别让 SQL 凑列**。
- **B 类 · 聚合值不得为 NULL（2026-09-30 踩到）**：record 分量是 primitive `long`，接不住 null ⇒ `IllegalArgumentException: ... invalid types/values (0,null,0,0,0)`；而 SQL 聚合在**零行**时恰好返回 NULL（`COUNT(*)` 例外）。**计数用 `COUNT(CASE WHEN ... THEN 1 END)`，求和/均值用 `COALESCE(SUM(...),0)`/`COALESCE(AVG(...),0)`**。⚠ 只在「窗口内一行都没有」时触发，有数据的窗口永远测不出来（默认近 30 天正常、点「近 7 天」500）。接收方是包装类（`Long`/`Double`/`BigDecimal`）时不炸，且成绩统计的 NULL 有语义（「这组没成绩」≠「平均 0 分」）—— 判据是「接收方能不能接住 null」。
- **自检**：改 mapper 或改投影类型后跑 `check_record_projection_columns.py`（A 类）+ `check_mapper_null_aggregates.py`（B 类）。

## 提示词回归评测（ai，2026-09-29）
- 目的：`prompts.yaml` 改动此前靠手感，评测补上「跑批 + 断言」这层壳，**零侵入对话链路**（复用现成路由/规划，只换入口调用并断言）。组成：`EvalService` + `EvalController`（`/api/eval/cases|run|batches|compare`）+ `evalExecutor`（core 4 / max 8），用例集 `classpath:eval-cases.yaml`，会话 ID 固定 `__eval__`。
- **三态必须分开**：`passed` / `failed`（提示词质量问题）/ `configError`（**用例自己写错**）。混在一起会把「用例维护失误」误读成「模型变笨」；落库分 `passed`、`config_error` 两列，`failed` 只算 `passed=0 AND config_error=0`。
- 用例集用 Hutool `YamlUtil` **必须显式 UTF-8 Reader**（否则中文用例名乱码）；解析失败抛 400，不当「0 条用例」静默放过。
- **对比优先看 `broken`**（上批通过、本批失败）—— 比总通过率更能定位回归。批次只留最近 20 批；清理 SQL **必须套派生表**（MySQL 不许 `DELETE` 子查询直接引用被删的表，报 1093）。
- `/api/eval/**` 整类限 ADMIN（跑批＝真实模型调用＝花钱）。
- `EvalBatchSummary` 选 **bean 而非 record**（MyBatis 结果投影，setter 映射不依赖 `-parameters`）。

## 红线（改代码前先看）
### 鉴权
- **只有一道闸门**：`app.jwt` + `JwtAuthInterceptor` 挂 `/api/**`（白名单只有 `POST /api/auth/login` 与 OPTIONS）。旧的服务级密钥 `app.api-key`/`ApiKeyInterceptor`/`ApiSecurityConfig` **已整段删除**。密钥不进仓库，本地值放 `application-local.yaml`（`optional:file:` 引入），主 yaml 不留空占位符（会覆盖本地值）。**2026-10-06 起主 yaml 连 `secret` 键都不写**（此前内置固定 hex 默认值 ⇒ 拿源码即可离线伪造 ADMIN token）；`app.jwt.secret` 只出现在 local yaml 或环境变量 `JWT_SECRET`，两处都没有才回落随机密钥 + WARN。**改密钥会让所有已签发 token 失效**（需重新登录）。校验用 `.workbuddy/tools/ValidateConfig.java`（SnakeYAML 实解析，不启动应用）。
- **有效期用自定义 claim `expMs`（毫秒），不用标准 `exp`**；**`app.jwt.expire-minutes` 没绑上就是 0 = 签发即过期**（症状：登录成功又被要求登录，且不抛异常）。
- **`JWTSigner` 绝不能共享**：Hutool `HMacJWTSigner` 内含非线程安全 `Mac`，存字段/单例会让并发验签互相污染 ⇒ 合法 token 被判 `BAD_SIGNATURE`。字段只留 `byte[]`，每次现建。**串行 curl 永远复现不出并发问题**。
- **401 必须说清原因**：`TokenCheck` 六态写进响应体 `reason` + 落 WARN，前端原样展示。**手写的 401/403 别用 `getWriter`**（Tomcat 追加 `;charset=UTF-8`，与其余接口形状不一致）→ 用 `getOutputStream().write(utf8Bytes)`。
- **前端 401 归属**：`reason` 白名单 `TOKEN_FAILURE_REASONS` 决定要不要清本地登录态；**不在白名单里的 401 一律保留 token**；清态前必须经 `confirmSession()` 复核。见 `FRONTEND.md`。

### 对话链路
- **`Map.of` 拒 null**：`spec.call().content()` 标 `@Nullable`；控制器回显**可选入参**用服务层解析后的生效值。
- **Spring AI 2.0**：`stream()`+`@Tool` 必崩 → 带工具走 `call()` 后切片模拟流式；LLM 超时只有全局一处、运行时改不了。
- **记忆**：摘要侧与注入侧同一份 `MemoryProperties` + 同一 `SQL_FETCH_LIMIT`，否则中间段「既不摘要也不注入」；`before()` 既读又写、工具循环不过 advisor 链 → **不做请求级缓存**。
- **步骤间产物有配额**：`PlannerRoundHandler.buildStepInput` 按 `min(upstream-max-chars, upstream-total-chars/前驱个数)` 截断（默认 4000/12000），超额保留前段 + 显式省略标注 + WARN；配额**只作用于「注入下一步的输入」**，不影响 `task_step.output` 落库与最终回复。此前是无界全量拼接。该配置**不支持关闭**。
- **规划步骤 system 分三档**：首层吃「已确认参数」、末层（汇总步）追加近期窗口 `historyContext`、**中间步默认不注入长期记忆**（开关 `agent.planner.isolate-middle-steps`）。理由：中间步是「拿前驱产物做自己那一段」，会话摘要多半是别的话题会带偏。**RAG 资料不参与隔离**（每步 query 不同）—— 别顺手一起关。
- **`chat_message` 排序一律带 id tiebreaker**（`created_at` 秒级，同轮 user/assistant 时间完全相同）；不要依赖 `plusNanos`（被静默截断）。
- **跨轮任务持久化（task/task_step）**：单会话单 RUNNING，开新规划前 `cancelRunning`；落库 `step_index`/`depends_on` 是重映射后连续下标；续跑不重新规划、只跑 PENDING + `retry_count<2` 的 FAILED；`markStepFailed` 用 `setSql("retry_count=retry_count+1")`。
- **五条规划通路共用同一个执行入口 `POST /api/chat/task/resume`**（2026-10-06）：首次规划、「先看计划」确认、断点续跑、套用模板、**审批放行**。原因是执行侧只按库里 `task_step` 重建执行 ⇒「改库即生效」，**不存在第二套执行逻辑**；模板套用（`PlannerRoundHandler#applyTemplate`）与审批批准（`POST /api/chat/task/approve` 只落标记）因此都只做「落库 / 打标记」。**改续跑 = 同时改这五条通路的语义**。
- **规划模板（task_template，2026-10-06）**：把跑顺的规划**步骤骨架**沉淀为可复用资产，套用**零模型调用**（省掉一次规划往返）。三条设计红线：① `steps_json` 存**快照**、**不引用 `task_step`**（那张表带运行态列，且 `replanTail` 会删改/重排行 ⇒ 引用式会被连带破坏）；② 步骤只记 `agentCode` 不记展示名（智能体改名不该让模板失效）；③ 依赖落库前经 `TaskStep.strictPriorDeps` 净化，只留**严格前序**（`0<=d<self`）—— 执行层按依赖分层推进，指向自身/后序会让该步永远等不到前驱；限死严格前序同时天然无环，**故不需要环检测**。模板按 `user_id` 隔离，取不到即 404。`TaskStep.Def` 是「执行前的步骤定义」的唯一结构（重规划与模板骨架共用），不要另建同构 record。
- **计划步骤编辑 / 局部重规划（2026-10-06）**：两者都**结束于同一个执行入口** —— 执行侧（`resumeTask`）从 `task_step` **读库重建** specs ⇒「改库即生效」，**不需要第二套执行逻辑**（与「规划暂停复用续跑」同一套路，本项目**已复用五次**）。
  - `PUT /api/chat/task/step`：**只收 PENDING**；依赖**只能指向前序**（`0 <= d < stepIndex`）—— 执行层分层推进的前提，同时天然杜绝依赖环。
  - `POST /api/chat/task/replan`：只重排「**第一个未成功步及其之后**」，**改完不自动执行**（保持「先看 / 再改 / 后跑」）。
  - ⚠ **`step_index` 必须连续**：续跑依赖映射用的是**列表位置**（`resumeTask` 的 `remap[i]`）而非 step_index，留空洞会把越界依赖**静默过滤成「无依赖」** ⇒ `TaskService.replanTail` 必须把保留段压紧到 `0..k-1` 并重映射 depends_on。
  - 重规划**不外传上游产出原文**（段内首层「无依赖」步的输入只有原始目标），故提示词要求模型把上游结论**内联进 instruction**；`depends_on` 序列化的唯一约定点在 **`TaskStep.depsToJson/depsFromJson`**（别再各写一份）。
- **步骤审批点（2026-10-06）**：`task_step.approval_required` / `approved` 两列，**独立于 `status` 状态机** —— status 说「跑到哪了」、审批说「允不允许跑」，两者正交。若把「待审批」做成一个 status 值，`markStepRunning`、`isSettled`、续跑重试判定等**所有既有分支**都要为它加一个态；两列正交后执行侧只需在「找 ready 步骤」处插一道闸门（`ApprovalGate`）。
  - **闸门粒度 = 整条流水线暂停**（不是只挡该步、兄弟步照跑）：后者会让用户看到「卡住那步后面的步骤先出了结果」，流水线因果顺序难解释；「到此为止」才符合审批点直觉。`ready` 按下标升序 ⇒ 取最靠前那步。
  - **specs 下标 ≠ `step_index`**：续跑时被删掉的 agent 会被跳过（specs 变短），而审批/编辑接口按 `step_index` 落库 ⇒ 必须单列 `specStepIndex` 换算，否则「显示第 3 步、实际改到第 4 步」。
  - **取消审批必须一并把 `approved` 归零**：否则「先勾→批准→取消→再勾」会沿用旧批准，用户以为重新设了关卡、实际已被静默放行。
  - 批准后走的就是断点续跑；终止走 `POST /api/chat/task/cancel`（置 `CANCELLED`）。审批暂停提示**进记忆**（`savePlannerExchange`，与「先看计划」同口径）。SSE 事件 `approval` 先于 `token`。
- **前置链进度广播（2026-09-30）**：`AgentRoundHandler.handle` 在「路由判定 / 参数抽取 / 检索问句改写」三段**调用前**各发一条 `progress`，消除「发送后到首 token 之间 2~3 秒空白」。要点：只在**确实会跑那段**时才播（路由只未绑定时、抽取只要带 paramSchema、改写只要 RAG 开着）；改写是异步预取，只播「已启动」不播结果。通道是 `handle` 的 `progress` 形参，与 `RoundTrace.reportProgress` 同源，**`progress` 只展示不进记忆**。
- **工具分三类（2026-10-06）**：`ToolRegistry` 除注解式（`ToolProvider`）与动态来源（`ToolCallbackSource`，构造期快照）外，新增**第三类「动态工具」** —— 实例依赖调用方上下文，只登记 `ToolInfo` 进 `availableTools`（供前端勾选）、**不入 `byName`**，由 `ChatComposer.decorateRequest` 每轮现构并合并进 `tools()`（分两次调 `tools()` 后者会覆盖）。目前只有 `call_agent`（`SubAgentTool`）：**白名单专属**（`tools_json=NULL` 全量**不含**它，用 `dynamicToolRequested` 判定）、**只做一层**（子智能体挂其静态工具、不含本工具 ⇒ 天然无递归与调用环，故不需要调用链追踪/深度配置）。加新动态工具要同步 `DYNAMIC_TOOL_NAMES`。`SubAgentTool` 走 `AgentMapper` 而非 `AgentService`（后者 → `PlannerService` → `ChatComposer` → 本类 会成构造器环）。
- **规划暂停复用续跑（2026-10-06）**：`conversation.planner_confirm=1` 时 `PlannerRoundHandler` 只落库（task=RUNNING、步骤全 PENDING）不执行，SSE 推 `plan` 事件 + 计划文本；「确认执行」走的就是 `POST /api/chat/task/resume` —— **改续跑逻辑即改确认执行逻辑**，没有第二条通路。`plan` 事件不落库，刷新后卡片消失，靠顶部 resume-bar（文案按 `doneSteps` 分流）恢复入口。事件顺序必须 plan 先于 token。
- **可观测面板（2026-09-30）**：`ObservabilityController`（`/api/observability/summary`，ADMIN）+ `ObservabilityService` + `ObservabilityMapper`（SQL 在 `mapper/ai/ObservabilityMapper.xml`，六段：overview/daily/byMode/byRouteSource/byAgent/slowest）+ 独立页 `/observability.html`。**`successRate` 不落 SQL 列、不在服务层算**，是 `Overview#successRate()` 派生方法 + `@JsonProperty`（别写 `AVG(status='error')`：空表语义不清且同一事实两处）。**耗时只取总 `elapsed_ms`**，不细分前置链分段（要细分得加列，暂不做）。定位「运行质量」，与成本看板（花钱）互补。

### 对话形态：三条链路与五个会话级开关（ai，2026-10-06）
- **`conversation` 现有五个会话级开关**：`rag_enabled` / `planner` / `planner_confirm` / `review_enabled`（并行评审）/ `cross_session`（跨会话搜索）。都是**纯布尔偏好**，拨动即写回、刷新保持；`planner` 与 `agentId` 互斥（前端置灰 + 后端防御），`planner` 与 `review_enabled` **互斥**（见下）。`cross_session` 与任何形态**可叠加**（它是检索增强，不是编排形态）。
- **链路优先级固定：规划 > 评审 > 普通**（`ChatService.selectHandler`）。规划与**并行评审**都是「**编排形态**」（规划决定**怎么拆**、评审决定**谁来答**），同时开着会让「本轮走哪条链路」取决于读取顺序 ⇒ 开启任一方时后端**顺手关掉另一方**（`updatePlannerSwitch` 置 `reviewEnabled=false`、`updateReviewEnabled` 置 `planner=false`），`selectHandler` 再留一道同序防御。前端开关**双向联动**（`onPlannerChange` / `onReviewChange` 各自把对方 ref 置 false），否则会出现「开关还亮着、顶部徽标还显示规划，而实际走的是评审」的 UI 与行为脱节。`agent_trace.mode` 因此有三态 `agent` / `planner` / **`review`**，`route_source` 对应新增 **`REVIEW`**（Java 常量 + SQL 注释 + 前端标签映射多处同步）。
- **并行评审（`ReviewRoundHandler`）**：候选由 LLM 从智能体库挑，**会话已绑定的智能体固定占一席**（用户选了它就说明要它参与），其余按问题类型补足 —— 固定名单会让「问数学题也拉翻译官进来」。四条红线：① 库里 `<2` 个智能体直接返回空、**不调模型**（一个候选构不成「评审」）；② 候选**互相不可见**（可见就退化成串行接力，后答者跟着前面的思路走）；③ 候选**共用同一份 kb/recall**（各取一次：既控成本，也保证「谁答得好」里不混进「谁拿到的资料多」）；④ 用 `internalChatClient()`（**无记忆**），最终回复仍由既有出口落库 → 本项目第 7 次复用「一次落库 + 现成通路」接缝。并行跑在 `plannerStepExecutor`（与规划步骤**同池** —— 两者互斥，不会互相抢），`future.get(timeout)` 超时即 `cancel(true)` 按弃权处理。落库的是**最终回答**、候选只走 `review` 事件（不落记忆）。**四种失败分支全部显式播报**（候选<2 / 仅 1 个成功 / 全部失败 / 裁决失败），没有一种静默假装成功。
- **内容安全护栏（`ContentSafetyService`）**：输入/输出两侧规则收在**一处**，构造期一次编译成 `Pattern`（单条非法正则 WARN 后跳过，不让一条写错的规则拖垮整个功能）。输入侧在 `ChatController` 的**配额之前**拦（→ 422 `CONTENT_BLOCKED`，**零模型调用**）；输出侧由 `ChatService.screen()` 在推送与展示前替换文案，**所有出口都要过**（含 `clarified` 分支与 `chat()` 的 return）。**两条已知边界写进类注释与 README，别再当缺陷上报**：(a) 字面正则**不是语义审核**（换说法/谐音可绕过）；(b) 替换**只作用于推送与展示**，库里仍是模型原始输出（普通对话的消息由记忆 Advisor 在模型返回时即落库；要让落库也替换得下沉到 Advisor 改写 response，会牵动 token / 工具元数据重建，本版本刻意不做）。命中只记 WARN、**不落原文**（拦截记录不该成为敏感内容的新副本）。`agent.safety.enabled` 默认 false。
- **跨会话搜索（`CrossSessionSearchService`）**：`cross_session=1` 时每轮先用 LLM 抽关键词，在**本人其他会话**的历史消息里做**关键词字面召回**（SQL 层强制 `user_id` 过滤 + 排除当前会话）→ 注入 system + 推 `recall`。要点：① **为什么不用向量** —— 会话消息逐轮写入，走向量检索意味着每条消息落库都要多一次 embedding + 新增一套副本维护链路 + 与 `kb_chunk` 共用空间互相干扰；代价是**换个说法就召回不到**，这是明确接受的边界（写进了 `CrossSessionProperties` Javadoc）；② 关键词进 SQL 前**必须清洗**（剥行首编号/引号 → 去 `%` `_` `\` → 截断 20 字），否则 LLM 吐一个 `%` 就是全表匹配；③ **`searchOwned` 的排序表达式写在 `ORDER BY` 里、命中数在 Java 侧重算** —— 若把命中数作为列返回，`resultType` 就得从 `ChatMessage` 换成 record（**列数/列序必须严格对应**，见 §十 A 类陷阱），为省几行 SQL 去换一个运行期才炸的雷不划算；④ 召回结果**刻意不落库**（`RecallHit` 无表）：它是「本轮检索到了什么」，同一会话不同轮次结果完全不同，存下来只是一堆只对那一轮有意义的快照。失败只降级为「本轮不带历史」（与 RAG 同一原则），但**四种情况都有明确播报**（未提取到关键词 / 未回忆起 / 回忆起 N 条 / 召回失败），让用户能区分「真没有」与「功能没生效」。提词与召回都计入 `llm_usage`（`RECALL` 用途，需同步 `LlmUsage` 注释与 `CostService.PURPOSE_LABELS`）。


### 治理 · 成本配额（ai，2026-10-06）
- **`QuotaService` 按「用户 × 自然日」汇总 token**：`QuotaMapper.sumUserTokensSince` 把 `agent_trace`（回答+工具循环）与 `llm_usage`（裸调用）两表 `UNION ALL` —— 与成本看板同一口径。**两表都没有 `user_id`**，归属靠 `JOIN conversation`；因此「无会话的调用」不计入任何人的配额。
- **`SUM` 零行返 NULL ⇒ `COALESCE(SUM(...),0)` 不可省**：接收方是 primitive `long`，NULL 一路传下去或炸或静默判 false（与 §十 B 类陷阱同源）。
- **超限必须明确报错、不许静默降级**：`AiErrorCode.QUOTA_EXCEEDED(429)`，消息含「已用 / 上限 / 何时重置」三要素。不换小模型、不悄悄截断历史、不假装成功。**这是红线**。
- **闸门短路**：`agent.quota.enabled=false` 时**完全不查库**（`enabledOn()` 短路），行为与不配这一节一致；`exempt-admins` 默认 true（否则管理员把额度用满后连界面都进不去调回配额 —— 自锁）。新增 `GET /api/chat/quota`（本人快照，**不要求 ADMIN**）。
- **`guarded(quota, body)` 不浪费一次对话**：`stream()`/`resume()` 超限时只 `Flux.just(error)`、**不订阅 `body`**（`Flux.create` 的 lambda 惰性 ⇒ 不会白跑一轮）。
- **fail-open 与「不许静默降级」的分界（本轮刻意定清）**：统计 SQL 出错时 **WARN + 放行**。理由是**配额是治理手段、不是正确性保障** —— 宁可统计故障时放过用户，也不因算不出用量就把所有人的正常对话挡住；且日志明确记了原因，**不是「静默」降级**。真正不许静默的是**超限本身**。
- **已知边界（已写进 README 与 Javadoc，别再当缺陷上报）**：① 检查在**每轮开始前**，单轮用量无法预知 ⇒ 可能小幅超出；② `/api/eval/**` 跑批**不经这道闸门**（它自带 ADMIN 限制）。

### RAG / 向量 / 降级
- **Chroma 写入一律经 `ChromaSyncSupport.afterCommit`**；删除侧顺序 **先取 chunkIds → 删 MySQL → 提交后删向量**，不可换。
- **降级不许静默**：RAG 的 MySQL 回退必有 `LIMIT`（`embedding` 是 1024 维 JSON，无界=OOM）；SSE 必须有心跳 + 有限超时，心跳走独立调度器 `sseHeartbeatScheduler`（跑 Reactor `parallel()` 会连带打字机卡死）。
- **精排阈值口径不得随候选条数变化**：候选非空且精排可用就必须走精排（含仅 1 条），否则阈值静默从 0.20 变 0.25。
- **RAG 引用唯一接线点**：`ChatService.runRound` 出口调 `trace.citations(...)`；漏掉则 `citations_json` 恒 NULL。
- **打字机总时长必须封顶**：固定帧间隔 + 分片按长度自适应（`TYPING_MAX_MS`/`TYPING_FRAME_MS`），勿固定 4 字/片。
- **工具注册唯一入口 `ToolRegistry`**：注解式走 `ToolProvider`（`@Tool` 反射），动态式（MCP）走 `ToolCallbackSource`，同名注解式优先；`toolCallbacks()` 在**构造期**调用 ⇒ **必须吞异常**。主 yaml 不声明 server，真配置只写 `application-local.yaml`。见 `ARCHITECTURE.md` 八。

### 其他
- **`clearMessages` 必须与摘要水位一起归零**（`summary`/`core_facts`/`summarized_count`），否则摘要指向不存在的历史。
- **`HttpMediaTypeNotSupportedException` 要显式接**（否则落到通用兜底显示成 500）；已接 → 415。
- **`/files/**` 免鉴权 + 按后缀推 Content-Type** → 后缀白名单化（`SAFE_EXTENSIONS` 外落 `.bin`），`isImage` 另挡 `image/svg+xml`。
- **`agent_code.md` 归档必须原子写**（临时文件 + ATOMIC_MOVE）；`deleteAgent` 解绑会话用**单条批量 UPDATE**。
- **控制台报错先看来源前缀**：`VM<数字>` / `<anonymous>` 的脚本**不是页面文件**，先怀疑 DevTools / 扩展注入。**证据必须与结论同一处**。见 `FRONTEND.md`。
- **flex 列容器（`flex-direction:column` + 固定高度 + 子项条数不定）的子项必须显式 `flex: 0 0 auto`**（2026-10-06）：默认 `flex-shrink:1` 会被 flex **等比压扁**，且压到刚好贴合容器 ⇒ **连滚动条都不出现**，只看到行高塌陷、文字被 `overflow:hidden` 裁掉后重叠（用户报的「数据一多就变形」）。另：量塌陷要量**子项高度 + 相邻行 top 差**，量行本身（`.trace-row`）恒为原值、会误判成「没塌」。见 `FRONTEND.md`。
- ⚠ **`.workbuddy/` 不在 `.gitignore` 里** ⇒ memory/tools 全都会进版本库。**密钥类备份绝不能放这儿**（2026-10-06 建过一个 `application-local.yaml.bak`，含 DB 口令 + API Key + JWT 密钥，发现后立即删除）。
- **Git Bash 下往 YAML 追加内容别用 `printf`**：格式串里的 `\n` 会被 MSYS 破坏成字面 `/n`，多行粘成一行。改用 Write 工具或 Python 写文件。

## 沟通红线（给用户讲架构 / 扩展方向，2026-09-17 定）
- **别用抽象分层名**（"定义层 / 运行时 / 协作 / 治理"）：用户明确反馈看不懂。用**具象对照 + 项目例子**——"现在什么样 → 加上之后变什么样"。抽象层名交替代方案描述。
