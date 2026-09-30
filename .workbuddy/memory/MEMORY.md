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
- 对外只出 `SysUserVO`（`password` 标 `@JsonIgnore`），角色字段名是 **`roleCodes`**（前端 `Auth.hasRole` 读它，不是 `roles`）；`PageResult.ofMapped(IPage<?>,List<T>)` 专供实体分页→VO。登录态唯一模块 `static/js/auth.js`（token 存 `localStorage['my_agent_token']`），**四页顶栏用户区也由它渲染**（页面只放 `<span data-auth-nav>`，样式只在 `css/auth.css`）。
- **会话按用户隔离（2026-09-29）**：`conversation.user_id`（DDL 同 schema/alter，`INDEX idx_user`）；`ConversationService` 对外方法全带 `Long userId`，越权一律 **404**（与「不存在」不可区分，防 ID 探测）。**身份一律「HTTP 线程取出 → 当参数传下去」**：`AuthContext` 是 ThreadLocal，而 `ChatService.stream/resume` 跑在 `boundedElastic` 上、异步链路读不到；`ChatController` 里还要先 `checkAccess` 把越权挡在建立 SSE 之前（否则只剩一条 SSE `error` 事件、没有 404）。`resolveConversationId` 空值按用户派生 `"default-"+userId`。存量会话 `user_id=NULL` → 对所有人不可见。
- **追踪归属不得写进表（2026-09-30）**：`agent_trace` **不加 `user_id` 列** —— 加列就得把身份一路传进异步落库链路（`RoundTrace` → `ChatService` → `traceExecutor`），破坏「追踪是纯旁路」原则。归属一律查询侧 `JOIN conversation` 判定（`AgentTraceMapper.xml` 的 `selectOwned`/`selectOwnedByTraceId`）。`TraceService` 只暴露带 `userId`/`allUsers` 的重载，**无归属的旧重载已删**（不留「顺手调一下就漏」的入口）；`allUsers=true` 只由 `TraceController` 按 ADMIN 传入。`GET /api/trace/{traceId}` 不存在与非本人统一 **404**。
- **删会话不删 trace**：`deleteConversation` 只清 `chat_message` + `conversation`，`agent_trace` 留着 ⇒ 存在**孤儿记录**。JOIN 天然查不出 → 普通用户不可见、ADMIN 仍可见，是刻意的「旁路留档」。实测：15 条 trace → JOIN 后 13 条。

## 教务（edu）后端分层
> 完整约定（每表分层、命令式接口风格、`BaseBO` 分页口径、XML 规则、`EduQueryService`、`@Resource` 注入）**在 `ARCHITECTURE.md` §十**，改 edu 代码前先读。三条要记牢：
- **接口风格统一为命令式**：`POST /{表}/page`（筛选走 body）、`GET /{表}/list`（只回 `OptionVO{id,label}`）、`GET /{表}/{id}`、`POST /save`、`PUT /update`（id 在 body）、`DELETE /delete/{id}`。
- **分页口径唯一在 `BaseBO`**（缺省 10、上限 100），别在 controller 再声明一份。
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
- **只有一道闸门**：`app.jwt` + `JwtAuthInterceptor` 挂 `/api/**`（白名单只有 `POST /api/auth/login` 与 OPTIONS）。旧的服务级密钥 `app.api-key`/`ApiKeyInterceptor`/`ApiSecurityConfig` **已整段删除**。密钥不进仓库，本地值放 `application-local.yaml`（`optional:file:` 引入），主 yaml 不留空占位符（会覆盖本地值）。
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
- **前置链进度广播（2026-09-30）**：`AgentRoundHandler.handle` 在「路由判定 / 参数抽取 / 检索问句改写」三段**调用前**各发一条 `progress`，消除「发送后到首 token 之间 2~3 秒空白」。要点：只在**确实会跑那段**时才播（路由只未绑定时、抽取只要带 paramSchema、改写只要 RAG 开着）；改写是异步预取，只播「已启动」不播结果。通道是 `handle` 的 `progress` 形参，与 `RoundTrace.reportProgress` 同源，**`progress` 只展示不进记忆**。
- **可观测面板（2026-09-30）**：`ObservabilityController`（`/api/observability/summary`，ADMIN）+ `ObservabilityService` + `ObservabilityMapper`（SQL 在 `mapper/ai/ObservabilityMapper.xml`，六段：overview/daily/byMode/byRouteSource/byAgent/slowest）+ 独立页 `/observability.html`。**`successRate` 不落 SQL 列、不在服务层算**，是 `Overview#successRate()` 派生方法 + `@JsonProperty`（别写 `AVG(status='error')`：空表语义不清且同一事实两处）。**耗时只取总 `elapsed_ms`**，不细分前置链分段（要细分得加列，暂不做）。定位「运行质量」，与成本看板（花钱）互补。

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

## 沟通红线（给用户讲架构 / 扩展方向，2026-09-17 定）
- **别用抽象分层名**（"定义层 / 运行时 / 协作 / 治理"）：用户明确反馈看不懂。用**具象对照 + 项目例子**——"现在什么样 → 加上之后变什么样"。抽象层名交替代方案描述。
