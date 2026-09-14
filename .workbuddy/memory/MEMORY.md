# 项目长期记忆（My_Agent）

> 逐日明细见 `YYYY-MM-DD.md`；**架构细节 / 组件契约 / Chroma 全套坑 → `ARCHITECTURE.md`（按需查阅）**。
> 本文件只留高频结论与红线（注入上限约 3KB，务必克制、勿把细节搬回来）。

## 约定与偏好
- LLM JSON **禁用 Jackson**，一律 Hutool `JSONUtil`/`JSONObject`。
- Maven 在 `D:\software\Java\maven`（仓库同路径，离线 `-o`）；启动器 `.workbuddy/memory/run_mvn.sh`；**离线必加 `-Dmaven.legacyLocalRepo=true`**。
- **打包后不启动项目**：只到 `mvn package`，运行由用户在 IDE 自启。
- **DDL 变更必须同步 `sql/schema.sql` 与 `alter.sql`**（schema=建库权威，alter=补存量）。
- 构建追求零告警：varargs 提示用显式 `(Object[]) tools`（`@SuppressWarnings("varargs")` 实测无效）。
- **前端三条硬约束**：禁小数像素字号；最小 12px、正文 13px 起；辅助文字用变量 `--text-soft`。改静态资源 Ctrl+F5；**UI 改动先 `show_widget` 出预览**再让用户强刷。

## 环境 / 工具坑
- Bash 报 `ls/find/dirname: command not found` → 先 `export PATH="/c/Users/802302/.workbuddy/binaries/PortableGit/versions/1.2.0/usr/bin:$PATH"`。
- 同一条消息对**同一文件**发多个 Edit，第二个会被静默丢弃（报 success 未落盘）→ 同文件改动**分条消息串行 + grep 复核**；**更推荐一次 Python 脚本批量精确替换**（`\r\n` 归一、每处 `assert count==1`、写回按原换行还原）。

## 红线（改代码前先看；完整论证在 ARCHITECTURE.md）
- **`Map.of` 拒 null（NPE 高发）**：`spec.call().content()` 标 `@Nullable`；控制器回显**可选入参**（如 rechunk `overlap`，缺省=null）要回显服务层解析后的生效值。
- **Spring AI 2.0**：`stream()`+`@Tool` 必崩 → 带工具走 `call()` 后切片模拟流式；LLM 超时**只有全局一处**、运行时改不了（`OpenAiChatOptions.timeout` 是死字段）。
- **记忆**：摘要侧与注入侧必须同一份 `MemoryProperties` + 同一 `DbChatMemory.SQL_FETCH_LIMIT`，否则中间段「既不摘要也不注入」；`before()` 既读又写、工具循环不过 advisor 链 → **不做请求级缓存**。
- **`chat_message` 排序一律带 id tiebreaker**（`created_at` 秒级）；**不要依赖 `plusNanos`**（被静默截断）。
- **Chroma 副本写入一律经 `ChromaSyncSupport.afterCommit`**（事务内直写会在回滚后留孤儿向量 / 幽灵引用）；删除侧顺序 **先取 chunkIds → 删 MySQL → 提交后删向量**，不可换；sync 回填 upsert-only、不清孤儿。
- **降级不许静默**：RAG 的 MySQL 回退必有 `LIMIT`（`embedding` 为 1024 维向量 JSON，无界=OOM）；SSE 必须有心跳 + 有限超时。
- **精排阈值口径不得随候选条数变化**：候选非空且精排可用就必须走精排（含仅 1 条），否则阈值从 0.20 静默变 0.25。
- **RAG 引用 / 命中数的唯一接线点**：`ChatService.runRound` 出口调 `trace.citations(...)`（Advisor 采集不到检索产物）——漏掉则 `citations_json` 恒 NULL。
- **打字机总时长必须封顶**：`ChatService` 固定帧间隔 + 分片按长度自适应（`TYPING_MAX_MS`/`TYPING_FRAME_MS`）；勿写固定 4 字/片（延迟随长度线性累加）。
- **鉴权**：`app.api-key`(env `APP_API_KEY`) → 只拦 `/api/**`（`/files/**` 不拦）；**密钥一律不进仓库**，本地值放 `application-local.yaml`（gitignore，经 `optional:file:` 引入），主 yaml 不留空占位符（会覆盖本地值）。
- **SSE 心跳必须用独立调度器**（`sseHeartbeatScheduler` + `scheduleWithFixedDelay`）；跑 Reactor `parallel()` 会连带打字机与全站心跳一起卡死。
- **`/files/**` 免鉴权 + 浏览器按后缀推断 Content-Type** → 附件后缀必须白名单化（`SAFE_EXTENSIONS` 外统一落 `.bin`）；`isImage` 还要挡 `image/svg+xml`。
- **`clearMessages` 必须与摘要水位一起归零**（`summary`/`core_facts`/`summarized_count`），否则摘要指向不存在的历史。
- **`agent_code.md` 归档必须原子写**（临时文件 + `ATOMIC_MOVE`）；`deleteAgent` 解绑会话用**单条批量 UPDATE**（`agent_id` 与 `agent_bind_source` 一起清），别逐条 `updateById`。
