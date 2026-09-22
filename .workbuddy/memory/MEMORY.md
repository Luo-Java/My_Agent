# 项目长期记忆（My_Agent）

> 只留结论与红线。细分去处：**前端/UI → `FRONTEND.md`**；**环境与工具坑 → `TOOLING.md`**；架构细节 → `ARCHITECTURE.md`；8 月及更早 → `ARCHIVE-2026-08.md`。

## 约定与偏好
- **包结构三层**：`org.luo.ai.*`（AI）/ `org.luo.edu.*`（教务）/ `org.luo.common.*`（config：Cors·GlobalExceptionHandler·MybatisPlusConfig·ApiKey*；exception：AiBusinessException·AiErrorCode）。主类 `MyAgentApplication` 在 `org.luo`，`@MapperScan({"org.luo.ai.mapper","org.luo.edu.mapper"})`；edu 关联查询 XML 在 `resources/mapper/edu/`。新增代码放对目录。
- LLM JSON **禁用 Jackson**，一律 Hutool `JSONUtil`/`JSONObject`。
- **Maven**：`D:/software/Java/maven/3.9.16`；离线 `-o`。Git Bash 直跑 `bin/mvn` 必失败 → 用 `.workbuddy/memory/run_mvn.sh`（联网加 `ONLINE=1`）。
- **打包后不启动项目**：只到 `mvn package`，运行由用户在 IDE 自启。**不改 `target/`**（构建产物，IDE 启动会重建）。
- **DDL 变更必须同步 `sql/schema.sql` 与 `alter.sql`**（schema=建库，alter=补存量）。
- 构建零告警：varargs 用显式 `(Object[]) tools`（`@SuppressWarnings` 无效）。

## 教务（edu）后端分层
- 每表 `XxxController` + `XxxService extends IService<T>` / `XxxServiceImpl extends ServiceImpl<M,T>`，**不自拷贝 MP 基类**；**接口风格统一为命令式**（无 RESTful 分支）：`POST /{表}/page`（body = `XxxDTO`，筛选走 body）、`GET /{表}/list`（下拉，只回 `OptionVO{id,label}`）、`GET /{表}/{id}`、`POST /save`、`PUT /update`（id 在 body）、`DELETE /delete/{id}`。
- 分页口径唯一在 `BaseBO`（缺省 10、上限 100）：**有 join 的表** → `XxxDTO`/`XxxVO` + `selectXxxPage` 写 `mapper/edu/*.xml`；**无外键的 subject/semester/period** → `LambdaQueryWrapper`，不写 XML。Mapper 返 `List` 必须配 `PageResult.of(ipage, list)`（插件只回填 total/pages，**不回填 records**）；方法名别用 `selectPage`（撞 MP `BaseMapper.selectPage`）。
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
- **鉴权**：`app.api-key`(env `APP_API_KEY`) 只拦 `/api/**`；密钥不进仓库，本地值放 `application-local.yaml`（`optional:file:` 引入），主 yaml 不留空占位符（会覆盖本地值）。
- **`/files/**` 免鉴权 + 按后缀推 Content-Type** → 后缀白名单化（`SAFE_EXTENSIONS` 外落 `.bin`），`isImage` 另挡 `image/svg+xml`。
- **`clearMessages` 必须与摘要水位一起归零**（`summary`/`core_facts`/`summarized_count`），否则摘要指向不存在的历史。
- **工具注册唯一入口 `ToolRegistry`**：注解式走 `ToolProvider`（`@Tool` 反射），动态式（MCP 等运行时才知的工具）走 `ToolCallbackSource`，同名注解式优先。
- **MCP**：官方 starter → `McpToolSource` 收进同一 `ToolRegistry`；`toolCallbacks()` 在**构造期**调用 ⇒ **必须吞异常**。主 yaml 不声明 server，真配置只写 `application-local.yaml`。已接 `filesystem`（根 `D:/Project`，预装 + 绝对路径、**禁 `npx`**）。见 `ARCHITECTURE.md` 八。
- **`agent_code.md` 归档必须原子写**（临时文件 + ATOMIC_MOVE）；`deleteAgent` 解绑会话用**单条批量 UPDATE**（`agent_id` 与 `agent_bind_source` 一起清）。
- **跨轮任务持久化（task/task_step）**：单会话单 RUNNING，开新规划前 `cancelRunning`；落库的 `step_index`/`depends_on` 是**重映射后 specs 连续下标**；续跑不重新规划、只跑 PENDING + `retry_count<2` 的 FAILED；`markStepFailed` 用 `setSql("retry_count=retry_count+1")` 自增。DDL 由用户在 mysql cli 执行 `alter.sql`。
