# 项目长期记忆（My_Agent）

> 只留结论与红线。细分去处：**前端/UI → `FRONTEND.md`**；**环境与工具坑 → `TOOLING.md`**；架构细节 → `ARCHITECTURE.md`；8 月及更早 → `ARCHIVE-2026-08.md`。

## 约定与偏好
- **包结构四层**：`org.luo.ai.*`（AI）/ `org.luo.edu.*`（教务）/ `org.luo.system.*`（用户与鉴权）/ `org.luo.common.*`（config：Cors·GlobalExceptionHandler·MybatisPlusConfig；result：RestResult·PageResult；exception：AiBusinessException·AiErrorCode；BaseBO）。主类 `MyAgentApplication` 在 `org.luo`，`@MapperScan({"org.luo.ai.mapper","org.luo.edu.mapper","org.luo.system.mapper"})`；edu 关联查询 XML 在 `resources/mapper/edu/`。新增代码放对目录。
- LLM JSON **禁用 Jackson**，一律 Hutool `JSONUtil`/`JSONObject`。
- **Maven**：`D:/software/Java/maven/3.9.16`；离线 `-o`。Git Bash 直跑 `bin/mvn` 必失败 → 用 `.workbuddy/memory/run_mvn.sh`（联网加 `ONLINE=1`）。
- **打包后不启动项目**：只到 `mvn package`，运行由用户在 IDE 自启。**不改 `target/`**（构建产物，IDE 启动会重建）。
- **DDL 变更必须同步 `sql/schema.sql` 与 `alter.sql`**（schema=建库，alter=补存量）。
- 构建零告警：varargs 用显式 `(Object[]) tools`（`@SuppressWarnings` 无效）。

## 用户系统（system）与登录鉴权
- 表 `sys_user` / `sys_role` / `sys_user_role`（多对多），DDL **独立在 `sql/system.sql`**（幂等，含初始角色 ADMIN/USER）；初始管理员由 `UserBootstrap` 在「表为空」时创建 —— BCrypt 哈希没法手写进 SQL。
- 链路：`JwtTokenService`（Hutool JWT，HMAC-SHA256）+ `JwtAuthInterceptor`（挂 `/api/**`，白名单只有 `POST /api/auth/login` 与 OPTIONS）+ `AuthContext`（ThreadLocal，`afterCompletion` 必清）+ `@RequireRole`（拦截器读 `HandlerMethod` 注解，**不引 AOP**）+ `PasswordHasher`（Hutool BCrypt）。
- **有效期用自定义 claim `expMs`（毫秒），不用标准 `exp`**：Hutool 对 claims 里 `Date` 的序列化单位属其内部约定，依赖它会出「一签发就过期」的静默故障。
- **权限不入 token**：拦截器每请求调 `loadLoginUser` 回库取状态+角色 ⇒ 改角色/停用立即生效（不必等过期）。
- **至少留一个启用的 ADMIN**：删除/停用/摘角色共用 `assertAdminRemains`，防最后一个管理员把自己锁在管理端外。
- 对外只出 `SysUserVO`（`SysUser.password` 标 `@JsonIgnore`）；`PageResult.ofMapped(IPage<?>,List<T>)` 专供实体分页→VO。前端登录态唯一模块 `static/js/auth.js`（token 存 `localStorage['my_agent_token']`），**四页顶栏用户区也统一由它渲染**：页面只放 `<span data-auth-nav>`，内容是「用户名 → 下拉（个人信息 / 修改口令 / 用户管理（仅 ADMIN）/ 退出登录）」，样式只在 `css/auth.css` —— 换肤改一处；页面 `/login.html`、`/user.html`。
- **会话按用户隔离（2026-09-29）**：`conversation.user_id`（关联 `sys_user.id`，DDL 同 schema/alter，`INDEX idx_user`）；`ConversationService` 对外方法全部带 `Long userId`（`createXxx` 写入、`listConversations` 过滤、改名/开关 UPDATE 带 `user_id`、`updatePlannerSwitch`/`deleteConversation` 先 `requireOwned`），越权一律 **404**（与「不存在」不可区分，防 ID 探测）。**身份一律「HTTP 线程取出 → 当参数传下去」**：`AuthContext` 是 ThreadLocal，`ChatService.stream/resume` 执行体在 `boundedElastic` 上，异步链路读不到；`ChatController` 里 `stream/resume` 还要先 `checkAccess` 把越权挡在建立 SSE 之前（否则只剩一条 SSE `error` 事件、没有 404）。`resolveConversationId` 空值兜底按用户派生 `"default-"+userId`，别用公共 `"default"`。存量会话 `user_id=NULL` → 对所有人不可见。**未覆盖**：`/api/trace`、`/api/cost/summary` 仍可跨用户读。

## 教务（edu）后端分层
- 每表 `XxxController` + `XxxService extends IService<T>` / `XxxServiceImpl extends ServiceImpl<M,T>`，**不自拷贝 MP 基类**；**接口风格统一为命令式**（无 RESTful 分支）：`POST /{表}/page`（body = `XxxDTO`，筛选走 body）、`GET /{表}/list`（下拉，只回 `OptionVO{id,label}`）、`GET /{表}/{id}`、`POST /save`、`PUT /update`（id 在 body）、`DELETE /delete/{id}`。
- 分页口径唯一在 `BaseBO`（缺省 10、上限 100）：**有 join 的表** → `XxxDTO`/`XxxVO` + `selectXxxPage` 写 `mapper/edu/*.xml`；**无外键的 subject/semester/period** → `LambdaQueryWrapper`，不写 XML。**通用规则**：mapper 的显式 SQL 一律落 XML（路径 `resources/mapper/<pkg>/XxxMapper.xml`，namespace=接口全类名），禁止 `@Select`/`@Update` 等注解；纯 `BaseMapper` / `LambdaQueryWrapper` 的 mapper 不需要 XML 文件（2026-09-29 已把 `AgentTraceMapper`/`LlmUsageMapper` 的 `@Select` 迁到 XML）。Mapper 返 `List` 必须配 `PageResult.of(ipage, list)`（插件只回填 total/pages，**不回填 records**）；方法名别用 `selectPage`（撞 MP `BaseMapper.selectPage`）。
- 写规则收在各自 Impl，不散到 Controller：`requireUnique(e, selfId)`（新增传 null、编辑排除自身）、删除前 `count()` 其他表做引用校验、冲突与不存在分别抛 `CONFLICT`/`NOT_FOUND`。跨表只读聚合走 `EduQueryService`（`/api/edu/score-detail` 等**顶层**路径，禁改嵌套）与 `EduMetaService.dashboard()`；**`/api/edu/dict` 已删**，选项一律各表自取。4 个关联查询**都是分页接口**（缺省 10、上限 100 的口径只在 `BaseBO`，别在 controller 再声明一份 `MAX_SIZE`），统一回 `PageResult`；筛选参数见 README，不传即不过滤，SQL 全是可选 `<if>`。4 个查询各配 `XxxDTO extends BaseBO`（`ScoreDetailDTO`/`ScoreStatsDTO`/`TimetableDTO`/`ScheduleDTO`），Controller 用 `@ModelAttribute` 收 query 参数、**方法体只转发**，Mapper 收 `@Param("dto")` 返 `List<XxxVO>`，**查询行一律类型化投影、不许回 `Map`**（`ScoreDetailVO`/`ScoreStatsVO`/`TimetableVO`/`ScheduleVO`，XML `resultType` 写全类名），分页对象由 `dto.toPage()` 出；**edu 层注入一律 `@Resource` 字段注入**（别用构造器注入）。
- **统一返回** `RestResult{code,message,data}` + `PageResult{records,total,page,size,pages}`；异常由 `GlobalExceptionHandler` 同形状返回。**改返回体必须同步前端解包**（edu.js 取 `.data`）。

## 红线（改代码前先看）
- **`Map.of` 拒 null**：`spec.call().content()` 标 `@Nullable`；控制器回显**可选入参**用服务层解析后的生效值。
- **Spring AI 2.0**：`stream()`+`@Tool` 必崩 → 带工具走 `call()` 后切片模拟流式；LLM 超时只有全局一处、运行时改不了。
- **记忆**：摘要侧与注入侧同一份 `MemoryProperties` + 同一 `SQL_FETCH_LIMIT`，否则中间段「既不摘要也不注入」；`before()` 既读又写、工具循环不过 advisor 链 → **不做请求级缓存**。
- **`chat_message` 排序一律带 id tiebreaker**（`created_at` 秒级）；不要依赖 `plusNanos`（被静默截断）。
- **Chroma 写入一律经 `ChromaSyncSupport.afterCommit`**；删除侧顺序 **先取 chunkIds → 删 MySQL → 提交后删向量**，不可换。
- **降级不许静默**：RAG 的 MySQL 回退必有 `LIMIT`（`embedding` 是 1024 维 JSON，无界=OOM）；SSE 必须有心跳 + 有限超时，心跳走独立调度器 `sseHeartbeatScheduler`（跑 Reactor `parallel()` 会连带打字机卡死）。
- **精排阈值口径不得随候选条数变化**：候选非空且精排可用就必须走精排（含仅 1 条），否则阈值静默从 0.20 变 0.25。
- **RAG 引用唯一接线点**：`ChatService.runRound` 出口调 `trace.citations(...)`；漏掉则 `citations_json` 恒 NULL。
- **打字机总时长必须封顶**：固定帧间隔 + 分片按长度自适应（`TYPING_MAX_MS`/`TYPING_FRAME_MS`），勿固定 4 字/片。
- **鉴权只有一道闸门（2026-09-29 起）**：`app.jwt`（env `JWT_SECRET`）用户登录态，`JwtAuthInterceptor` 挂在 `/api/**`（白名单只有 `POST /api/auth/login` 与 OPTIONS），不通过即 401。**旧的服务级密钥 `app.api-key`（env `APP_API_KEY`）+ `ApiKeyInterceptor`/`ApiSecurityConfig` 已整段删除** —— 它与登录流程冲突：启用后连 `POST /api/auth/login` 都会被拒（前端登录请求只带 `Authorization`，不带 `X-Api-Key`），用户根本进不来；而 `/api/**` 已由 JWT 全覆盖，机器调用同样得先登录，那道闸门没有额外价值。前端 `app.js`/`edu.js`/`user.js` 里三份 `X-Api-Key` 注入、顶栏「🔑 访问密钥」按钮、`auth.js` 的 `API_KEY_REQUIRED` 分支（及 `style.css` 的 `.key-btn*`）同步移除。密钥不进仓库，本地值放 `application-local.yaml`（`optional:file:` 引入），主 yaml 不留空占位符（会覆盖本地值）。`app.jwt.enabled=false` 可整体关掉登录校验；`JWT_SECRET` 不足 32 字节会每次启动换随机密钥（=重启后全员掉线）；`${JWT_SECRET:默认值}` 的默认值在环境变量**被显式设成空串**时不生效。**本机实测（2026-09-22）**：未设 `JWT_SECRET`（用户级/机器级环境变量里都没有），生效的就是 `application.yaml` 里那个固定默认值 —— 指纹比对通过、重启不换密钥。（据此曾推断「`BAD_SIGNATURE` 只可能来自更早密钥签发的 token」，**该推断同日被推翻**，真因见下面「`JWTSigner` 绝不能共享」。）
- **`app.jwt.expire-minutes` 是 `int`，没绑上就是 0**，而 0 分钟 = token **签发即过期**，症状是「登录成功后进页面又被要求登录」且不抛异常。`JwtTokenService` 已收敛：≤0 → WARN + 回落 720，构造期 INFO 打出生效 TTL。`JwtProperties` 的 `enabled` 默认 true（配错也拦）、`secret` 默认空、`expireMinutes` 原本无初始值 —— 判断鉴权异常先看这几条默认值。
- **401 必须能说清原因**：`JwtTokenService.verify()` 返回 `TokenCheck`（`NO_TOKEN`/`MALFORMED`/`BAD_SIGNATURE`/`MISSING_EXPIRY`/`EXPIRED`/`NO_SUBJECT`），拦截器把它写进响应体 `reason` + 落 WARN，前端用 `resp.clone()` 读 `message` 原样展示。**别再退化成一句笼统的「登录已失效」** —— 那会让「头没到服务端」和「密钥换过」无法区分。
- **控制台报错先看来源前缀**：`VM<数字>` / `<anonymous>` 的脚本**不是页面文件**（`eval`/`Runtime.evaluate` 建的），先怀疑 DevTools / 扩展注入，用特征标识 `grep` 自证归属再决定改不改业务。实测案例（2026-09-22）：用户报的 `reading 'startTime'` 是 DevTools 注入的 **web-vitals 采集包**（`reportAllChanges`/`devToolsReportSoftNavs`/CLS·INP 字段自证），与 edu.js 无关，却被误诊成「班级课表 null 行」并白加一轮加固 —— 「越改问题越多」的来源之一。**证据必须与结论同一处**（勿拿 A 的复现解释 B 的报错）。详见 `FRONTEND.md`。
- **`JWTSigner` 绝不能共享（2026-09-22 定案的真凶）**：Hutool `HMacJWTSigner` 内含**一个** `javax.crypto.Mac`（非线程安全），而它 `verify()` 是「重新签一遍再比对字符串」⇒ 把 signer 存成字段/单例，并发请求会互相污染 HMAC 计算，**同一份合法 token 会被判 `BAD_SIGNATURE`**（连带出 `MALFORMED`）。实测（真实编译产物，Hutool 5.8.38）：16 线程 × 100 共享 signer → `OK=232 / BAD_SIGNATURE=1288 / MALFORMED=80`；共享 signer 并发 `issue()` → 800 次里 600 次「签发即无效」。修法：字段只留密钥 `byte[]`，`issue()`/`verify()` **每次现建** `JWTSignerUtil.hs256(secret)`（改后 1600/1600 通过、签发 800/800 有效）。线上症状：**「登录成功后一进页面点几下就被要求重新登录」**——页面挂载并发发起 5 个接口，总有几个被误判 ⇒ 前端把本来有效的登录态清掉。复现脚本 `.workbuddy/tools/probe_token_concurrency.py`（旧进程实测：同一份 token 并发 120 次 → 103×200 + **17×401 BAD_SIGNATURE**）。
- **`BAD_SIGNATURE` 的判读**：先排除「并发验签不可靠」（上条），再谈「token 由另一把密钥签发」。**串行 curl 永远复现不出并发问题** —— 判定服务端是否可靠要看并发分布，不是单次结果。
- **前端 401 归属**：`reason` 白名单（`TOKEN_FAILURE_REASONS`）决定要不要清本地登录态；**不在白名单里的 401（反向代理/网关拦截、无 reason）一律保留 token**。**清登录态是不可逆动作，不许由单个接口的单次 401 决定** —— 凭证类 401 先经 `confirmSession()`（复核 `/api/auth/me`）确认，复核通过就只记控制台、不弹框；非 401（5xx/网关）从不清 token。细节见 `FRONTEND.md`。
- **手写的 401/403 响应必须与其余接口同形状**：拦截器里**别用 `setCharacterEncoding` + `getWriter`**（Tomcat 会追加 `;charset=UTF-8`，而 Jackson 输出的是 `application/json`）—— 同一 API 两种响应头形状会被误读成「带 charset 的请求才 401」（本轮就发生过）。直接 `getOutputStream().write(utf8Bytes)`。
- **`HttpMediaTypeNotSupportedException` 要显式接**：不接会落到通用兜底显示成 500「服务器内部错误」，把「客户端 Content-Type 用错」指向服务端故障。已接 → 415。
- **`/files/**` 免鉴权 + 按后缀推 Content-Type** → 后缀白名单化（`SAFE_EXTENSIONS` 外落 `.bin`），`isImage` 另挡 `image/svg+xml`。
- **`clearMessages` 必须与摘要水位一起归零**（`summary`/`core_facts`/`summarized_count`），否则摘要指向不存在的历史。
- **工具注册唯一入口 `ToolRegistry`**：注解式走 `ToolProvider`（`@Tool` 反射），动态式（MCP 等运行时才知的工具）走 `ToolCallbackSource`，同名注解式优先。
- **MCP**：官方 starter → `McpToolSource` 收进同一 `ToolRegistry`；`toolCallbacks()` 在**构造期**调用 ⇒ **必须吞异常**。主 yaml 不声明 server，真配置只写 `application-local.yaml`。已接 `filesystem`（根 `D:/Project`，预装 + 绝对路径、**禁 `npx`**）。见 `ARCHITECTURE.md` 八。
- **`agent_code.md` 归档必须原子写**（临时文件 + ATOMIC_MOVE）；`deleteAgent` 解绑会话用**单条批量 UPDATE**（`agent_id` 与 `agent_bind_source` 一起清）。
- **跨轮任务持久化（task/task_step）**：单会话单 RUNNING，开新规划前 `cancelRunning`；落库的 `step_index`/`depends_on` 是**重映射后 specs 连续下标**；续跑不重新规划、只跑 PENDING + `retry_count<2` 的 FAILED；`markStepFailed` 用 `setSql("retry_count=retry_count+1")` 自增。DDL 由用户在 mysql cli 执行 `alter.sql`。

## 沟通红线（给用户讲架构 / 扩展方向，2026-09-17 合并自旧日志）
- **别用抽象分层名**（"定义层 / 运行时 / 协作 / 治理 / 度量"一类）：用户明确反馈看不懂。讲扩展方向时用**具象对照 + 项目具体例子**——"现在什么样 → 加上之后变什么样"，例如"关掉页面会不会忘、换个用户能不能查到别人数据"。抽象层名交替代方案描述，用户凭此判断要不要做。
