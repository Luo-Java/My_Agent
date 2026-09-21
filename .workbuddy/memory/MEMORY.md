# 项目长期记忆（My_Agent）

> 明细：8 月见 `ARCHIVE-2026-08.md`（已蒸馏），当月见 `YYYY-MM-DD.md`；**架构细节 → `ARCHITECTURE.md`**。只留结论与红线（≤3.5KB）。

## 约定与偏好
- LLM JSON **禁用 Jackson**，一律 Hutool `JSONUtil`/`JSONObject`。
- **Maven**：`D:/software/Java/maven/3.9.16`，仓库 `.../repository`；离线 `-o`。**Git Bash 直跑 `bin/mvn` 必失败** → 用 `.workbuddy/memory/run_mvn.sh`（联网加 `ONLINE=1`）。
- **打包后不启动项目**：只到 `mvn package`，运行由用户在 IDE 自启。
- **DDL 变更必须同步 `sql/schema.sql` 与 `alter.sql`**（schema=建库，alter=补存量）。
- 构建追求零告警：varargs 用显式 `(Object[]) tools`（`@SuppressWarnings` 无效）。
- **前端三条硬约束**：禁小数像素字号；最小 12px、正文 13px 起；辅助用 `--text-soft`。UI 改动先出预览。
- **前端**：`index.html`=首页、`chat.html`=聊天页，**共用深色色板**（换肤改两处 `:root`）；**`#app` 禁加 `backdrop-filter`/`transform`/`filter`**（为 fixed 弹窗建包含块 → 被圆角裁）；`--primary` 只做填充、文字用 `--primary-text`；ECharts 走 `agent-dark`；改 `style.css` 先导出选择器清单、只改值不删选择器。
- **原生 `<select>` 是坑**：底色写 `background-color`（简写静默丢自绘箭头）；`option` 必须显式写色（`color-scheme:dark` 染不深弹层）。**弹层容器由系统绘制、CSS 完全不可控**（配色/边框/圆角/开合动画都不听，`option` 自定义色还会导致点开闪一帧）→ 要完全可控只能换自绘组件。
- **前端依赖禁回外网 CDN**：Vue/marked/echarts 一律走 `static/js/lib/`（CDN 挂 → Vue 未定义 → 页面停在原始模板，4 个 fixed 弹窗叠加且点不动）；`#app` 的 `v-cloak`、`#boot-tip` 勿删。
- **静态资源配了 `no-store: true`**（`spring.web.resources.cache.cachecontrol`）→ 前端改完普通刷新即生效，不必 Ctrl+F5；上线再删。**「改了看不到」三层排查：源文件 → 产物 → 浏览器缓存**。
- **截断用固定高度，别用 `-webkit-line-clamp`**：卡片描述在 flex 布局下若上方元素缺失（如无 agent_code 的卡片少一行），`display:-webkit-box` 会**整段不渲染**（不是截断）→ 用 `min-height/max-height` + `overflow:hidden`。
- **固定高度 flex 容器 + 内部滚动区必须补 `min-height:0`**：子项默认 `min-height:auto` 不收缩，内容顶破 `max-height` 后被父级 `overflow:hidden` 静默裁掉（弹窗字段被裁那次）→ `.modal-body { flex:1 1 auto; min-height:0; }`。
- **开关/状态类控件禁用高饱和实底 + 外发光**：整块亮色填充会让「状态」抢过主按钮（「发送」）的权重。关闭态无底无框，开启态用淡色底 14% + `inset` 内描边 + 着色文字。**同类控件共用一套规则、用 `--tg-rgb`/`--tg-fg` 变量区分色相**，不要复制两份样式。图标用 `currentColor` 描边 SVG（emoji 全彩会打乱色板，且小尺寸下细节糊）。
- **无会话空白页是 `chat.html` 的默认入口**（首页进入 / 刷新都是）→ 会话级操作必须处理 `currentId` 为空：开关类只改前端状态、由 `send()` 建会话后补写回（`newConversation()` 会复位开关）；**别在拨开关时建会话**（会留没说过话的空会话）。漏掉的表现 = 开关「点不动」（v-model 置 true 被 `@change` 改回）或选择被静默丢弃（RAG 不写回会话则本轮不检索）。

## 环境 / 工具坑
- Bash 报 `dirname: command not found` → 先 `export PATH="/c/Users/802302/.workbuddy/binaries/PortableGit/versions/1.2.0/usr/bin:$PATH"`。
- 同一文件同条消息发多个 Edit，第二个被静默丢弃 → **改用 Python 脚本批量精确替换**（`\r\n` 归一、每处 `assert count==1`）；含反引号的代码**必须写成 .py 执行**（`python -c` 会被 shell 当命令替换）。
- **Bash 命令可能被执行两次**（sandbox bypass 后重跑）→ 补丁脚本靠「精确 old→new + 断言」天然幂等；**看到断言失败先核对文件是否已是目标状态**，别盲目重跑。症状还包括：**只看到第二次的 traceback，第一次的成功日志被整个吞掉**（stdout 只剩命令末尾那句 echo）—— 所以「脚本一行 OK 都没打印」也不代表没写盘。
- **无头 Chrome 会复用旧 CSS**：改样式后连拍截图**字节数完全相同 = 命中缓存**（换 `--user-data-dir`、`--disable-http-cache` 都无效）。每次都改 `<link>` 的 `?t=xxx`，或用 **CDP 探针读 computed style** 判断生效与否。Chrome 在 `C:/Program Files/Google/Chrome/Application/chrome.exe`；静态页优先 `file://`（相对路径引 CSS），`python -m http.server` 用 `&` 起会被回收。
- **别 `rm -rf` 成批文件**：>50 个（按 turn 计）触发 safe-delete 确认弹窗，命令 `EXIT=1` 且 `&&` 短路（打包就因此白跑过一次）。两个惯犯：无头 Chrome 的 profile 目录 → **固定 `%TEMP%/wb-chrome-prof` 长期复用、用完不删**；`target/classes/**` → 交给 Maven 覆盖，不手清。
- **HTML 区间切片定位闭合标签必须锚定行首缩进**（`re.search(r"^        </div>$", …, re.M)`）——裸子串会命中更深缩进的同类标签。

## 红线（改代码前先看）
- **`Map.of` 拒 null**：`spec.call().content()` 标 `@Nullable`；控制器回显**可选入参**用服务层解析后的生效值（如 rechunk `overlap`）。
- **Spring AI 2.0**：`stream()`+`@Tool` 必崩 → 带工具走 `call()` 后切片模拟流式；LLM 超时**只有全局一处**、运行时改不了。
- **记忆**：摘要侧与注入侧必须同一份 `MemoryProperties` + 同一 `SQL_FETCH_LIMIT`，否则中间段「既不摘要也不注入」；`before()` 既读又写、工具循环不过 advisor 链 → **不做请求级缓存**。
- **`chat_message` 排序一律带 id tiebreaker**（`created_at` 秒级）；**不要依赖 `plusNanos`**（被静默截断）。
- **Chroma 写入一律经 `ChromaSyncSupport.afterCommit`**（事务内直写留孤儿向量）；删除侧顺序 **先取 chunkIds → 删 MySQL → 提交后删向量**，不可换。
- **降级不许静默**：RAG 的 MySQL 回退必有 `LIMIT`（`embedding` 是 1024 维 JSON，无界=OOM）；SSE 必须有心跳 + 有限超时。
- **精排阈值口径不得随候选条数变化**：候选非空且精排可用就必须走精排（含仅 1 条），否则阈值从 0.20 静默变 0.25。
- **RAG 引用唯一接线点**：`ChatService.runRound` 出口调 `trace.citations(...)`；漏掉则 `citations_json` 恒 NULL。
- **打字机总时长必须封顶**：固定帧间隔 + 分片按长度自适应（`TYPING_MAX_MS`/`TYPING_FRAME_MS`），勿固定 4 字/片。
- **鉴权**：`app.api-key`(env `APP_API_KEY`) 只拦 `/api/**`；**密钥不进仓库**，本地值放 `application-local.yaml`（`optional:file:` 引入），主 yaml 不留空占位符（会覆盖本地值）。
- **SSE 心跳必须独立调度器**（`sseHeartbeatScheduler` + `scheduleWithFixedDelay`）；跑 Reactor `parallel()` 会连带打字机与全站心跳卡死。
- **`/files/**` 免鉴权 + 按后缀推 Content-Type** → 附件后缀白名单化（`SAFE_EXTENSIONS` 外落 `.bin`），`isImage` 另挡 `image/svg+xml`。
- **`clearMessages` 必须与摘要水位一起归零**（`summary`/`core_facts`/`summarized_count`），否则摘要指向不存在的历史。
- **工具注册唯一入口 `ToolRegistry`**：注解式走 `ToolProvider`（`@Tool` 反射），**动态式（MCP 等运行时才知的工具）走 `ToolCallbackSource`**，同名注解式优先；远端工具必须落这里，否则前端装配 / `tools_json` / 兜底解析器都看不到。
- **MCP**：官方 starter → `McpToolSource` 收进同一 `ToolRegistry`；`toolCallbacks()` 在**构造期**调用 ⇒ **必须吞异常**。主 yaml 不声明 server，真配置只写 `application-local.yaml`。已接 `filesystem`（根 `D:/Project`，预装 + 绝对路径、**禁 `npx`**）。见 `ARCHITECTURE.md` 八。
- **`agent_code.md` 归档必须原子写**（临时文件 + ATOMIC_MOVE）；`deleteAgent` 解绑会话用**单条批量 UPDATE**（`agent_id` 与 `agent_bind_source` 一起清）。
- **跨轮任务持久化（task/task_step）**：单会话单 RUNNING，开新规划前 `cancelRunning`；落库的 `step_index`/`depends_on` 是**重映射后 specs 连续下标**（续跑直接按此重建，agent 被删标 SKIPPED + remapDeps 过滤）；续跑不重新规划、只跑 PENDING + `retry_count<2` 的 FAILED；`markStepFailed` 用 `setSql("retry_count=retry_count+1")` 自增。DDL 由用户在 mysql cli 执行 `alter.sql`。
