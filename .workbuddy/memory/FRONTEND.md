# 前端与 UI 约定（My_Agent）

> 从 `MEMORY.md` 拆出（该文件超注入上限被截断）。**改前端/样式前先读本文**。
> 覆盖 `index.html` / `chat.html` / `edu.html` / `user.html` / `login.html` 与 `static/css`、`static/js`。

## 通用硬约束
- **禁小数像素字号**；最小 12px、正文 13px 起；次要文字用 `--text-soft`。
- **UI 改动先出预览**（无头 Chrome 截图），别只靠读代码判断。
- **前端依赖禁回外网 CDN**：Vue / marked / echarts 一律走 `static/js/lib/`。CDN 挂 → Vue 未定义 → 页面停在原始模板，4 个 fixed 弹窗叠加且点不动。`#app` 的 `v-cloak`、`#boot-tip` 勿删。
- **静态资源配了 `no-store: true`**（`spring.web.resources.cache.cachecontrol`）→ 前端改完普通刷新即生效，不必 Ctrl+F5；上线再删。
- **「改了看不到」三层排查**：源文件 → 产物（`target/classes` / jar 时间戳）→ 浏览器缓存。
- 🔴 **错误提示必须落到界面上**：`error.value = ...` 只是赋值，页面上没有对应渲染点时用户什么都看不到 —— 表现为「点了按钮页面纹丝不动」，看起来像按钮坏了。`/observability.html` 2026-09-30 正是如此：error 有值，但 `.ob-error` 只写在非管理员分支里，管理员分支一个渲染点都没有。**凡有「加载失败」分支的页面，都要有 `v-if="error"` 的可见节点。**
- **「没数据」与「没成功」必须分开渲染**：空窗口 / 空列表走 `v-else` 的「暂无数据」空态（判 `xxx.length`），请求失败才显示错误节点，两者不可共用同一块 UI —— 否则用户分不清「这个窗口真的没有记录」和「接口挂了」。空态有专门的回归用例（真实库上 `verify_observability_empty_window_sql.py` 反证过「空窗口聚合返回 NULL」，前端必须走 `v-else`「暂无数据」、不与错误节点共用，判据见上方红线）。

## 流式生成：中断与竞态（`js/app.js`，2026-10-06）
- **`send` / `resumeTask` 共用一套中断控制**，四项缺一不可：`beginStream()`（建 `AbortController` + 清 `stopReason`）→ `fetch` 带 `signal` → 读循环里每收到一段数据就 `resetIdleTimer()` → `finally` 无条件 `endStream()`（清计时器 + 释放）。漏掉 `finally` 那步会让下一轮的 `streamAbort` 指向已结束的控制器。
- **空闲超时不可省**：`STREAM_IDLE_MS = 60000`（后端心跳 15s，取 4 个心跳余量）。没有它时，连接被中间层静默掐断会让 `reader.read()` 一直挂着 ⇒ **输入框永久锁死、只能刷新页面**。
- **`AbortError` 必须按 `stopReason` 分流**：`'idle'`（超时）/ `'user'`（用户点停止）/ 其它（真错误）。混成一句会把用户主动停止显示成「请求失败」。
- **停止文案要写明「服务端仍会完成本轮并落库」**：客户端 abort 拦不住后端（整轮跑在 `boundedElastic` 上），前端只是「不再接收」。不写清楚，用户刷新后看到完整回复会当成 bug。按钮样式 `.stop-btn`（红色，与发送按钮区分中断/提交两种语义）。
- **切会话竞态靠 `historySeq` 序号**：`selectConversation` 进入即 `const seq = ++historySeq`，**拿到数据后与返回前各判一次**（`resp.json()` 本身也是 await）；`newConversation` / `startAgentChat` 也要 `historySeq++` —— 否则「点了会话立刻新建」会让旧会话的历史写进新会话的空列表。
- **`messages.value[lastIndex]` 必须判存在**：切会话/新建会清空列表，原写法直接在 undefined 上取属性会抛错。

## 页面骨架
- `index.html` = 首页、`chat.html` = 聊天页，**共用深色色板**（换肤改两处 `:root`）；`edu.html` 有独立 `edu.css`（同一套色板变量，改 `:root` 一处）。
- **`#app` 禁加 `backdrop-filter` / `transform` / `filter`**（会为 fixed 弹窗建包含块 → 被圆角裁）。
- `--primary` 只做填充，文字用 `--primary-text`；ECharts 走 `agent-dark` 主题。
- 改 `style.css` 先导出选择器清单、**只改值不删选择器**。
- **固定高度 flex 容器 + 内部滚动区必须补 `min-height:0`**：子项默认 `min-height:auto` 不收缩，内容顶破 `max-height` 后被父级 `overflow:hidden` 静默裁掉（弹窗字段被裁那次）→ `.modal-body { flex:1 1 auto; min-height:0; }`。
- **截断用固定高度，别用 `-webkit-line-clamp`**：flex 布局下若上方元素缺失（如无 agent_code 的卡片少一行），`display:-webkit-box` 会**整段不渲染**（不是截断）→ 用 `min-height/max-height` + `overflow:hidden`。
- 🔴 **每个页面挂载后必须摘掉 `#boot-tip`**：它是 HTML 里那条静态启动提示，样式为 `position:fixed;inset:0` + 不透明底 + `z-index:200`。Vue 挂载成功却忘了 `remove()` 的后果是「**DOM 里数据齐全、屏幕上一片空**」—— `#app` 的内容全渲染好了，只是被这层盖住，且不抛任何错（2026-09-30 `/observability.html` 就是这么漏的）。四页统一姿势：`onMounted` 里 `document.getElementById('boot-tip')?.remove()`（`app.js` 在 `app.mount()` 之后，edu/user 在 `onMounted` 开头）。

## 登录态与鉴权 UI（`js/auth.js` + `css/auth.css`）
- **登录界面只有一份**：结构在 `js/auth.js`（`Auth.openLogin()` 拼 DOM），样式在 `css/auth.css`（`auth-` 前缀类名 + `--auth-*` 变量，**刻意不复用页面变量**，landing/chat/edu 三套主题下引入都不互相污染）。改登录外观只改这两处；`/login.html` 只是「整页模式」薄壳（调 `Auth.mountLoginPage()`），**别再把表单复制一份出去**。
- **三个入口**：`data-auth-nav`（任意元素，自动渲染「登录」按钮 / 已登录渲染「用户名 + 用户管理(ADMIN) + 退出」）、`data-auth-required`（链接，未登录点击先弹框、登录后自动前往；已登录也先校验 token 再跳）、`Auth.requireLogin()`（页面 `<head>` 守卫：**先锁页 → 确认登录态 → 才放行渲染**，登录成功 `reload`，**不跳页**）。
- **页面锁（`lockPage`/`unlockPage`）—— 别改成「盖一层不透明遮罩」**：直接打开受限页 URL 时，只看本地 token、或先渲染页面等接口 401 再弹框，都会**先闪一眼未登录的空壳页**。现行做法是 head 里同步上锁：注入 `<style data-auth-lock>` + 给 `<html>` 加 `auth-locked`，规则为 `body{visibility:hidden}`（整页不参与渲染）+ `.auth-veil{visibility:visible}`（登录框就挂在 body 里，visibility 会继承，必须显式放行，否则连框一起藏掉）。提示层 `.auth-lock` 挂 **`<html>`** 上（head 阶段 `document.body` 还不存在，head 里的元素不渲染）。
  - 解锁时机只有两处：`/api/auth/me` 返回 200（放行页面），或登录框**已插入 DOM 后**（`openLogin` 的 `onMounted` 钩子）。顺序反了就会露出一帧页面内容。
  - 受限页的登录框一律 `page:true`（整页不透明底）：半透明遮罩会把空壳页透出来，正是要避免的观感。`onClose` → 回首页（`pageGuarded` 标记）。
  - `requireLogin()` 里页面自身的接口可能先于 `me` 返回 401 → `Auth.fetch` 的 401 分支用 `!gate && !veil` 拦掉，避免半透明弹框与整页登录框打架。
- 401 也是就地弹框（在 `Auth.fetch` 内）；弹框是单例 → 并发 401 只弹一个。`redirectToLogin()` 降级为兜底（弹框不可用时才整页跳）。
- **401/403 的提示语由服务端决定**：`readFailure(resp, fallback)` 用 **`resp.clone()`** 读响应体的 `message` 与 `reason`，返回 `{reason, message}`（服务端已把「未登录 / 已过期 / 凭证无效 / 账号停用」分开写）。用 clone 是因为响应体只能读一次 —— 直接 `resp.json()` 会让调用方自己的解析炸掉（这正是早期「统一按需重新登录」那句笼统提示的由来）。**前端别再自己编提示语**，否则服务端区分得再细也白搭。
- **判定「要不要清登录态」只看 `reason` 白名单，不看状态码**（`isSessionInvalid(failure)`）：只有命中 `TOKEN_FAILURE_REASONS`（`NO_TOKEN`/`MALFORMED`/`BAD_SIGNATURE`/`MISSING_EXPIRY`/`EXPIRED`/`NO_SUBJECT`/`USER_UNAVAILABLE`）才算凭证真失效。**不在白名单里的 401 一律保留 token** —— 例如反向代理/网关拦截、或无 reason 的 401，它们与用户登录态无关，清了等于让用户白白重登一次。（2026-09-29 前这里还额外显式排除 `API_KEY_REQUIRED`；该服务级密钥闸门已整段删除，分支一并移除。）非 401（如 500）同样保留 token。
- **清登录态前必须先复核**（`confirmSession()`）：命中白名单时也不立即 `Auth.clear()`，先向 `/api/auth/me` 再问一次；多个并发 401 共用一个 in-flight 请求（不放大流量）。复核通过 → 不弹框不清除（单次 401 是噪声）；复核仍失败 → 才清 token + 弹框 + `leaveGuardedPage`。**别把单次 401 直接当判决** —— 这正是「登录后随便点一下就弹重新登录」的成因（服务端并发验签不可靠时会随机吐 401，见 `README.md` 3.1）。
- **`Auth.diagnose()`**：控制台自检入口 —— 打印本地 token 的声明与剩余有效期（**不含 token 本身**）+ 服务端 `/api/auth/me` 的 reason，用于区分「本地 token 真过期」还是「服务端误判」。
- `readFailure` 的 fallback 分场景：登录态相关用 `EXPIRED_MESSAGE`，其余用 `'(服务端未提供 message)'`；服务端异常（`SERVER_ERROR_MESSAGE`）时**不清** token。
- **旧凭证的延迟 401 不许动新会话**：`Auth.fetch` 的 401 分支先比 `token !== Auth.getToken()`，不同就直接放过。少了这一步，「登录成功后」仍可能被上一轮请求的延迟 401 清掉刚拿到的 token → 表现是「刚登录又被要求登录」。
- **「连不上服务端」≠「登录失效」**：`guardNavigation` / `requireLogin` 的 `catch` 只可能来自 fetch 本身失败（网络中断、服务端重启中）——此时**服务端没有表态**，本地 token 只是「可能有效」，**禁止 `Auth.clear()`**：清掉等于把一次网络抖动升级成一次强制重新登录（「刚登录成功、一进页面又让登录」的来源之一）。提示语用 `OFFLINE_MESSAGE`（无法连接服务端），只有服务端明确 401 才清 token 并显示服务端 message。
- `openLoginForPage()` 若已有框（`veil`）不会触发 `onMounted`，因此先 `unlockPage()`；否则页面永久停在「正在校验登录状态…」。
- **顶栏用户区已统一到 `data-auth-nav`（2026-09-29）**：四页（index / chat / edu / user）都只放一个 `<span data-auth-nav></span>` 挂载点，内容全部由 `js/auth.js` 渲染 —— **用户名点开下拉**（个人信息 / 修改口令 / 用户管理（仅 ADMIN）/ 退出登录），样式只在 `css/auth.css`（`.auth-nav` / `.auth-user-trigger` / `.auth-menu` / 账号弹框）。**三页各自的 `.auth-box` / `.auth-user` / `.auth-entry` 及其 CSS 已删除 → 换肤只需改 auth.css 一处**（此前要同步 4 处）。edu 页 `.edu-topbar-right` 因失去 `.auth-box` 的 `margin-left:8px` 而补了 `gap:8px`。**顶栏不再有裸露的「退出」按钮**：退出是低频且不可逆的动作，收进下拉减少误触。
- **用户菜单实现要点**：① 显隐走 `[hidden]` 属性，CSS 必须显式写 `.auth-menu[hidden]{display:none}` —— `.auth-menu` 自身有 `position/display` 声明会盖掉浏览器默认的 `[hidden]` 行为；② 触发器与菜单项的 `click` 都要 `stopPropagation`，否则会被 document 上的「点空白收起」监听**点开即关**；③ 账号弹框（`openAccount`）**与登录框各持一个 veil 单例** —— 共用会互相顶掉（401 可能在弹框开着时弹登录框）；弹框骨架复用 `.auth-veil` + `.auth-modal`（`.is-account` 加宽），只换内容节点；④ 个人信息「先本地快照、再 `/api/auth/me` 回源覆盖」，回源失败保留快照（昵称/角色可能已被管理员改过，不回源会显示旧值）；⑤ 改密前端即时校验口径与后端 `PasswordHasher` 对齐（6~64 位），**成功后不自动关闭弹框、不清 token**（JWT 无状态，改密不影响已签发 token）。
- **chat 顶栏右侧动作区靠右由容器负责，不挂在按钮上**：`.header-actions`（`inline-flex` + `gap:10px` + `margin-left:auto`）统一装「追踪 / 成本 / 教务系统 / 登录用户区」，各按钮**不带 `margin-left`**（`.cost-btn` 的 8px 补偿已删）。曾把 `margin-left:auto` 挂在 `.trace-btn` 上，而该按钮 `v-if="currentId"` —— 无会话时它不渲染，**整组失去支点、塌回标题旁边**（用户报的「用户名/用户管理/退出没在最右边」）。教训：**条件渲染的元素不能承担布局支点**。
- **三页顶栏的「互相跳转」入口共用 `css/topbar.css` 的 `.nav-pill`（2026-09-29）**：chat「🎓 教务系统」、edu「💬 AI 对话」、user「🎓 教务系统」是同一个胶囊（`padding:5px 12px` / `border-radius:999px` / 13px 500 / `line-height:1`，hover 变主色描边 + `--primary-soft` 底）。**样式只此一处**：原先 chat 的 `.trace-btn` / `.nav-link` 定义在 style.css，edu、user 两页不引 style.css，各自又抄了一份文字链接样式（`.back-chat` / `.um-link`），三处观感不一 —— 现改为三页各 `<link>` 一次 topbar.css，`<a>` 版由 `a.nav-pill` 补 `inline-flex` + 去下划线。色板依赖 `--border/--surface/--text/--primary/--primary-text/--primary-soft`（style.css / edu.css / user.css 三份取值相同）。**别把 `.nav-pill` 定义塞回某一页的 CSS**，否则另一页立刻失去样式。第三页的「← 返回首页 / ← 返回控制台」仍走各页原来的文字链接样式，不在共用范围内。

## 下拉：原生 `<select>` 是坑
- 底色写 `background-color`（`background` 简写会静默丢掉自绘箭头）；`option` 必须显式写 `background-color` + `color`（`color-scheme:dark` 染不深弹层，实测无效）。
- **弹层容器由系统绘制、CSS 完全不可控**（配色/边框/圆角/开合动画都不听；`option` 自定义色还会导致点开闪一帧）→ 要完全可控只能换自绘组件。
- **现行方案：`UiSelect` 组件**（app.js 定义、Teleport 到 body 的自绘下拉），AI 页与教务页（edu.js 有副本）共用同一套 `.ui-select` CSS；**换皮时两处都要同步**。

## in-DOM 模板陷阱
- **自定义组件禁自闭合**：`<ui-select />` 会被浏览器解析成未闭合开标签 → 后续 `v-else` 失去相邻 `v-if` → Vue compiler-30、**整页挂载失败**（boot-tip 不消失）。必须写 `<ui-select></ui-select>`；`template:` 字符串模板不受此限。

## 开关 / 状态类控件
- **禁用高饱和实底 + 外发光**：整块亮色填充会让「状态」抢过主按钮（「发送」）的权重。关闭态无底无框，开启态用淡色底 14% + `inset` 内描边 + 着色文字。
- **同类控件共用一套规则、用 `--tg-rgb` / `--tg-fg` 变量区分色相**，不要复制两份样式。图标用 `currentColor` 描边 SVG（emoji 全彩会打乱色板，且小尺寸下细节糊）。

## 按角色显隐的入口（2026-09-30）
- **入口角色值必须在 setup 时取一次存进 `ref`**，不要在模板里直接写 `Auth.hasRole('ADMIN')` / `Auth.getUser()` —— 它们读 `localStorage`，**不是响应式的**，模板只求值一次且不会随登录态变化重算。现状：页面级 `isAdmin`（顶栏 `💰 成本` 与 `🧪 评测` 用它，`v-if="isAdmin"`）在 setup 取一次；`traceModal.isAdmin` 在**打开弹窗时**取一次（弹窗只有点开才渲染，那时 Auth 早已就绪）。
- **取不到角色一律当非管理员**：UI 侧失败关闭（`!!(window.Auth && window.Auth.hasRole && ...)`），真正的闸门在后端（`@RequireRole`）。别让前端「角色没读出来就先显示」。
- **UI 显隐不是权限**：只能少给一个点了就 403 的入口，不能替代后端校验。两处要一致 —— 后端 `@RequireRole(ADMIN)`，前端同一角色隐藏入口。
- **403 的文案要单独给**：`403` 是角色不够，不是网络/服务端故障。别混进笼统的「加载失败：HTTP 403」，说清「该看板仅管理员可见（当前账号无 ADMIN 角色）」/「评测仅管理员可用（当前账号无 ADMIN 角色）」—— 走到这里多半是同一浏览器换了小号登录、或角色被管理员摘掉后的残留状态。
- 当前角色相关的入口/能力：`💰 成本`（ADMIN 专属，后端 `CostController`）、`🧪 评测`（ADMIN 专属，后端 `EvalController` —— 跑批是真实模型调用、会花钱，与成本同属「会花钱的运维动作」）、`🔍 追踪` 的「本会话 / 全部会话」切换（ADMIN 专属）、`用户管理` 菜单项（ADMIN 专属）。

## 聊天页（chat.html）
- **追踪弹窗（🔍）按每页 10 条分页（2026-10-06）**：`TRACE_PAGE_SIZE=10`，`tracePageItems` 切片渲染，底部 `.trace-pager` 显示「共 N 轮 · 第 x / y 页」。三条口径别搞混：①**统计条基于全量 `traceModal.items`**，不是当前页（它回答「这个范围的总体情况」）；②展开态 key 必须是 **`traceId`**，用列表下标记会在翻页后串页（第 1 页第 3 条与第 2 页第 3 条共用同一个 key）；③`openTrace` 里 `page = 1` 重置，切范围 / 刷新都回到第 1 页，避免停在一个已不存在的页码上。
- **分页条按「有数据」渲染，不要按「页数 > 1」（2026-10-06）**：`v-if="!traceModal.loading && traceModal.items.length"`，按钮组另加 `v-if="tracePageCount > 1"`。原因：分页逻辑本就与「本会话 / 全部会话」无关（共用同一套 `tracePageItems`），但按页数判断时**本会话不足 10 条 = 1 页 ⇒ 整条不渲染**，用户据此以为「只有全部会话才分页」。1 页时只出页码信息、不出按钮（两个全禁用的按钮只是占位噪声）。`loading` 条件是为了不闪一条「共 0 轮」。
- **flex 列容器的子项必须显式 `flex: 0 0 auto`，否则条数一多就「变形」**：`.trace-list`（`flex-direction:column` + `max-height` + `overflow-y:auto`）里的 `.trace-item` 默认 `flex-shrink:1`，内容超高时 flex 会**等比压扁每个子项** —— 而且是压到刚好贴合容器，于是**连滚动条都不出现**，只看到行高塌陷、文字被各自的 `overflow:hidden` 裁掉后重叠。这就是用户报的「下面数据变形」。修法一行：`.trace-list > * { flex: 0 0 auto; }`。**凡是 `flex-direction:column` + 固定高度 + 子项数量不定（列表 / 卡片流）的地方，都该检查这条。**
- **量「塌陷」要量 `.trace-item` 的高度与相邻行间距，不能量 `.trace-row`**：`.trace-row` 的高度由自身盒模型决定，父项被压扁时它只是被裁掉，`getBoundingClientRect().height` 依然是 34 —— 曾据此误判成「没塌」。正确指标：`.trace-item` 高度（正常 36，塌陷时 2）+ 相邻两行 `top` 之差（正常 42，塌陷时 8 = 行已重叠）。反证脚本 `probe_trace_collapse.js`（三步：现状 → 仅收紧容器 → 再注回旧 flex）。
- **无会话空白页是默认入口**（首页进入 / 刷新都是）→ 会话级操作必须处理 `currentId` 为空：开关类只改前端状态、由 `send()` 建会话后补写回（`newConversation()` 会复位开关）；**别在拨开关时建会话**（会留没说过话的空会话）。漏掉的表现 = 开关「点不动」（v-model 置 true 被 `@change` 改回）或选择被静默丢弃（RAG 不写回会话则本轮不检索）。**`send()` 里的 `pendingXxx` 暂存 + 建会话后补写回是一组**：新增会话级开关时三处必须一起改（ref 声明 / `newConversation`+`startAgentChat` 里复位 / `selectConversation` 里回显 / `send()` 的 pending 暂存），漏任何一处都表现为「刷新或首次发送后开关状态不对」。
- **会话级开关现有五个**：`ragEnabled` / `planMode` / `plannerConfirm` / `reviewEnabled` / `crossSession`，模板里全部暴露在 `return {...}`（忘了导出 = 模板里静默 undefined）。**规划与评审互斥**，前端必须**双向联动**：`onReviewChange` 成功写回后置 `planMode=false`（并同步 `conv.planner`），`onPlannerChange` 成功写回后置 `reviewEnabled=false` —— 只靠后端关掉会出现「两个开关都亮着 / 顶部徽标还显示规划，而实际走评审」的脱节。两个新开关都复用既有 `.planner-toggle` / `.rag-toggle` 样式与 `tg-ico` 图标，**别新造一套**。
- **SSE 事件解析在两条通路各一份**（`send()` 的 `flushEvents` 与 `resumeTask()` 的 `flushEvents`，两段代码近似但注释不同）。**新增事件类型必须两处都加**，否则在 resume 通路里会落到最后的 `else if (data)` 分支 —— **被当成正文 token 拼进回答**（症状是回答里混进一坨 JSON），而不是报错。事件字段名与后端 `StreamEvent` 的静态工厂一一对应：`token` / `progress` / `citations` / `plan` / `approval` / `review` / `recall` / `error`。
- **气泡内展示区块的顺序**：正文 `md-body` → 评审候选 → 跨会话回忆 → 引用来源。三者都插在 `<template v-if="m.role === 'user' ? … : m.content">` 内、都排在正文**之后**（结论在上、依据在下），且都用 `m.role !== 'user'` 再滤一层。`review` / `recall` **不落库**（后端刻意不持久化）⇒ 刷新后不再出现，别写成「历史也要回看」。
- **折叠默认值按「内容长度」定，不按类型统一**：评审候选是完整作答（可能很长）⇒ `reviewOpen=false` 默认折叠，`.review-body` 给 `max-height: 360px; overflow: auto`；回忆列表条数少且对用户有用 ⇒ `recallOpen=true` 默认展开。候选原文用 `{{ }}` 插值而非 `v-html`（模板转义天然防注入）+ `white-space: pre-wrap` 保留模型输出的换行。
- **顶栏徽标复用 `.agent-badge` + 修饰类**（`planner-badge` 紫 / `rag-badge` 绿 / `review-badge` 琥珀 / `cross-badge` 青）：徽标由**会话级 ref**（不是 `conversations` 里的对象）驱动，因为 `selectConversation` 会同时更新两者，而 `loadConversations` 只填列表不选中任何会话（`currentId` 为 null 时本就不该有徽标）。评审用琥珀色是刻意的：提示「会多花几次模型调用」。

## 教务页（edu.html / edu.js / edu.css）
- **接口风格已统一为命令式**（无 RESTful 分支、无 `apiStyle`）：分页 `POST /api/edu/{表}/page`（body 带 `page/size` + 筛选条件）、下拉 `GET /api/edu/{表}/list`（只回 `OptionVO{id,label}`）、`GET /{id}`、`POST /save`、`PUT /update`（id 在 body）、`DELETE /delete/{id}`。**`/api/edu/dict` 已删**，别再调（预览 mock 对该路径返回 404，调了就看得见）。
- **外键可读名由后端 join 给**：列 key 直连 VO 字段（`headTeacherName`/`className`/`courseLabel`/`studentName`…），前端**不再做 id→名称映射**；`fmtCell` 只留 `isBoarding`/`dayOfWeek` 这类枚举美化。
- **下拉选项按表懒加载**：`optionsCache`（`{表key: [{value,label}]}`）+ `ensureOptions(keys)`（并发去重、失败静默不阻塞主流程）；`switchView` 取数前先备齐当前视图用到的键，`openCreate/openEdit` 打开弹窗前再兜一次。`fkOptions` 的值是 **tableMeta 的键**（`class`/`subject`/`semester`…），不是接口路径；选项文案由后端拼，前端不拼。
- **搜索条一套两用**（table 与 query 共用，别再各写一套）：字段声明在 `tableMeta[x].search` / `queryMeta[x].search`——`fkOptions` → 该表 `/list` 下拉、`options` → 固定枚举下拉（如星期）、都没有 → 输入框；10 张表 + 4 个关联查询都已声明。`searchFields` 按 `current.view` 取声明，`collectSearch()` 统一收集（**空值不下传**），`clearSearch()` 在切表/切查询与点「重置」时清空，`doSearch()` 按视图分派 `loadQuery(1)` / `loadPage(1)`。**已删掉 per-view 的 `filter` 状态与 `filters:['class']` 那套**，别再加回来。query 视图与 table 一样分页：`loadQuery(p)` 是**唯一**入口（原 `loadQueryPage` 已合并进来，`goPage` 也走它），四个查询都是分页接口，前端**不再有** `paged` 标记 / `isPagedQuery` / 分页脚 `v-if`。
- **分页**：默认 10 条/页，口径唯一在 `BaseBO`（`DEFAULT_SIZE=10` / `MAX_SIZE=100`；前端 `pageSize=ref(10)` + 选项首位 10）。控件 = `首页 / 上一页 / 第 x / y 页 · 共 n 条 / 下一页 / 尾页 / 跳至 [输入] 页 [/ 每页条数]`，**table 与 query 两视图共用**，统一走 `goPage(p)`（按 `current.view` 分派 `loadPage` / `loadQuery`），`doJump()` 做**非数字忽略 + 越界向边界收敛**。`.edu-pager` 靠右下（`justify-content: flex-end`），`flex-wrap: wrap`；`.edu-pager .edu-btn` 比常规按钮紧凑一档；跳页输入 `.edu-jump-input`（`type="text" inputmode="numeric"`，避免数值框自绘尖角）。
- **表格页布局**：筛选条置顶（`.edu-search-bar`，查询/重置与输入框同行）；标题放表格卡片头 `.edu-panel-head` 右上区（table 只留「＋新增」，query 无操作按钮）；两视图一致，仅 dashboard 还用 `.edu-toolbar`。
- **视图记忆（刷新不回总览）**：`switchView()` 末尾统一调 `rememberView(item)` —— 写 `localStorage['my_agent_edu_view']`（值 `dashboard` / `table:xxx` / `query:xxx`）+ `history.replaceState` 把 `?view=` 同步进地址栏（**用 replaceState 不污染后退历史**，保留 `__auto` 等已有参数）。初始 `switchFromUrl()` 顺序：**URL 参数 → localStorage → 回落 `menuGroups[0].items[0]`**，非法值经 `findMenuItem()` 返回 null 自然回落。侧边栏分组展开状态另存 `my_agent_edu_groups`（`toggleGroup` 落盘），恢复视图时 `revealGroup(item)` 把所在分组展开，否则菜单里看不到高亮项。新增菜单项/视图时**不用**额外登记，`viewParam`/`findMenuItem` 按 `view:key` 泛化。
- **列表页「刷新」按钮已删**（搜索/翻页/增删改都会重载）；看板 toolbar 的刷新是唯一手动重载入口，保留。
- **卡片三件套**：`.edu-panel`（panel 底 + border + 8px 圆角 + `overflow:hidden`）作外壳，内部 `.edu-panel-head` / `.edu-table-wrap` / `.edu-panel-foot`（分页在脚里，`.edu-pager` 无 `margin-top`）。
- **列宽**：`thead th{width:auto}` + 序号列 64px、操作列 140px 固定，其余按内容比例分配。**禁 `th{width:1%}` + `last-child:auto` 那套**：操作列会被拉成巨大空白列、数据全挤左边。
- **表头底必须不透明**：`linear-gradient(rgba(255,255,255,.05),rgba(255,255,255,.05)), var(--panel)`；用半透明 `--surface` 横向滚动会透字。
- **首列是「序号」不是 ID**：`rowNo(i)=(page-1)*Number(pageSize)+i+1`，模板须 `v-for="(row,i) in rows"`；空行 `colspan` table 为 `currentColumns.length+2`、query 为 `+1`；`tableMeta.*.columns` 已无 `id`，但 `:key="row.id"` 仍用实体主键。
- **切视图必须同步清空上一视图的列表**（`switchView` 里 `rows`/`queryRows`/`total`/`totalPages` 一起归零，且**当场** `listLoading=true`）：表头是同步换的、数据要等请求回来，旧行哪怕只留一帧，也是「新表头 + 旧数据」（四个查询列口径不同 → 缺的字段全成「—」、数字错位），观感就是**列表闪一下**。`listLoading` 必须在 `switchView` 里置位 —— 等 `loadPage/loadQuery` 起来再置会晚一个 microtask（中间要过 `ensureOptions().then`），那一帧渲染成「暂无数据」，等于把一种闪换成另一种。tbody 因此是三态：有数据 / `listLoading && !len` → `.edu-loading`「加载中…」/ 否则「暂无数据」；`.edu-table-wrap.is-loading{min-height:420px}` 顺手压掉面板高度抖动。
- **列表请求带序号丢弃**（`reqSeq`）：`loadPage/loadQuery` 开头 `const seq = ++reqSeq`，响应回来先比 `seq !== reqSeq` 就 return —— 快速连点菜单/翻页时，先发的响应可能后到，不丢弃就会把新视图的列表覆盖成旧视图的数据。**连接层失败要出声**：`!r.ok` 只管 HTTP 错误码，fetch 本身失败（断网、服务端重启）落不到那个分支，必须 `catch` 里 `askAlert`，否则页面只留一个空列表，分不清「没数据」还是「没连上」。
  - **列表行可能为 null / undefined / 非对象：落库前归一化 + 模板层兜底（双保险）**。`loadPage`/`loadQuery` 统一 `const recs = Array.isArray(data.records) ? data.records : []; … recs.map(r => (r && typeof r === 'object') ? r : {})`；模板三处取值点写 `row ? … : ''`（`<tr :key="row ? row.id : i">`、`fmtCell(row ? row[c.key] : '', c)`、`fmtQueryCell(row ? row[c] : '', c)`）。**只写 `filter(r => r != null)` 挡不住「值存在但非对象」**；只靠模板兜底则每列都得判。漏了会抛 `Cannot read properties of null/undefined (reading '列名')` —— 关联查询是 `row[c]`（`c` 是动态列名），往 mock 注入 `[None]+rows` 实测可复现（先炸的是首列 `className`，不是 `startTime`）。**性质：防御性加固（「后端 join 可能吐 null 行」这一理论风险的堵漏），不是某条用户报错的正主** —— 用户看到的 `reading 'startTime'` 来自 DevTools 注入脚本，见下节。另：只跑「正常数据」的探针会 `errs=[]` **假阴性**，必须往 mock 里**注入 null 行**才能走到这条路径。
- **表单下拉**：字段写 `options:[{value,label}]`（固定枚举，值必须与库里一致：性别 `男/女`、职称 4 档取自 `business.sql`）或 `fkOptions`（取值来自该表 `GET /list`）。渲染条件 `c.fkOptions || c.options`，选项函数 `fieldOptions(col)`（`options` 优先）；`saveForm` 对 `c.options` **按字符串原样提交、不进 `Number()`**。
- **禁原生 `confirm`/`alert`**（系统绘制、与页面两个风格）→ 统一自绘 `dialog`：`askConfirm(msg,{title,okText,danger})` / `askAlert(msg,{title})` 返回 Promise（主按钮 true，取消/遮罩 false）；模板 `.edu-modal.is-dialog`（400px），危险按钮 `.edu-btn-danger`（淡红底 14% + 红描边，勿用高饱和实底）；删除文案用 `recordLabel(row)` 取首列可读值而非主键。后端的校验失败（唯一性 409 / 引用校验 409 / 404）就从这条 `askAlert` 路径弹出。

## UI 预览工具
- `.workbuddy/tools/serve_preview.py`：托管 `static` + mock `/api/edu/**`、`/api/auth/me`、`/api/chat/conversation` 与 **`/api/chat/stream`（固定 SSE，供 `?__auto=send` 走完整条对话渲染链路）**，并在 head 的 `Auth.requireLogin()` 之前**默认注入假 token**（`?__nologin=1` 不注入）—— 该页是受限页，head 里就上锁，没有 token 会一直停在登录框、列表永远拿不到数据（工具早期版本的截图就是这么卡住的）。**从项目根执行**，用完 TaskStop。
  - `?__js=<名称>`：把 `tools/<名称>.js` 注入到 `</body>` 前（样式/几何自查探针入口）。**量样式一律用探针**：`getBoundingClientRect` + `getComputedStyle` 写进 `<pre id="__probe">`，dump-dom 读出来比对 —— 顶栏深色小胶囊在缩略图上看不清，靠肉眼必误判。（现成探针 `probe_topbar.js` 与配套 `verify_topbar.py` 已于 2026-09-30 删除；需要时按 `serve_preview.py` 的 `?__js` 机制在同目录现写一个 `.js` 即可，详见 `TOOLING.md` 的 `?__js` 说明。）
  - `?__errcap=1`：在 head 最前注入运行时错误捕获（`window.onerror` / `unhandledrejection` / 劫持 `console.error|warn`），结果写进隐藏的 `<pre id="__errcap">` JSON 数组，**dump-dom 时由探针读出 `errs=`**。`errs=[]` = 页面零 JS 错误（脚本一上来就建元素，故能区分「没报错」与「没注入」）。要不要它取决于问题形态：`--dump-dom` 只给 DOM，**运行时异常（尤其 Vue 渲染里抛的那种）在 DOM 上常常看不出来**。
- ~~`.workbuddy/tools/verify_pages.py`~~（**已删除，2026-09-30**：9-22 通用 6 页冒烟，其「改完 static 必跑」职责由 `verify_observability.py` / `verify_topbar.py` 承接，但三者连同全部 `probe_*.js` 探针也已在 2026-09-30 一并删除；判据 `ERRCAP=[]` 作为通用原则保留）。
  - ⚠️ **改完 `serve_preview.py` 的注入逻辑后，必须先用无头 Chrome 实跑一遍，确认注入脚本真的进了 HTTP 响应体（而非只在源码里）** —— 本次吃过亏：注入那行没落盘（同文件并行发多个 Edit 的竞态），探针连跑三轮全是「没抓到」，**看起来像「页面没报错」而真实结论相反**。断言一律取**注入脚本源码里的字面量**（`id="__errcap"` 是运行时才生成的，写成它必然误报）。
- ~~`.workbuddy/tools/probe_edu_table.py`~~（**已删除，2026-09-30**：9-22 抓 edu 列表三态的一次性探针）。其沉淀的通用坑保留：
  - **「请求在途」的帧抓不到，这是硬限制**：`--virtual-time-budget` 遇未决 fetch 会**暂停**（服务端拖 25s，dump 也老老实实等 25s，抓回来的是终态）；不虚拟时间则 dump 约 0.5s 就出，比 `__auto` 的 `load+500ms` 定时器还早，点击步骤根本来不及跑；`--timeout` 在 `--headless=new` 下被静默忽略（给了 2500ms 实测 0.5s 就出）。⇒ 判定「切视图时有没有闪旧数据」只能真机肉眼（或上 CDP 逐帧），别指望 dump-dom。
- ~~`.workbuddy/tools/probe_auth.py`~~（**已删除，2026-09-30**：9-22 对真实服务的鉴权对照探针；其职责「mock 覆盖不到密钥/签发链路，改服务端鉴权后用真实服务复核」已写成上方红线文字，不再依赖脚本）。
- **退役（2026-09-30，tools 已精简）**：9-22 那一代工具已全部删除 —— `preview_user.py` / `verify_auth_gate.py` / `probe_frontend.py`（上轮删）、`CropShot.java` / `check_preview_inject.py` / `probe_token_concurrency.py` / `probe_edu_table.py` / `verify_pages.py` / `probe_auth.py`（本轮删）。其中 `preview_user.py` 独有的三项能力（`?noanim` / `?probe=lock` / `/__mock` 故障注入）未迁移，需要时按思路自建；其余职责已并入 `serve_preview.py` 或沉淀为上方红线文字。
- **注入式探针 2026-10-06 部分回补**（当前 `tools/` 有 3 个：`probe_approval.js` / `probe_review.js` / `probe_badges.js`；完整清单见 `TOOLING.md` 顶部索引）。回补理由：**「由 SSE 事件或开关状态驱动的 UI」（审批卡片 / 评审候选 / 回忆列表 / 徽标）靠静态 HTML 与肉眼都验不到** —— 只有真跑一遍 `send()` → 读流 → 解析 → 渲染才能证明「区块确实会出」，而不是「模板里有这段」。写新探针时沿用同一套骨架：`load` + `setTimeout(2200)` 等流跑完 → 收集指标 → 写 `<pre id="__probe">`；需要验交互的（折叠展开、点开关）在写完之后再挂一个 `setTimeout` 二段采数。
- **耗时特性（重要，决定怎么排截图）**：无头 + `--virtual-time-budget` 下每毫秒虚拟时间约烧 4ms 真实时间，**且只要发生整页跳转，单次调用固定 ~184s**（与 budget 是 3000 还是 6000 无关）；不跳转的场景 4~6s 就出（首页/弹框类）。所以：能用 `--dump-dom` 判定的（落在哪页、有没有弹框）优先 dump-dom；必须出图时避开跳转链路，或接受 3 分钟。
- **无头截图只有一个坑：`--virtual-time-budget` 冻结 CSS 动画/帧驱动的时间轴** —— `.rise` 停在 `opacity:0`（首页看着「只剩顶栏」）、登录弹框的 `auth-fade`/`auth-pop` 停在低透明度帧（看着像「遮罩没压暗 + 卡片透明」）、首页 `#net` 粒子与 ECharts 动画同理。要终态得主动关动画（做法见上面退役说明①）。**别把这类现象当成 CSS 写错** —— 本次曾误判为 `backdrop-filter` 在无头软件渲染下失真。
- **`--dump-dom` 的字符串断言要匹配 DOM 属性，别匹配裸串**：DOM 里含 `<script>` 的内容（含预览工具注入的注释）与探针文本，`grep auth-locked` 会命中注释里的同名文字造成假阳性 → 一律写成 `class="auth-locked"` / `class="auth-veil is-page"` 这类属性形态。（本轮就这么误报过一次。）
- **mock 要按真实形状给**：`/api/chat/conversations`（及 agent/kb 一类）是**裸数组端点，不走 `RestResult`**；包成 `{code,message,data}` 会让 chat 页「Vue 已挂载但整页空白」+ 控制台 `null.id`，极易误判成改坏了页面。拿为 A 页写的预览工具截 B 页前，先核对该页接口的响应形状。
- mock 与真实接口同形：`POST /api/edu/{复数表名}/page`（**真分页**：按 `page`/`size` 切片，返回同形 `PageResult`；筛选只对「行里真有同名字段」的条件生效）、`GET /{复数表名}/list`（回 `OptionVO`，key 用复数名如 `courses`，**别再写成单数**）、`GET /api/edu/dashboard`、4 个关联查询（`score-detail`/`score-stats`/`timetable`/`schedule` **都返回 PageResult**，与真实接口一致；同样吃 `classId/subjectId/semesterId/teacherId/dayOfWeek/keyword`）、`/api/edu/dict` → **404**（防前端回头调）。
- fixture：10 张表的行都带 join 后的可读字段（`headTeacherName`/`courseLabel`…），教师 23 条 → size=10 时共 3 页；`DELETE` 固定回 409 引用校验文案，用于验证错误弹窗。关联查询的行**额外带筛选键**（`classId/...`）让 `apply_filters` 能筛；成绩明细再带隐藏字段 `keyword`（`"姓名 学号"`）承载后端那条 OR LIKE。
- URL 加 `?view=table:teacher` / `?view=query:score-detail` 直达视图；`?__auto=` 注入自动操作：`form`（开新增弹窗）、`selectN`（弹窗里第 N 个自绘下拉）、`del`、`ok`、`jumpN`（跳页框填 N 回车）、`q文本`（往搜索条第一个输入框填文本并回车，默认「陈」）、`pickN`（点搜索条第 N 个下拉并选中第一个具体项，跳过「全部」）。自绘下拉要**两步**：先点 `.ui-select-trigger`，再点 `.ui-select-pop .ui-select-opt`（Teleport 到 body，不在原 DOM 子树里）。

## 判定「控制台报错是不是本项目的」（2026-09-22 三次实操，**最终定案：DevTools 注入脚本**）

**先看来源前缀，这是最快也最省事的判据：标 `VM<数字>`（`VM3919` / `VM4191` / `VM4502`…）的脚本不是任何页面文件。** 它是 `eval` / `Runtime.evaluate` / 扩展在运行时创建的匿名脚本，DevTools 拿不到文件名才编号成 VM；栈里 `<anonymous>:2:<col>` 同理。**项目自己的脚本报错一定带真实路径**（`http://host/js/edu.js:626` 这种）。**所以：报错来源带 `VM` 就先怀疑注入方，别先改业务。**

### 定案：用户报的 `reading 'startTime'` = Chrome DevTools 注入的 web-vitals 采集包
报错原文（用户首次反馈）：
```
Uncaught TypeError: Cannot read properties of undefined (reading 'startTime')
    at et.reportAllChanges (<anonymous>:2:19429) …  脚本 VM3919
```
用户后续又贴了同一处的完整源码（`VM4502`），字段自证身份：

| 截图里的标识 | 归属 |
|---|---|
| `name: "CLS"` / `"INP"`、`reportAllChanges: 10` | web-vitals 的指标回调选项（Core Web Vitals：CLS / INP / LCP / TTFB / FCP） |
| `reportSoftNavs: window.devToolsReportSoftNavs` | **DevTools 专有钩子**（soft navigation 采集） |
| `subparts: {inputDelay, processingDuration, presentationDelay}` | web-vitals 的 **INP attribution** 三分解 |
| `clusterShiftIds: t.entries.map(Y)`、`entryGroupId`、`interactionId` | 同上，CLS / INP attribution 字段 |

⇒ 这是 DevTools（Performance 面板录制 / Lighthouse 审计）**注入页面的性能采集包**；出错点是它自己的 `t.entries[0].startTime`（`entries` 为空时读 `[0]` 的属性）—— **DevTools 自身的防御缺失，与业务无关**。项目源码 `grep` 上述标识**零命中**，可随时自证。

**处置：不改代码。** 停止 Performance 录制 / 关掉 Lighthouse 即消失；DevTools 重开会换编号（`VM3919`→`VM4502`），这是识别特征、**不是「又出了新错」**。判定业务侧有没有问题，**只看非 VM 来源的报错**。

### 我这轮的两个反向错误（留着当反面教材，比结论本身值钱）
1. **初判「不属于本项目」——结论对，依据无效**：当时靠「`startTime` 在源码里只是字符串键（timetable 列 + `queryColumnLabels`）」+ 正常数据探针 `errs=[]` 下结论。**`errs=[]` 是假阴性**（路径压根没跑到），不能拿它排除自家代码；而真正该看的 `at et.reportAllChanges` 就明摆在栈里。
2. **随后的「纠偏」——把对的结论改错了**：为验证「是 edu.js 坏行」，往 mock 注入 `[None]+rows` 去复现，复现出来的却是 `reading 'className'`（首列先炸），**与 `startTime` 不是同一处**；我把「同族」当成证据，认定用户报的就是这条 ⇒ 写进文档 + 给模板与 `loadQuery` 加双保险。加固本身无害（后端 join 确实可能吐 null 行，属**独立的理论风险**），但**它不是这条报错的原因，也没修好任何东西** —— 重启后那个 VM 报错照旧出现，这正是「越改问题越多」的一部分。

### 可复用判据（按序执行）
1. 栈里有 `VM<数字>` / `<anonymous>`？→ 先怀疑 DevTools / 扩展注入，用特征标识 `grep` 自证「不属于自家代码」，**别急着改业务**。
2. 报错行有独特标识（`reportAllChanges`、`devToolsReportSoftNavs`、指标名 `CLS/INP/LCP`）→ 直接按库归属定位。
3. 要证明「自家渲染路径会不会崩」：**必须注入畸形数据**（`[None]+rows`）再跑 `__errcap=1`；`errs=[]` 只说明「这条路径没跑到」，**不等于「代码没问题」**。
4. 结论落地前回读一遍**证据与结论是否同一处** —— 本轮就是拿 `className` 的复现去解释 `startTime`。

### 附：验证「null 行加固」的复现命令（与上面的 startTime 定案无关，别混为一谈）
```
# 在 serve_preview.py 的关联查询 GET 分支临时 rows=[None]+rows（仅 timetable），重启后：
# 用无头 Chrome 渲染该视图并读 #__errcap（原 probe_edu_table.py 已删除，2026-09-30，手动等价）。
# 加固前：errs=["CE|Cannot read properties of null (reading 'className')"]，整表崩溃

## Vue 编译红线（合并自 9-02）
- **`v-if` / `v-else-if` / `v-else` 链：v-else 必须是最后一个分支，其后不能再接 `v-else-if`**。否则报 Vue compiler-30（"v-else/v-else-if has no adjacent v-if"）→ 整页挂载失败、boot-tip 不消失。给既有 `v-if`/`v-else` 链加分支时，先把原 `v-else` 改成 `v-else-if="原条件"` 再追加新分支。排查白屏先看 console 的 vuejs.org/error-reference 错误码（compiler-30 即此问题）。
# 加固后：errs=[]，坏行被归一化成空串、其余行正常渲染
```


