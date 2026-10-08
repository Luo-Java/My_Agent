// Vue 由 /js/lib/vue.global.prod.js 提供（已本地化，不再依赖 CDN）。
// 缺了它，下面这行解构会抛 ReferenceError，整个文件静默失效、页面停在原始模板。
// 显式拦一下，把原因写进控制台，并保留 chat.html 里 #boot-tip 的提示文案。
if (typeof Vue === 'undefined') {
    console.error('[启动失败] Vue 未加载：请确认 /js/lib/vue.global.prod.js 可访问（HTTP 200）。');
    throw new Error('Vue 未加载，前端无法启动');
}

const { createApp, ref, reactive, computed, onMounted, onUnmounted, nextTick } = Vue;

/** 统一 API 请求：所有 /api/** 调用都必须走这里。
 *  登录态（Authorization: Bearer）与 401 就地弹登录框由 auth.js 统一处理，本函数只做转发。
 *  附件图片走 /files/**（不在鉴权拦截范围内），<img> 直连即可。 */
function apiFetch(url, options) {
    return window.Auth ? window.Auth.fetch(url, options) : window.fetch(url, options);
}


// 配置 marked：启用 GFM（表格/任务列表/删除线）。
// 注意：marked 自 v5 起已移除 sanitize 选项（旧注释「关闭 sanitize」是过时说法），它现在**只做**
// Markdown → HTML，URL 消毒一概不管 —— 所以 renderMd 自己补一层清理（见 sanitizeHtml）。
if (typeof marked !== 'undefined') {
    marked.setOptions({
        gfm: true,
        breaks: true
    });
}

/** 判断链接/图片的 URL 是否安全：只放行 http(s) / mailto / tel 与站内相对路径。
 *  判断前先剥掉控制字符 —— 浏览器解析 URL 时会忽略 href 里的 \t \n \r，
 *  `java\nscript:alert(1)` 照样当 javascript: 执行，不先剥就等于没拦。 */
function isSafeUrl(raw) {
    if (raw == null) return false;
    const url = String(raw).replace(/[\u0000-\u0020]/g, '').toLowerCase();
    if (url === '') return false;
    if (url.startsWith('#') || url.startsWith('/') || url.startsWith('./')
        || url.startsWith('../') || url.startsWith('?')) {
        return true;    // 锚点 / 站内绝对路径 / 相对路径
    }
    const scheme = /^([a-z][a-z0-9+.\-]*):/.exec(url);
    return !scheme || ['http', 'https', 'mailto', 'tel'].includes(scheme[1]);
}

/** 对 marked 产出的 HTML 做白名单清理：拔掉可执行标签、事件属性与非法 URL 协议。
 *  为什么不引 DOMPurify：输入在进 marked 之前已把 & < > 转义，marked 能产出的标签本就只有
 *  a/img/code/pre/table/h1-6/p/ul/ol/li/blockquote/em/strong/del/hr/br 这些，真正残留的面只剩
 *  a[href] / img[src] 的协议（`[x](javascript:...)` 走的是 Markdown 链接语法，转义挡不住）。
 *  DOMParser + 协议白名单已足够覆盖这点残留，不必为此再往 static/js/lib 塞一个 200KB 依赖。 */
function sanitizeHtml(html) {
    const doc = new DOMParser().parseFromString(html, 'text/html');
    doc.querySelectorAll('script,iframe,object,embed,form,style,link,meta,base').forEach(function (n) { n.remove(); });
    doc.querySelectorAll('*').forEach(function (el) {
        Array.prototype.slice.call(el.attributes).forEach(function (attr) {
            const name = attr.name.toLowerCase();
            if (name.indexOf('on') === 0 || name === 'srcdoc' || name === 'style') {
                el.removeAttribute(attr.name);
            }
        });
    });
    doc.querySelectorAll('a[href]').forEach(function (a) {
        if (!isSafeUrl(a.getAttribute('href'))) {
            a.removeAttribute('href');
            return;
        }
        a.setAttribute('rel', 'noopener noreferrer');
        a.setAttribute('target', '_blank');
    });
    doc.querySelectorAll('img[src]').forEach(function (img) {
        if (!isSafeUrl(img.getAttribute('src'))) img.removeAttribute('src');
    });
    return doc.body.innerHTML;
}

/** 引用回链：把正文里的 [n] 角标标记为可点击的 data-idx 元素，供点击时定位到下方「引用来源」的第 n 条。
 *  <p>为什么不用 DOMParser：renderMd 在流式期间每来一片 token 就跑一次，再解析一遍 DOM 等于把每次渲染
 *  的开销翻倍。这里按标签切分字符串，只改「标签之外」的文本段；并跳过 <pre>/<code>/<a> 的内容——
 *  那三处出现的 [n] 分别是代码、数组下标与链接文字，改了就是错的。
 *  <p>注意：必须在 echarts 占位还原<b>之后</b>调用，否则 data-option 里的 JSON 数组会被误判成角标。 */
const CITE_REF_RE = /\[(\d{1,3})\]/g;
function linkifyCitations(html) {
    if (!html || html.indexOf('[') < 0) return html;
    const parts = html.split(/(<[^>]*>)/);
    let inPre = 0, inCode = 0, inA = 0;
    for (let i = 0; i < parts.length; i++) {
        const seg = parts[i];
        if (!seg) continue;
        if (seg.charCodeAt(0) === 60) {          // 60 = '<'：本段是标签
            const t = seg.toLowerCase();
            if (t.startsWith('<pre')) inPre++;
            else if (t.startsWith('</pre')) inPre = Math.max(0, inPre - 1);
            else if (t.startsWith('<code')) inCode++;
            else if (t.startsWith('</code')) inCode = Math.max(0, inCode - 1);
            else if (t.startsWith('<a ')) inA++;
            else if (t.startsWith('</a')) inA = Math.max(0, inA - 1);
            continue;
        }
        if (inPre || inCode || inA) continue;
        parts[i] = seg.replace(CITE_REF_RE, (m, n) =>
            '<sup class="cite-ref" data-idx="' + n + '" title="定位到引用来源第 ' + n + ' 条">[' + n + ']</sup>');
    }
    return parts.join('');
}

/** 将 Markdown 文本转为 HTML（先转义 HTML 标签 → marked 渲染 → 再对产物做一次 URL 协议白名单清理）。
 *  ```echarts 代码块会被识别并替换为图表容器 div（.echarts-box，data-option 存 JSON），
 *  由 renderCharts() 用 ECharts 渲染成真正的图表；JSON 未完整时保留为代码块。 */
function renderMd(text) {
    if (!text) return '';
    if (typeof marked === 'undefined') return escapeHtml(text);
    // 1) 先转义用户输入中的 HTML 标签，同时抽出 echarts 代码块（避免占位符被转义/解析破坏）
    const charts = [];
    const escaped = text
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/```echarts\s*\n?([\s\S]*?)```/g, (m, json) => {
            const idx = charts.length;
            charts.push({ json: json || '' });
            return '\n\n@@ECHARTS_' + idx + '@@\n\n';
        });
    // 2) 交给 marked 渲染
    let html;
    try {
        const r = marked.parse(escaped);
        html = typeof r === 'string' ? r : escaped;
    } catch {
        html = escaped;
    }
    // 3) 清理 marked 产物（只清它 —— 第 4 步拼出的图表容器是我们自己的安全 HTML，不需要过这一道）
    html = sanitizeHtml(html);
    // 4) 还原图表占位：JSON 合法 → 图表容器 div；非法（流式未完整）→ 保留代码块
    html = html.replace(/@@ECHARTS_(\d+)@@/g, (m, i) => {
        const c = charts[+i];
        if (!c) return '';
        try {
            JSON.parse(c.json);
            const safe = escapeHtml(c.json).replace(/"/g, '&quot;');
            return '<div class="echarts-box" data-option="' + safe + '"></div>';
        } catch (e) {
            return '<pre><code class="language-echarts">' + escapeHtml(c.json) + '</code></pre>';
        }
    });
    // 5) 引用回链：正文里的 [n] 变成可点击角标（点击定位到「引用来源」里的第 n 条）
    return linkifyCitations(html);
}

/** ECharts 深色主题：页面已改为深色科技风，ECharts 默认浅色主题的深色文字在暗底上看不清。
 *  只改「文字 / 轴线 / 分隔线 / 提示框」这类框架级配色，不动模型给的系列色，
 *  保证图表配色仍由数据决定，而坐标、图例、tooltip 与页面同色系。
 *  注册一次即可（重复注册会告警），故用 flag 守卫。 */
const ECHARTS_THEME = 'agent-dark';
let echartsThemeReady = false;
function ensureEchartsTheme() {
    if (echartsThemeReady || typeof echarts === 'undefined') return;
    const axisLineColor = 'rgba(110, 160, 255, .28)';
    const splitLineColor = 'rgba(110, 160, 255, .10)';
    const axis = {
        axisLine: { lineStyle: { color: axisLineColor } },
        axisTick: { lineStyle: { color: axisLineColor } },
        axisLabel: { color: '#93a6c8' },
        splitLine: { lineStyle: { color: splitLineColor } },
        splitArea: { show: false },
        nameTextStyle: { color: '#93a6c8' }
    };
    echarts.registerTheme(ECHARTS_THEME, {
        color: ['#3b82f6', '#22d3ee', '#a78bfa', '#34d399', '#fbbf24', '#f87171'],
        backgroundColor: 'transparent',
        textStyle: { color: '#cfe0ff' },
        title: {
            textStyle: { color: '#e8eefc' },
            subtextStyle: { color: '#93a6c8' }
        },
        legend: { textStyle: { color: '#93a6c8' } },
        tooltip: {
            backgroundColor: 'rgba(8, 14, 26, .95)',
            borderColor: 'rgba(110, 160, 255, .32)',
            borderWidth: 1,
            textStyle: { color: '#e8eefc' },
            axisPointer: {
                lineStyle: { color: 'rgba(110, 160, 255, .45)' },
                crossStyle: { color: 'rgba(110, 160, 255, .45)' },
                label: { color: '#e8eefc', backgroundColor: '#1b2a4a' }
            }
        },
        categoryAxis: axis,
        valueAxis: axis,
        timeAxis: axis,
        logAxis: axis,
        dataZoom: {
            textStyle: { color: '#93a6c8' },
            handleStyle: { color: '#3b82f6' }
        }
    });
    echartsThemeReady = true;
}

/** 渲染所有 .echarts-box 容器（ECharts 图表）。防抖 300ms：流式高频重建 DOM 时避免反复 init。
 *  流结束时需手动调用一次强制渲染。 */
let chartRenderTimer = null;
function renderCharts() {
    if (typeof echarts === 'undefined') return;
    if (chartRenderTimer) return; // 已有待执行的渲染任务，合并
    chartRenderTimer = setTimeout(() => {
        chartRenderTimer = null;
        renderChartsNow();
    }, 300);
}
function renderChartsNow() {
    if (typeof echarts === 'undefined') return;
    nextTick(() => {
        document.querySelectorAll('.echarts-box').forEach(el => {
            if (el.dataset.inited) return; // 已初始化，跳过
            let opt;
            try { opt = JSON.parse(el.dataset.option); } catch (e) { return; }
            if (!opt) return;
            try {
                ensureEchartsTheme();
                const chart = echarts.init(el, ECHARTS_THEME);
                chart.setOption(opt);
                el.dataset.inited = '1';
            } catch (e) { /* 单个图表失败不影响其余消息 */ }
        });
    });
}

/** 纯文本转义（marked 未加载时的兜底）。 */
function escapeHtml(text) {
    return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** 构造一条消息对象，预渲染 html 字段（v-html 直接绑定，避免流式突变不刷新）。
 *  version：每次内容更新自增，用作 v-html 所在 DOM 的 :key，强制 Vue 重建节点，
 *  规避流式高频更新下 v-html 未刷新（DOM 停留在中间态）导致的 Markdown 未渲染问题。
 *  citations：AI 消息的 RAG 引用来源（[{index,kbName,source,score}]），来自历史接口或当轮 SSE citations 事件。
 *  extra：仅历史接口能提供的写回类字段（{ id, memoryExcluded }）—— 本轮流式新增的消息还没落库主键，
 *  故没有这两项，「不参与记忆」开关在那两条上会被禁用（刷新后拿到历史即可用）。传对象而不是再加两个
 *  位置参数，是为了让调用处一眼看出「这些是可选的历史侧字段」。 */
function toMsg(role, content, attachments, citations, turn, extra) {
    const e = extra || {};
    return {
        role, content: content || '', html: renderMd(content || ''), version: 0,
        attachments: (attachments && attachments.length) ? attachments : undefined,
        citations: (citations && citations.length) ? citations : undefined,
        citesOpen: true,
        // 对话分支版本（同一轮提问的第几版 / 共几版）：只有分过叉的轮才有，切换器据此显隐
        turn: turn || null,
        // 消息主键（写回用）与「不参与记忆」标记；两者都只在历史加载时有值
        messageId: (e.id != null) ? e.id : null,
        memoryExcluded: !!e.memoryExcluded,
        memoryBusy: false,
        // 消息反馈（👎）：由 loadFeedback() 按 messageId 合并进来；null = 还没评价过
        feedback: e.feedback || null,
        fbOpen: false, fbSaving: false, fbPromoting: false,
        fbReason: 'ANSWERS_OFF', fbComment: '', fbError: ''
    };
}

// ── 自绘下拉组件（替代原生 select）───────────────────────────────────────────
// 原生 select 展开的弹层是操作系统绘制的独立窗口（Windows 上不受页面渲染树管辖）：
// 容器底色 / 边框 / 圆角 / 阴影 / 开合动画全由系统决定，CSS 碰不到；唯一能影响的 option
// 配色还会让 Chromium 走「先建原生弹层、再套自定义色」的路径，点开闪一帧。改为页内自绘。
// 弹层 Teleport 到 body 并用 fixed 定位：绕开祖先滚动容器的 overflow 裁剪
// （.modal-body / 知识库面板都是 overflow:auto），也避开 .modal-mask 的 backdrop-filter
// 建立包含块带来的干扰（fixed 相对遮罩定位，而遮罩本身 inset:0 全屏）。
const UiSelect = {
    name: 'UiSelect',
    props: {
        modelValue: { default: null },
        options: { type: Array, default: () => [] },
        placeholder: { type: String, default: '请选择' },
        title: { type: String, default: '' },
        size: { type: String, default: '' },
        disabled: { type: Boolean, default: false }
    },
    emits: ['update:modelValue', 'change'],
    setup(props, { emit }) {
        const open = ref(false);
        const activeIndex = ref(-1);
        const trigger = ref(null);
        const pop = ref(null);
        const pos = ref({});

        // value 保持原类型（数字 / 字符串），故一律用全等比较；空串是「全部来源」的合法取值
        const currentIndex = computed(() => props.options.findIndex(o => o.value === props.modelValue));
        const currentLabel = computed(() => {
            const o = props.options[currentIndex.value];
            return o ? o.label : '';
        });

        // 弹层定位：贴着 trigger，下方空间不足就向上弹；高度按可用空间收敛
        function measure() {
            const el = trigger.value;
            if (!el) return;
            const r = el.getBoundingClientRect();
            const gap = 6;
            const below = window.innerHeight - r.bottom - gap - 8;
            const above = r.top - gap - 8;
            const up = below < 160 && above > below;
            const maxHeight = Math.max(120, Math.min(260, up ? above : below));
            pos.value = up
                ? { left: r.left + 'px', width: r.width + 'px', bottom: (window.innerHeight - r.top + gap) + 'px', maxHeight: maxHeight + 'px' }
                : { left: r.left + 'px', width: r.width + 'px', top: (r.bottom + gap) + 'px', maxHeight: maxHeight + 'px' };
        }

        function scrollActive() {
            const p = pop.value;
            if (!p) return;
            const el = p.children[activeIndex.value];
            if (!el) return;
            const top = el.offsetTop;
            const bottom = top + el.offsetHeight;
            if (top < p.scrollTop) p.scrollTop = top;
            else if (bottom > p.scrollTop + p.clientHeight) p.scrollTop = bottom - p.clientHeight;
        }

        function openPop() {
            if (props.disabled || !props.options.length) return;
            open.value = true;
            activeIndex.value = currentIndex.value >= 0 ? currentIndex.value : 0;
            nextTick(() => { measure(); scrollActive(); });
        }

        function close() { open.value = false; }

        function toggle() { open.value ? close() : openPop(); }

        function pick(o) {
            if (o.disabled) return;
            if (o.value !== props.modelValue) {
                emit('update:modelValue', o.value);
                emit('change', o.value);
            }
            close();
        }

        function onKeydown(e) {
            if (props.disabled) return;
            const k = e.key;
            if (!open.value) {
                if (k === 'Enter' || k === ' ' || k === 'ArrowDown' || k === 'ArrowUp') {
                    e.preventDefault();
                    openPop();
                }
                return;
            }
            if (k === 'Escape') { e.preventDefault(); close(); return; }
            if (k === 'Tab') { close(); return; }
            if (k === 'Enter' || k === ' ') {
                e.preventDefault();
                const o = props.options[activeIndex.value];
                if (o) pick(o);
                return;
            }
            if (k === 'ArrowDown' || k === 'ArrowUp') {
                e.preventDefault();
                const n = props.options.length;
                if (!n) return;
                let i = activeIndex.value + (k === 'ArrowDown' ? 1 : -1);
                if (i < 0) i = n - 1;
                if (i >= n) i = 0;
                activeIndex.value = i;
                nextTick(scrollActive);
            }
        }

        // 点击组件与外点关闭：弹层 Teleport 到了 body，所以 trigger 和 pop 都要判
        function onDocDown(e) {
            const t = e.target;
            if ((trigger.value && trigger.value.contains(t)) || (pop.value && pop.value.contains(t))) return;
            close();
        }
        function onReposition() { if (open.value) measure(); }

        onMounted(() => {
            document.addEventListener('mousedown', onDocDown, true);
            window.addEventListener('resize', onReposition);
            window.addEventListener('scroll', onReposition, true);
        });
        onUnmounted(() => {
            document.removeEventListener('mousedown', onDocDown, true);
            window.removeEventListener('resize', onReposition);
            window.removeEventListener('scroll', onReposition, true);
        });

        return { open, activeIndex, trigger, pop, pos, currentIndex, currentLabel, toggle, pick, onKeydown };
    },
    template: `
        <div class="ui-select" :class="{ 'is-open': open, 'is-disabled': disabled, 'is-sm': size === 'sm' }">
            <button type="button" class="ui-select-trigger" ref="trigger" :title="title" :disabled="disabled"
                    @click="toggle" @keydown="onKeydown">
                <span class="ui-select-label" :class="{ 'is-placeholder': !currentLabel }">{{ currentLabel || placeholder }}</span>
                <span class="ui-select-caret" aria-hidden="true"></span>
            </button>
            <Teleport to="body">
                <div v-if="open" class="ui-select-pop" ref="pop" :style="pos">
                    <div v-for="(o, i) in options" :key="o.value" class="ui-select-opt"
                         :class="{ 'is-active': i === activeIndex, 'is-selected': i === currentIndex, 'is-disabled': o.disabled }"
                         @click="pick(o)" @mousemove="activeIndex = i">
                        <span class="ui-select-opt-text">{{ o.label }}</span>
                        <span v-if="i === currentIndex" class="ui-select-tick" aria-hidden="true"></span>
                    </div>
                </div>
            </Teleport>
        </div>`
};

const app = createApp({
    setup() {
        const conversations = ref([]);
        const agents = ref([]);
        // 可用工具清单（GET /api/agent/tools）：供智能体弹窗「工具装配」区域选择，按 group 分组展示
        const availableTools = ref([]);
        const messages = ref([]);
        const input = ref('');
        const loading = ref(false);
        const scroll = ref(null);
        const currentId = ref(null);
        const editingId = ref(null);
        const editingTitle = ref('');
        // 输入框「智能规划」开关：勾选=按规划模式执行（动态规划器编排多智能体），取消=普通对话。
        // 随会话切换同步（规划会话默认勾选），发送时随请求写回会话（刷新后保持）。
        const planMode = ref(false);

        // 顶栏登录用户区（用户名 + 下拉菜单）不在这里 —— 整块由 js/auth.js 渲染到 data-auth-nav
        // 挂载点上，四页共用同一份实现，本页不再持有任何登录态判断或退出逻辑。

        // 但「按角色显隐的顶栏入口」必须由本页自己持有：💰 成本看板是 ADMIN 专属
        // （后端 CostController 标了 @RequireRole(ADMIN)），普通账号不该看到一个点了就 403 的按钮。
        // 在 setup 里取一次存进 ref —— Auth.getUser() 读的是 localStorage，不是响应式的，
        // 模板里直接写 Auth.hasRole('ADMIN') 只会求值一次且不会随登录态变化重算。
        // 取不到（Auth 未加载 / 本地无 user）一律当非管理员：UI 侧失败关闭，真正的闸门在后端。
        const isAdmin = ref(!!(window.Auth && window.Auth.hasRole && window.Auth.hasRole('ADMIN')));

        // 输入框「RAG」开关（会话级 RAG 开关）：false=不使用 RAG；true=每轮自动检索
        // 「通用知识库 + 路由智能体专属库」（不手动选库）。变更即写回会话（刷新后保持）。
        const ragEnabled = ref(false);

        // 规划模式「先看计划」开关：true=规划只产出计划并暂停，用户在卡片上确认后才执行；false=规划后直接执行。
        // 只在规划模式下有意义（模板里 v-if 控制显隐），随会话切换回显、拨动即写回。
        const plannerConfirm = ref(false);

        // 「⚖ 评审」开关（会话级）：true=本轮由 LLM 选若干个候选智能体并行独立作答，再由裁决者综合成最终回答；
        // false=普通单智能体回答（默认）。候选与最终答案同推（候选走 review 事件、只作展示不落记忆）。
        // 与规划互斥：开启评审时后端会自动关掉 planner，这里同步前端状态（见 onReviewChange）。
        const reviewEnabled = ref(false);

        // 「🔎 跨会话」开关（会话级）：true=每轮先用 LLM 从本轮问题里抽关键词，在本人「其他会话」的历史消息里
        // 做关键词召回并注入上下文；false=不检索（默认）。只查本人会话、天然排除当前会话。
        const crossSession = ref(false);

        // 当前会话的未完成任务（RUNNING）：有则显示顶部「继续执行」提示条（断点续跑入口）。
        // 切换会话 / 发送完成 / 续跑完成后刷新；无 RUNNING 任务为 null。
        const runningTask = ref(null);

        // 局部重规划请求进行中（按钮态）：一次模型往返、几秒量级，期间禁用按钮防重复提交。
        const replanning = ref(false);

        // 请求暂停计划执行中（按钮态）。暂停本身只置一个标志位（重复请求无害），但连点会让「已请求暂停」
        // 的提示刷屏，且用户会以为没生效 —— 故按钮点一次就禁用。
        const pausing = ref(false);

        // 跳过某一步执行中（按钮态）；同一时刻只允许跳一步。
        const skipping = ref(false);

        // 续跑通路（点「执行计划 / 继续执行」）执行中。与 loading 分开是因为 loading 也覆盖普通发送，
        // 而「暂停」只对真的在跑的计划任务有意义（见 canPause）。
        const taskExecuting = ref(false);

        /**
         * 是否显示「暂停」入口：只有规划任务真的在跑时才有意义。
         *
         * 覆盖两种跑法：① 续跑通路（taskExecuting，点过「执行计划 / 继续执行」）；② 会话里发消息触发的
         * 首轮规划（规划模式会话生成中）—— 它的执行体在 /stream 的流里，前端拿不到任务 id，只能用
         * 「规划模式 + 生成中」近似判断。若这一轮其实退化成了普通回答，后端会明确回「当前会话没有未完成
         * 的计划」，不会静默假装暂停成功。
         */
        const canPause = computed(() => taskExecuting.value || (loading.value && currentPlanner.value));

        // ===== 对话分支（编辑重发 / 重新生成）=====
        // 正在「编辑重发」的用户消息下标；-1 = 不在编辑态。
        // 编辑态下 send() 会先为这一轮开一个新版本（旧版本一条不删、随时可翻回），再用输入框里的新文本重发，
        // 因此重发走的仍是同一条 /api/chat/stream 通路，不需要第二套发送逻辑。
        const editingIndex = ref(-1);

        // ===== 长期记忆面板 =====
        // 双层记忆此前完全黑盒：压缩由后端异步写入，用户看不到「它记住了什么」、也无法纠正记错的内容。
        // 本面板把「逐条长期事实 / 旧版归档 / 滚动摘要」摊开可编辑；summarizedCount 是执行游标，只展示不可改。
        // window 是另一半黑盒 —— 本轮会注入的那几条原文（与摘要分属两层：摘要 = 已出窗的更早历史）。
        const memoryModal = reactive({
            open: false, loading: false, saving: false, error: '',
            summary: '', coreFacts: '', summarizedCount: 0, messageCount: 0,
            excludedCount: 0, window: [], facts: []
        });

        // 长期事实条目（功能 E）：逐条可改可删。来源分两种 —— USER=手动（自动整理绝不动它）、
        // MERGE=自动（由记忆合并按对话内容维护，改过之后会转成 USER）。这一点必须在 UI 上说清：
        // 用户删掉一条自动条目后若它又出现，那不是 bug，而是「对话里又提到了一次」。
        const factTopics = ['身份', '偏好', '待办', '背景', '其它'];
        const factBusy = ref(false);
        const factAdd = reactive({ topic: '偏好', fact: '' });
        const factEdit = reactive({ id: null, topic: '偏好', fact: '' });

        // ===== 引用回链：查看被引用的那段原文 =====
        // 气泡里的「引用来源」此前只有「库名 · 文件名 · 相关度」，看不到真正回答问题的原文段落，
        // 用户无法判断「这句话是文档里写的还是模型编的」。点引用条目上的「原文」按 chunkId 取块。
        // 块可能已被删除或重新分片（几个月前看到的引用，块后来被重切了），此时后端 404 + 原因，
        // 本弹窗把它原样显示出来 —— 不渲染一段空白，那看起来像「文档里本来就是空的」。
        const chunkModal = reactive({ open: false, loading: false, error: '', chunk: null });

        /** 打开某条引用来源的原文。c 为 KbCitation（含 chunkId / kbId / kbName / source / score）。 */
        async function openCite(c) {
            if (!c || c.chunkId == null) {
                alert('这条来源没有可定位的知识块（可能是早期数据，未记录 chunkId）。');
                return;
            }
            chunkModal.open = true;
            chunkModal.loading = true;
            chunkModal.error = '';
            chunkModal.chunk = null;
            try {
                const resp = await apiFetch('/api/kb/chunk/' + encodeURIComponent(c.chunkId));
                if (!resp.ok) {
                    let msg = 'HTTP ' + resp.status;
                    try {
                        const d = await resp.json();
                        if (d && d.message) msg = d.message;
                    } catch (ignore) { /* 非 JSON 响应体：沿用状态码 */ }
                    throw new Error(msg);
                }
                chunkModal.chunk = await resp.json();
            } catch (e) {
                chunkModal.error = '读取原文失败：' + e.message;
            } finally {
                chunkModal.loading = false;
            }
        }

        function closeCite() {
            chunkModal.open = false;
        }

        /** 点击正文里的 [n] 角标：展开该条消息的「引用来源」并高亮第 n 条。
         *  找不到对应序号时明确告知 —— 模型偶尔会自行标注角标，静默无反应会让人以为链接坏了。 */
        function onCiteClick(ev, m) {
            const el = ev.target;
            if (!el || !el.classList || !el.classList.contains('cite-ref')) return;
            const idx = parseInt(el.dataset.idx, 10);
            const hit = (m.citations || []).find(x => Number(x.index) === idx);
            if (!hit) {
                alert('正文里的 [' + idx + '] 在本轮引用来源里没有对应条目（可能是模型自行标注的角标）。');
                return;
            }
            m.citesOpen = true;
            m.citeHit = idx;
            nextTick(() => {
                const line = document.querySelector('.cite-line.cite-hit');
                if (line && line.scrollIntoView) line.scrollIntoView({ block: 'center', behavior: 'smooth' });
            });
            setTimeout(() => { if (m.citeHit === idx) m.citeHit = null; }, 2000);
        }

        // 智能体导入用的隐藏文件选择器（与附件选择器同样走「点击按钮 → 触发 input」的方式）
        const agentImportInput = ref(null);

        // ===== 成本配额（本人当日 token 用量）=====
        // 由后端 agent.quota 配置控制，默认关闭（此时后端回 enabled=false，前端不展示任何东西）。
        // 前端这里<b>只做刻度展示</b>：真正的闸门在后端入口，超限会被明确拒绝并把原因作为一条 error 事件推回来
        // （按既有错误渲染逻辑红字展示），前端不参与「放不放行」的判断，避免两处规则各说各话。
        const quota = reactive({ enabled: false, exempt: false, used: 0, limit: 0 });

        // ===== 规划模板（把跑顺的步骤骨架沉淀为可复用资产）=====
        // 一个弹窗两个模式共用：'use' 选模板 + 填本次目标 → 生成计划；'save' 把某份计划卡片存成模板。
        // items 只在 use 模式加载；selectedId 为空表示还没选模板（「生成计划」按钮据此禁用）。
        // confirmId：删除采用「点两次确认」（项目不用原生 confirm，也不为一次误删开弹窗）——
        // 第一次点只进入待确认态，再点同一个才真删；选中别的行会清掉它。
        const tplModal = reactive({
            open: false, mode: 'use', loading: false, items: [], selectedId: null, confirmId: null,
            goal: '', name: '', description: '', stepCount: 0, taskId: null,
            saving: false, error: ''
        });

        // ===== 对话附件（图片 / 文档任意文件）=====
        // 待发送附件列表，每项 {file, previewUrl, isImage, type, content, filename}。
        // 图片：previewUrl 用 URL.createObjectURL 本地预览（零上传开销）；
        // 文档：无预览，仅记录文件名，发送时统一调 /api/chat/attachment/process 拿到 type+content；
        // 最终把 content（纯文本）随 ChatRequest.attachments 发给后端，原始二进制不进会话存储。
        // 图片上限 10MB（后端 VisionProperties.maxImageBytes）、文档 15MB（DocumentParserService），
        // 前端统一按 15MB 拦截；最多 5 个。
        const attachments = ref([]);
        const fileInput = ref(null);  // hidden file input，用于 JS 触发文件选择
        const MAX_ATTACHMENTS = 5;
        const MAX_ATTACHMENT_SIZE = 15 * 1024 * 1024;
        const ACCEPT = '.txt,.md,.markdown,.csv,.json,.xml,.yml,.yaml,.properties,.log,.sql,.pdf,.docx,.xlsx,image/*';
        function triggerFilePicker() {
            if (loading.value) return;
            if (fileInput.value) fileInput.value.click();
        }
        function onFilePicked(event) {
            const files = event.target.files;
            if (!files || !files.length) return;
            const incoming = Array.from(files);
            for (const f of incoming) {
                if (attachments.value.length >= MAX_ATTACHMENTS) {
                    alert(`最多 ${MAX_ATTACHMENTS} 个附件，后续未选择`);
                    break;
                }
                if (f.size > MAX_ATTACHMENT_SIZE) {
                    alert(`文件 ${f.name} 超过 ${(MAX_ATTACHMENT_SIZE / 1024 / 1024)}MB，已跳过`);
                    continue;
                }
                const isImage = !!f.type && f.type.startsWith('image/');
                attachments.value.push({
                    file: f,
                    previewUrl: isImage ? URL.createObjectURL(f) : null,
                    isImage,
                    type: null,       // 由后端 /attachment/process 回填
                    content: null,    // 由后端 /attachment/process 回填
                    filename: f.name || (isImage ? 'image' : 'file')
                });
            }
            // 重置 input.value 以便重复选同一文件
            event.target.value = '';
        }
        function removeAttachment(i) {
            const a = attachments.value[i];
            if (a && a.previewUrl) URL.revokeObjectURL(a.previewUrl);
            attachments.value.splice(i, 1);
        }

        // 主区域视图：chat=聊天 | agents=智能体管理 | kbs=知识库管理
        const mainView = ref('chat');

        // ===== 知识库（RAG）状态 =====
        const kbs = ref([]);   // 知识库列表（全局库置顶；列表来自后端，含 docCount/agentId）
        // 新建 / 重命名弹窗：mode=create 为某智能体建专属库；mode=rename 改设置（名称/说明/默认分片策略/默认重叠）
        const kbModal = reactive({ open: false, mode: 'create', id: null, agentId: null, name: '', description: '',
            chunkStrategy: 'recursive', chunkOverlap: 60, saving: false });
        // 库详情视图：kb=当前库；chunks/total=知识块分页；files=已选待上传文件；uploading/msg=上传过程与结果
        const kbFileInput = ref(null);
        const kbDetail = reactive({
            open: false, kb: null, chunks: [], total: 0, files: [], fileList: [],
            uploading: false, drag: false, msg: '', msgOk: true,
            strategies: [], uploadStrategy: 'recursive',
            uploadOverlap: 60, defaultOverlap: 60,
            rechunk: null   // { file, strategy, overlap, busy }：正在切换分片策略 / 重叠的文件
        });
        // 向量副本（Chroma）状态条：connected=是否连上；documentCount=collection 内向量条数（-1=未知）
        const chroma = ref({ connected: false, baseUrl: '', collection: '', documentCount: -1,
            lastError: '', syncing: false, msg: '' });
        // 知识块筛选（纯前端，只作用于已加载的分页数据）：q=内容关键字，source=来源文件
        const chunkFilter = reactive({ q: '', source: '' });

        // 尚无专属知识库的智能体（新建知识库下拉只列这些，一个智能体至多一个库）
        const availableAgents = computed(() =>
            agents.value.filter(a => !kbs.value.some(k => k.agentId === a.id)));

        // 知识库一览统计（列表态顶部统计条）：块数为各库 docCount 求和
        const kbTotalChunks = computed(() => kbs.value.reduce((s, k) => s + (Number(k.docCount) || 0), 0));
        const kbScopedCount = computed(() => kbs.value.filter(k => k.agentId).length);
        const kbGlobalCount = computed(() => kbs.value.length - kbScopedCount.value);

        // 知识块来源候选（仅已加载分页内去重），供「全部来源」下拉
        const chunkSources = computed(() => {
            const set = new Set();
            kbDetail.chunks.forEach(c => { if (c.source) set.add(c.source); });
            return [...set];
        });

        // ── 自绘下拉的选项源：把已有数据映射成 {value,label}，value 保持原类型 ──
        const strategyChoices = computed(() =>
            (kbDetail.strategies || []).map(s => ({ value: s.key, label: s.label })));
        const overlapChoices = computed(() =>
            overlapOptions.map(o => ({ value: o, label: overlapText(o) })));
        const agentChoices = computed(() =>
            availableAgents.value.map(a => ({ value: a.id, label: (a.icon || '🤖') + ' ' + a.name + '（暂无专属库）' })));
        const sourceChoices = computed(() => [
            { value: '', label: '全部来源' },
            ...chunkSources.value.map(s => ({ value: s, label: s }))
        ]);

        // 过滤后的知识块：关键字按内容匹配、来源按文件精确匹配；两者皆空时直出原数组
        const shownChunks = computed(() => {
            const q = chunkFilter.q.trim().toLowerCase();
            const src = chunkFilter.source;
            if (!q && !src) return kbDetail.chunks;
            return kbDetail.chunks.filter(c =>
                (!src || c.source === src) &&
                (!q || String(c.content || '').toLowerCase().includes(q)));
        });

        // 预设智能体图标（emoji）
        const iconPresets = ['🤖', '🎓', '🌐', '💻', '🎨', '📝', '🧮', '🔬', '🗣', '💼'];

        // 智能体创建/编辑弹窗状态
        const agentModal = reactive({
            open: false,
            id: null,
            name: '',
            agentCode: '',
            icon: '',
            description: '',
            systemPrompt: '',
            model: '',
            temperature: null,
            avatarColor: '',
            paramList: [],   // 参数补全列表（结构化，保存时序列化为 JSON 串写入 paramSchema）
            // 工具装配：toolMode ∈ all（全部工具，存 null）/ none（不使用，存 "[]"）/ custom（白名单，存 JSON 数组）
            toolMode: 'all',
            toolNames: [],   // custom 模式下勾选的工具名（对应 ToolDefinition.name）
            toolSearch: '',  // 工具列表搜索词（仅影响展示，不影响已勾选结果）
            saving: false,
            genLoading: false
        });

        // 智能体查看弹窗状态（只读详情）
        const agentView = reactive({
            open: false,
            agent: null
        });

        function scrollToBottom() {
            nextTick(() => {
                if (scroll.value) scroll.value.scrollTop = scroll.value.scrollHeight;
            });
        }

        /** 是否展示某条消息的重做按钮。<b>生成中一律不展示</b>：此时截断历史会与正在写入的那一轮撞车。
         *  用户消息要有可重发的文本或附件；AI 回复即使为空（失败轮）也允许重新生成。 */
        function canRedo(i) {
            const m = messages.value[i];
            if (loading.value || !m) return false;
            return m.role === 'user' ? !!(m.content || (m.attachments && m.attachments.length)) : true;
        }

        // 根据智能体 ID 取名称（侧边栏/会话徽标用）
        function agentName(id) {
            const a = agents.value.find(x => x.id === id);
            return a ? a.name : '';
        }

        // 根据智能体 ID 取图标（emoji，默认 🤖）
        function agentIcon(id) {
            const a = agents.value.find(x => x.id === id);
            return (a && a.icon) ? a.icon : '🤖';
        }

        // 当前会话绑定的智能体 ID（为 null 表示普通会话；绑定智能体时规划开关置灰——planner 与 agentId 互斥）
        const currentAgentId = computed(() => {
            const conv = conversations.value.find(c => c.id === currentId.value);
            return (conv && conv.agentId) ? conv.agentId : null;
        });

        // 当前会话绑定的智能体名称（用于顶部徽标）
        const currentAgentName = computed(() => {
            const conv = conversations.value.find(c => c.id === currentId.value);
            if (conv && conv.agentId) return agentName(conv.agentId);
            return '';
        });

        // 当前会话绑定的智能体图标
        const currentAgentIcon = computed(() => {
            const conv = conversations.value.find(c => c.id === currentId.value);
            if (conv && conv.agentId) return agentIcon(conv.agentId);
            return '';
        });

        // 当前会话是否处于规划模式（由后端 planner 标志决定）
        const currentPlanner = computed(() => {
            const conv = conversations.value.find(c => c.id === currentId.value);
            return !!(conv && conv.planner);
        });

        // 当前会话是否开启 RAG（顶部徽标 / 输入框开关回显依据）
        const currentRagOn = computed(() => {
            const conv = conversations.value.find(c => c.id === currentId.value);
            return !!(conv && conv.ragEnabled);
        });

        // 参数补全（澄清追问）进行中：已问次数 / 上限。
        // 两个字段由后端从 conversation.clarify_state 解析后随会话列表下发（0/null = 无追问，徽标不渲染）。
        // 不在这里读原始 JSON：内部状态结构只应活在服务端，前端只需要「问到第几次」。
        const currentClarifyAsked = computed(() => {
            const conv = conversations.value.find(c => c.id === currentId.value);
            return (conv && conv.clarifyAsked) ? conv.clarifyAsked : 0;
        });
        const currentClarifyMax = computed(() => {
            const conv = conversations.value.find(c => c.id === currentId.value);
            return (conv && conv.clarifyMax) ? conv.clarifyMax : 0;
        });

        // RAG 开关变更 → 即时写回会话（纯开关，不选库），刷新后保持上次选择。
        // 开启后每轮自动检索「通用知识库 + 路由到智能体时其专属库」，由后端 KbService 决定目标库。
        async function onRagEnabledChange() {
            // 尚未建会话：同样只留在前端状态，由 send() 建会话后补写回（否则本轮检索不会生效）。
            // 刻意不在此处建会话 —— 避免「碰一下开关就多出一个没说过话的空会话」。
            if (!currentId.value) return;
            try {
                const resp = await apiFetch('/api/chat/conversation/' + encodeURIComponent(currentId.value) + '/rag', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ enabled: ragEnabled.value })
                });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                // 同步侧边栏会话对象：顶部徽标即时更新、切走再切回也能回显
                const conv = conversations.value.find(c => c.id === currentId.value);
                if (conv) conv.ragEnabled = ragEnabled.value;
            } catch (e) {
                alert('保存 RAG 开关失败：' + e.message);
            }
        }

        // 智能规划开关变更 → 即时写回会话（与 RAG 开关对称，拨动即持久化），刷新后保持上次选择。
        // planner 与 agentId 互斥：绑定智能体的会话开关已置灰，后端另有防御校验拒绝越权开启。
        async function onPlannerChange() {
            // 尚未建会话（首页进入 / 刷新后的空白页）：保留本次选择，等首次发送建会话时由 send() 补写回。
            // 不能强制复位为 false —— 那会让开关在空白页上「拨一下立刻弹回」，看起来像点不动。
            if (!currentId.value) return;
            try {
                const resp = await apiFetch('/api/chat/conversation/' + encodeURIComponent(currentId.value) + '/planner', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ enabled: planMode.value })
                });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                // 同步侧边栏会话对象：顶部徽标与会话列表「规划」标记即时更新
                const conv = conversations.value.find(c => c.id === currentId.value);
                if (conv) conv.planner = planMode.value;
                // 开规划顺手关评审（后端 updatePlannerSwitch 同口径）：两者都是编排形态，不允许同时亮着
                if (planMode.value && reviewEnabled.value) {
                    reviewEnabled.value = false;
                    if (conv) conv.reviewEnabled = false;
                }
            } catch (e) {
                planMode.value = !planMode.value;   // 保存失败回滚开关，避免 UI 与后端形态脱节
                alert('保存智能规划开关失败：' + e.message);
            }
        }

        // 「先看计划」开关变更 → 即时写回会话（与规划 / RAG 开关对称）。仅在规划模式下显示，
        // 但关掉规划时不清它 —— 它记的是「偏好」（下次开规划是否先看计划），不是「当前状态」。
        async function onPlannerConfirmChange() {
            if (!currentId.value) return;
            try {
                const resp = await apiFetch('/api/chat/conversation/' + encodeURIComponent(currentId.value) + '/planner-confirm', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ enabled: plannerConfirm.value })
                });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                const conv = conversations.value.find(c => c.id === currentId.value);
                if (conv) conv.plannerConfirm = plannerConfirm.value;
            } catch (e) {
                plannerConfirm.value = !plannerConfirm.value;
                alert('保存「先看计划」开关失败：' + e.message);
            }
        }

        // 「⚖ 评审」开关变更 → 即时写回会话（与 RAG / 规划开关对称）。
        // 与规划互斥：开启评审时后端会把 planner 置 false，这里必须把本地 planMode 也置 false ——
        // 否则开关还亮着、顶部徽标还显示「规划」，而实际走的是评审链路，UI 与行为脱节。
        async function onReviewChange() {
            if (!currentId.value) return;
            try {
                const resp = await apiFetch('/api/chat/conversation/' + encodeURIComponent(currentId.value) + '/review', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ enabled: reviewEnabled.value })
                });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                const conv = conversations.value.find(c => c.id === currentId.value);
                if (conv) conv.reviewEnabled = reviewEnabled.value;
                // 开评审顺手关规划（后端同口径）：本地状态一并对齐，不留「两个都亮着」的中间态
                if (reviewEnabled.value && planMode.value) {
                    planMode.value = false;
                    if (conv) conv.planner = false;
                }
            } catch (e) {
                reviewEnabled.value = !reviewEnabled.value;   // 保存失败回滚开关
                alert('保存评审开关失败：' + e.message);
            }
        }

        // 「🔎 跨会话」开关变更 → 即时写回会话。与规划 / 评审无关，是独立的检索增强开关（可与任何形态叠加）。
        async function onCrossSessionChange() {
            if (!currentId.value) return;
            try {
                const resp = await apiFetch('/api/chat/conversation/' + encodeURIComponent(currentId.value) + '/cross-session', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ enabled: crossSession.value })
                });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                const conv = conversations.value.find(c => c.id === currentId.value);
                if (conv) conv.crossSession = crossSession.value;
            } catch (e) {
                crossSession.value = !crossSession.value;
                alert('保存跨会话开关失败：' + e.message);
            }
        }

        // 新建对话（总是创建新会话；无论当前是否已有会话）
        async function goChat() {
            mainView.value = 'chat';
            await newConversation();
        }

        // 切到智能体管理视图
        function goAgents() {
            mainView.value = 'agents';
            loadAgents();
        }

        // 切到知识库管理视图
        function goKbs() {
            mainView.value = 'kbs';
            kbDetail.open = false;
            loadKbs();
        }

        // ===== 会话 =====
        // 历史加载的请求序号：只允许「最后一次」请求的结果写进 messages。
        // 快速点 A→B 时 A 的响应可能后到，把 B 的消息列表覆盖成 A 的 —— 序号对不上就直接丢弃。
        let historySeq = 0;

        // 只把列表填进侧边栏，不自动打开任何会话：进入 / 刷新 chat.html 一律落在空白欢迎页，
        // 只有在侧边栏点击某条历史时才打开它。currentId 保持 null，
        // 用户直接提问时由 send() 兜底新建会话，不会产生空会话记录。
        async function loadConversations() {
            try {
                const resp = await apiFetch('/api/chat/conversations');
                if (!resp.ok) return;
                conversations.value = await resp.json();
            } catch (e) { /* 忽略：后端未启动或数据库未就绪 */ }
        }

        // 查询当前会话的未完成任务（RUNNING）：有则显示「继续执行」提示条。无会话或无任务时置 null。
        async function loadRunningTask() {
            if (!currentId.value) { runningTask.value = null; return; }
            try {
                const resp = await apiFetch('/api/chat/task/running?conversationId=' + encodeURIComponent(currentId.value));
                if (!resp.ok) { runningTask.value = null; return; }
                runningTask.value = await resp.json();
            } catch (e) { runningTask.value = null; }
        }

        // ===== 成本配额：刷新本人当日用量 =====
        /** 拉取本人配额用量（未启用时后端回 enabled=false）。纯提示用途，取不到就不显示，不打扰任何流程。 */
        async function loadQuota() {
            try {
                const resp = await apiFetch('/api/chat/quota');
                if (!resp.ok) return;
                const d = await resp.json();
                quota.enabled = !!d.enabled;
                quota.exempt = !!d.exempt;
                quota.used = d.used || 0;
                quota.limit = d.limit || 0;
            } catch (e) { /* 配额只是刻度：查询失败不提示、不重试 */ }
        }

        // 开启新对话（默认助手，不绑定智能体）
        async function newConversation() {
            try {
                const resp = await apiFetch('/api/chat/conversation', { method: 'POST' });
                if (!resp.ok) { alert('创建会话失败'); return; }
                const data = await resp.json();
                historySeq++;   // 作废在途的历史请求：新的空会话不该被上一个会话的历史覆盖
                currentId.value = data.conversationId;
                messages.value = [];
                conversations.value = [
                    { id: data.conversationId, title: data.title, updatedAt: data.createdAt, agentId: data.agentId, planner: data.planner, ragEnabled: !!data.ragEnabled, reviewEnabled: !!data.reviewEnabled, crossSession: !!data.crossSession },
                    ...conversations.value
                ];
                input.value = '';
                planMode.value = false; // 新建普通会话：规划开关默认关闭
                ragEnabled.value = false;   // 新建会话默认关闭 RAG；需要时在输入框自行开启
                plannerConfirm.value = false; // 「先看计划」默认关闭（规划模式默认直接执行）
                reviewEnabled.value = false;  // 评审默认关闭（多候选 = 多次模型调用，显式开启才走）
                crossSession.value = false;   // 跨会话检索默认关闭（需要时在输入框自行开启）
                runningTask.value = null;   // 新会话无未完成任务
                mainView.value = 'chat';
                scrollToBottom();
            } catch (e) {
                alert('创建会话失败：' + e.message);
            }
        }

        // 用某个智能体开启新对话
        async function startAgentChat(a) {
            try {
                const resp = await apiFetch('/api/chat/conversation', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ agentId: a.id })
                });
                if (!resp.ok) { alert('创建会话失败'); return; }
                const data = await resp.json();
                historySeq++;   // 同 newConversation：作废在途的历史请求
                currentId.value = data.conversationId;
                messages.value = [];
                conversations.value = [
                    { id: data.conversationId, title: data.title, updatedAt: data.createdAt, agentId: data.agentId, planner: data.planner, ragEnabled: !!data.ragEnabled, reviewEnabled: !!data.reviewEnabled, crossSession: !!data.crossSession },
                    ...conversations.value
                ];
                input.value = '';
                planMode.value = false; // 智能体会话不支持规划（planner 与 agentId 互斥），开关强制关闭
                ragEnabled.value = false;   // 新会话默认关闭 RAG；需要时在输入框自行开启
                plannerConfirm.value = false; // 「先看计划」仅规划模式有意义，智能体会话恒为关闭
                reviewEnabled.value = false;  // 评审默认关闭；智能体会话里绑定的这个智能体会固定占一个候选位
                crossSession.value = false;   // 跨会话检索默认关闭
                runningTask.value = null;   // 新会话无未完成任务
                agentView.open = false; // 从查看弹窗发起对话后关闭弹窗
                mainView.value = 'chat'; // 切回聊天视图
                scrollToBottom();
            } catch (e) {
                alert('创建会话失败：' + e.message);
            }
        }

        async function selectConversation(id) {
            const seq = ++historySeq;   // 本次请求的序号；期间再切会话会让它过期
            currentId.value = id;
            messages.value = [];
            mainView.value = 'chat'; // 从智能体管理视图点击历史时切回聊天视图
            // 规划开关跟随会话形态：规划会话默认勾选（写回机制保证刷新后仍保持上次选择）
            const conv = conversations.value.find(c => c.id === id);
            planMode.value = !!(conv && conv.planner);
            // RAG 开关跟随会话：上次的开关状态回显（false=关闭）
            ragEnabled.value = !!(conv && conv.ragEnabled);
            // 「先看计划」跟随会话回显（非规划会话后端恒为 false）
            plannerConfirm.value = !!(conv && conv.plannerConfirm);
            // 评审 / 跨会话开关跟随会话回显（后端字段：reviewEnabled / crossSession）
            reviewEnabled.value = !!(conv && conv.reviewEnabled);
            crossSession.value = !!(conv && conv.crossSession);
            loadRunningTask(); // 查询该会话是否有未完成任务（有则显示「继续执行」提示条）
            try {
                const resp = await apiFetch('/api/chat/history?conversationId=' + encodeURIComponent(id));
                const data = resp.ok ? await resp.json() : null;
                // 竞态守卫：resp.json() 也是 await，期间可能又切走了，所以拿到数据后要再判一次
                if (seq !== historySeq) return;
                if (data) {
                    messages.value = (data.messages || []).map(m => toMsg(m.role, m.content, m.attachments, m.citations, m.turn,
                        { id: m.id, memoryExcluded: m.memoryExcluded }));
                }
            } catch (e) { /* 忽略 */ }
            if (seq !== historySeq) return;   // 结果已过期：不写 messages，也不滚动/渲染图表
            await loadFeedback();             // 反馈单独拉一次，按 messageId 合并到消息上（见 loadFeedback 注释）
            scrollToBottom();
            renderChartsNow(); // 历史消息可能含 echarts 块，渲染图表
        }

        function startEdit(c) {
            editingId.value = c.id;
            editingTitle.value = c.title || '新对话';
            nextTick(() => {
                const el = document.querySelector('.conv-edit');
                if (el) { el.focus(); el.select(); }
            });
        }

        async function commitEdit() {
            const id = editingId.value;
            const title = (editingTitle.value || '').trim();
            editingId.value = null;
            if (!id || !title) return;
            try {
                const resp = await apiFetch('/api/chat/conversation/' + encodeURIComponent(id), {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ title })
                });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                const conv = conversations.value.find(c => c.id === id);
                if (conv) conv.title = title;
            } catch (e) {
                alert('重命名失败：' + e.message);
            }
        }

        async function deleteConversation(id) {
            if (!id) return;
            if (!confirm('确定删除该对话及其所有消息吗？此操作不可恢复。')) return;
            try {
                const resp = await apiFetch('/api/chat/conversation/' + encodeURIComponent(id), {
                    method: 'DELETE'
                });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                conversations.value = conversations.value.filter(c => c.id !== id);
                if (currentId.value === id) {
                    currentId.value = null;
                    if (conversations.value.length > 0) {
                        await selectConversation(conversations.value[0].id);
                    } else {
                        await newConversation();
                    }
                }
            } catch (e) {
                alert('删除失败：' + e.message);
            }
        }

        // ===== 对话分支：同一轮提问的多个版本原地并存 =====
        // 编辑重发与重新生成是同一件事的两个入口：都先给那一轮开一个新版本，再走既有流式通路重发。
        // 旧版本留在库里不删，消息上的「1/2 ‹ ›」可以随时翻回去 —— 与「先看计划」「局部重规划」
        // 同一思路：能复用现成通路就不另起一条，这里也就不复制任何发送逻辑。

        /**
         * 给第 keepCount 条消息所在的那一轮开一个新版本，返回 { groupId, version }。
         * <p>此刻后端不会动任何已有消息：旧版本要等本轮确实发出去、消息真的落库之后才失效
         * （否则附件处理失败 / 配额超限 / 内容安全拒绝时，旧版本会凭空消失且没有任何报错）。
         * 所以这里失败直接中止重发 —— 历史没被改过，用户可以重试。
         */
        async function prepareBranch(keepCount) {
            const resp = await apiFetch('/api/chat/conversation/' + encodeURIComponent(currentId.value) + '/branch', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ keepCount })
            });
            if (!resp.ok) {
                let msg = 'HTTP ' + resp.status;
                try { const d = await resp.json(); if (d && d.message) msg = d.message; } catch (ignore) { /* 非 JSON 响应体：沿用状态码 */ }
                throw new Error(msg);
            }
            return await resp.json();
        }

        /**
         * 重新生成第 i 条 AI 回复：把它前面那条提问当作新版本重发一次。
         * <p>与「编辑重发」走完全相同的通路（只是文本原样、不打断用户去改），所以这里不自己调流式接口，
         * 而是设好 editingIndex 后交给 send() —— 两条入口若各写一份发送逻辑，迟早会走偏。
         */
        async function regenerate(m, i) {
            if (loading.value) return;
            const askIndex = i - 1;
            const ask = messages.value[askIndex];
            if (!ask || ask.role !== 'user') { alert('找不到这条回复对应的提问，无法重新生成'); return; }
            // 历史消息的附件只存了元数据、没有解析出的正文，重发无法还原 —— 说清楚再降级，不静默丢内容
            if (ask.attachments && ask.attachments.length
                && !confirm('重新生成不会重新上传当时的附件（只保留文字），继续吗？')) return;
            editingIndex.value = askIndex;
            input.value = ask.content || '';
            await send();
        }

        /** 进入「编辑重发」态：文本放回输入框；发送时会给这一轮开新版本，旧版本保留可翻回。 */
        function startEditMessage(m, i) {
            if (loading.value) return;
            editingIndex.value = i;
            input.value = m.content || '';
            nextTick(() => {
                const el = document.querySelector('.input-box textarea');
                if (el) el.focus();
            });
        }

        /** 退出「编辑重发」态（只还原输入区，不动历史）。 */
        function cancelEditMessage() {
            editingIndex.value = -1;
        }

        /**
         * 切换某一轮的生效版本（消息上「1/2 ‹ ›」）。
         * <p>切完重新拉一次历史：变的是「整轮消息长什么样」而不是某一条的字段，重拉比在本地逐条改
         * 更不容易漏（附件、引用、图表占位都在那一轮里）。切换会让长期记忆水位归零（注入模型的历史整段
         * 换了），所以记忆面板也一并刷新。
         */
        async function switchTurn(m, delta) {
            const turn = m && m.turn;
            if (!turn || loading.value) return;
            const target = turn.version + delta;
            if (target < 1 || target > turn.versionCount) return;
            try {
                const resp = await apiFetch('/api/chat/conversation/' + encodeURIComponent(currentId.value) + '/turn', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ groupId: turn.groupId, version: target })
                });
                if (!resp.ok) {
                    let msg = 'HTTP ' + resp.status;
                    try { const d = await resp.json(); if (d && d.message) msg = d.message; } catch (ignore) { /* 同上 */ }
                    throw new Error(msg);
                }
                await selectConversation(currentId.value);
            } catch (e) {
                alert('切换版本失败：' + e.message);
            }
        }

        // ===== 会话导出 =====
        /** 导出当前会话为 Markdown。后端回文本、这里拼 Blob 下载 —— 裸链接带不上 Authorization 请求头。 */
        async function exportConversation() {
            if (!currentId.value) return;
            try {
                const resp = await apiFetch('/api/chat/conversation/' + encodeURIComponent(currentId.value) + '/export');
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                const data = await resp.json();
                downloadBlob(data.content || '', data.filename || 'conversation.md', 'text/markdown');
            } catch (e) {
                alert('导出失败：' + e.message);
            }
        }

        /** 触发浏览器下载（Blob → 临时 object URL → 合成点击）。用完释放，避免 object URL 泄漏。 */
        function downloadBlob(content, filename, mime) {
            const url = URL.createObjectURL(new Blob([content], { type: mime + ';charset=utf-8' }));
            const a = document.createElement('a');
            a.href = url;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            setTimeout(() => URL.revokeObjectURL(url), 1000);
        }

        // ===== 长期记忆面板 =====
        /** 拉取当前会话的记忆快照（逐条事实 / 归档 / 摘要 / 游标 / 总条数 / 当前窗口构成 / 已排除条数）。 */
        async function openMemory() {
            if (!currentId.value) return;
            memoryModal.open = true;
            memoryModal.loading = true;
            memoryModal.error = '';
            cancelFactEdit();
            try {
                const d = await fetchMemory();
                memoryModal.summary = d.summary || '';
                memoryModal.coreFacts = d.coreFacts || '';
                memoryModal.summarizedCount = d.summarizedCount || 0;
                memoryModal.messageCount = d.messageCount || 0;
                memoryModal.excludedCount = d.excludedCount || 0;
                memoryModal.window = Array.isArray(d.window) ? d.window : [];
                memoryModal.facts = Array.isArray(d.facts) ? d.facts : [];
            } catch (e) {
                memoryModal.error = '读取失败：' + e.message;
            } finally {
                memoryModal.loading = false;
            }
        }

        /** GET …/memory 的公共取数（打开面板与条目改动后刷新共用）。 */
        async function fetchMemory() {
            const resp = await apiFetch('/api/chat/conversation/' + encodeURIComponent(currentId.value) + '/memory');
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            return resp.json();
        }

        /**
         * 只刷新条目列表，<b>不碰摘要 / 归档两个 textarea</b>。
         * <p>条目改动后重拉整份快照会把用户正在编辑的摘要文本冲掉（弹窗还开着，他可能刚敲了一半），
         * 而条目本身是独立的列表，单独换掉即可。
         */
        async function reloadFacts() {
            const d = await fetchMemory();
            memoryModal.facts = Array.isArray(d.facts) ? d.facts : [];
        }

        /** 手动新增一条事实。空内容本地就拦掉（后端也会 400，但没必要白跑一趟）。 */
        async function addFact() {
            const text = (factAdd.fact || '').trim();
            if (!currentId.value || factBusy.value) return;
            if (!text) {
                alert('请先填写事实内容');
                return;
            }
            factBusy.value = true;
            try {
                const resp = await apiFetch('/api/chat/conversation/' + encodeURIComponent(currentId.value) + '/facts', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ topic: factAdd.topic, fact: text })
                });
                const d = await resp.json().catch(() => null);
                if (!resp.ok) throw new Error((d && d.message) || ('HTTP ' + resp.status));
                factAdd.fact = '';
                await reloadFacts();
                alert('已添加（手动条目不会被自动整理删除）');
            } catch (e) {
                alert('添加失败：' + e.message);
            } finally {
                factBusy.value = false;
            }
        }

        /** 进入某条事实的行内编辑态。 */
        function startFactEdit(f) {
            factEdit.id = f.id;
            factEdit.topic = f.topic;
            factEdit.fact = f.fact;
        }

        function cancelFactEdit() {
            factEdit.id = null;
            factEdit.topic = '偏好';
            factEdit.fact = '';
        }

        /** 保存行内编辑：内容与主题都会写回；后端会把该条转为「手动」来源（人工修正过的不再被自动淘汰）。 */
        async function saveFact() {
            const id = factEdit.id;
            const text = (factEdit.fact || '').trim();
            if (id == null || factBusy.value) return;
            if (!text) {
                alert('事实内容不能为空');
                return;
            }
            factBusy.value = true;
            try {
                const resp = await apiFetch('/api/chat/conversation/' + encodeURIComponent(currentId.value)
                    + '/facts/' + encodeURIComponent(id), {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ topic: factEdit.topic, fact: text })
                });
                const d = await resp.json().catch(() => null);
                if (!resp.ok) throw new Error((d && d.message) || ('HTTP ' + resp.status));
                cancelFactEdit();
                await reloadFacts();
                alert('已保存（该条已转为「手动」，自动整理不再覆盖它）');
            } catch (e) {
                alert('保存失败：' + e.message);
            } finally {
                factBusy.value = false;
            }
        }

        /**
         * 删除一条事实。自动条目删掉后<b>不会</b>被下一次合并自动加回来（模型看不到被删的行），
         * 但若后续对话里又提到它，会重新整理出类似条目 —— 提示必须写清这一点，否则用户会以为删除没生效。
         */
        async function deleteFact(f) {
            if (!currentId.value || factBusy.value) return;
            const extra = f.source === 'USER' ? '' : '（自动条目；若后续对话再次提到，可能会重新整理出类似条目）';
            if (!confirm('删除这条长期事实？\n\n' + f.fact + extra)) return;
            factBusy.value = true;
            try {
                const resp = await apiFetch('/api/chat/conversation/' + encodeURIComponent(currentId.value)
                    + '/facts/' + encodeURIComponent(f.id), { method: 'DELETE' });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                if (factEdit.id === f.id) cancelFactEdit();
                await reloadFacts();
                alert('已删除');
            } catch (e) {
                alert('删除失败：' + e.message);
            } finally {
                factBusy.value = false;
            }
        }

        /**
         * 切换单条消息的「不参与记忆」开关。
         * <p>后端会顺带把该会话的摘要<b>游标</b>归零（可见消息构成变了），故成功且真的发生变化时
         * 明确提示一句 —— 否则这次重置是静默的，用户下次发现摘要从头重压时无从归因。
         * 消息对象就地在内存里翻转，不重拉整份历史：历史接口没有分页，重拉长会话代价不值当。
         */
        async function toggleMemoryExcluded(m, i) {
            if (!currentId.value || !m || m.memoryBusy) return;
            const next = !m.memoryExcluded;
            m.memoryBusy = true;
            try {
                const resp = await apiFetch('/api/chat/message/' + encodeURIComponent(m.messageId) + '/memory-excluded', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ excluded: next })
                });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                const d = await resp.json();
                m.memoryExcluded = !!d.excluded;
                // 副作用必须说出来：改这一位会重置该会话的摘要游标（可见消息构成变了），下次超窗时会重新整理一遍。
                // 摘要与长期记忆的「内容」保留，故不丢信息 —— 但用户有权知道这次重置发生了。
                if (d.memoryCursorReset) {
                    alert((next ? '已排除出记忆。' : '已恢复参与记忆。')
                        + '本会话的长期记忆游标已归零，下次超窗时会重新整理一遍（摘要与长期记忆内容保留）。');
                }
            } catch (e) {
                alert('修改记忆参与状态失败：' + e.message);
            } finally {
                m.memoryBusy = false;
            }
        }

        // ===== 消息反馈（👎）→ 回归用例 =====
        // 反馈的价值不在「记录」，而在「可复用」：一条躺在表里的点踩只是留档，转成断言之后才会在下次改提示词
        // 时替用户把问题再问一遍。所以这里除了提交，还接了一条「转成回归用例」的出口。
        //
        // 必须说清能自动断言什么（别高估）：只有「那一轮实际路由到哪个智能体 / 计划里包含哪几个智能体」是
        // 确定的，故只预填这些。选「答非所问 / 编造」生成的用例天然钉不住答案质量 —— 备注全程只作人工线索，
        // 不假装它能自动判答案（与评测弹窗「不判答案质量」同一立场，见 EvalService 的类注释）。
        //
        // 「转成用例」限 ADMIN：库内用例是全局资产（所有跑批共用），且只有能跑批的人才能验证它。
        // 取值与后端 MessageFeedback.REASONS 同一份口径；这里给的顺序按「用户最容易说清的」排。
        const fbReasons = [
            { value: 'ANSWERS_OFF', label: '答非所问' },
            { value: 'FABRICATED', label: '编造内容' },
            { value: 'ROUTING', label: '路由或规划不对' },
            { value: 'OTHER', label: '其他' }
        ];

        /**
         * 拉本会话全部反馈，按 messageId 合并到已加载的消息上。
         * <p>刻意单开一个请求、不并进历史接口（后端也刻意没做进 history 响应）：历史是「说了什么」，反馈是
         * 「怎么看这句话」，两者变化频率与读取时机都不同；合成的代价是每次翻历史都白拉一遍反馈，也让消息
         * DTO 多一个只有标记用途的字段。
         * <p>读不到就静默按「没反馈」处理 —— 反馈是附加信息，不该因为它把整屏历史挡住。
         */
        async function loadFeedback() {
            const id = currentId.value;
            if (!id) return;
            const seq = historySeq;   // 切会话守卫：拿到结果时若已切走则丢弃
            try {
                const resp = await apiFetch('/api/chat/conversation/' + encodeURIComponent(id) + '/feedback');
                if (!resp.ok) return;
                const list = await resp.json();
                if (seq !== historySeq) return;
                const byMsg = new Map((list || []).map(f => [f.messageId, f]));
                for (const m of messages.value) {
                    if (m.messageId != null) m.feedback = byMsg.get(m.messageId) || null;
                }
            } catch (e) { /* 反馈读不到不影响对话：按「没反馈」处理 */ }
        }

        /**
         * 展开 / 收起某条消息的反馈表单。
         * <p>有历史评价时把原因与备注回填（改票场景：一人一票，改主意是改票不是追加），没有则用默认值。
         * 默认原因取「答非所问」而不是「路由或规划不对」—— 后者生成的用例有真实断言价值，默认它等于悄悄
         * 把用户往「更有价值的那一类」上引，分类就不再是用户说的了。
         */
        function openFeedback(m) {
            if (!m || !m.messageId) return;
            m.fbError = '';
            m.fbOpen = !m.fbOpen;
            if (!m.fbOpen) return;
            m.fbReason = (m.feedback && m.feedback.reason) || 'ANSWERS_OFF';
            m.fbComment = (m.feedback && m.feedback.comment) || '';
        }

        /** 提交「这条回答有问题」（👎 + 问题分类 + 备注）。 */
        async function submitFeedback(m) {
            if (!m || m.fbSaving) return;
            await sendFeedback(m, 'DOWN');
        }

        /** 记为「有用」（改票 / 撤回点踩）。后端会顺带清掉问题分类与备注 —— 「点赞 + 编造」是自相矛盾的组合。 */
        async function markFeedbackUp(m) {
            if (!m || m.fbSaving) return;
            await sendFeedback(m, 'UP');
        }

        /** 提交反馈的公共实现。成功后消息对象原地更新，不重拉整份历史。 */
        async function sendFeedback(m, rating) {
            if (!m.messageId) return;
            m.fbSaving = true;
            m.fbError = '';
            try {
                const resp = await apiFetch('/api/chat/message/' + encodeURIComponent(m.messageId) + '/feedback', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        rating: rating,
                        reason: rating === 'UP' ? null : m.fbReason,
                        comment: m.fbComment
                    })
                });
                const body = await resp.json().catch(() => ({}));
                if (!resp.ok) throw new Error(body.message || ('HTTP ' + resp.status));
                m.feedback = body;   // 后端回的是更新后的那条（改票也是这一条）
                m.fbOpen = false;
            } catch (e) {
                m.fbError = '提交失败：' + e.message;
            } finally {
                m.fbSaving = false;
            }
        }

        /**
         * 把这条反馈转成库内回归用例（ADMIN 专属）。
         * <p>生成后把用例名念给用户听 —— 断言边界在用例上，用户得知道它到底钉住了什么，才不会以为
         * 「转成用例」等于「以后答案质量也被自动看着」。
         */
        async function promoteFeedback(m) {
            if (!m || !m.feedback || m.fbPromoting) return;
            m.fbPromoting = true;
            m.fbError = '';
            try {
                const resp = await apiFetch('/api/eval/cases/from-feedback', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ feedbackId: m.feedback.id })
                });
                const body = await resp.json().catch(() => ({}));
                // 403 说清原因：这是角色不够，不是转用例这件事本身出错。入口已按角色隐藏，
                // 走到这里多半是「同一浏览器换了小号登录」或角色被摘掉后的残留状态。
                if (resp.status === 403) throw new Error('仅管理员可转成回归用例（当前账号无 ADMIN 角色）');
                if (!resp.ok) throw new Error(body.message || ('HTTP ' + resp.status));
                m.feedback.evalCaseId = body.id;
                alert('已生成回归用例：' + body.name + '\n\n'
                    + '它钉住的是这一轮「可确定的部分」：走哪个智能体 / 计划里包含哪几个智能体。'
                    + '答案质量本身不会被自动断言 —— 你写的备注仍只作人工排查线索。\n'
                    + '可在顶部「🧪 提示词回归评测」弹窗里跑批验证。');
            } catch (e) {
                m.fbError = '转用例失败：' + e.message;
            } finally {
                m.fbPromoting = false;
            }
        }

        /** 保存记忆内容（只覆盖两列内容；游标由系统维护，不在请求范围内）。事实条目走各自的端点。 */
        async function saveMemory() {
            if (!currentId.value || memoryModal.saving) return;
            memoryModal.saving = true;
            try {
                const resp = await apiFetch('/api/chat/conversation/' + encodeURIComponent(currentId.value) + '/memory', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ summary: memoryModal.summary, coreFacts: memoryModal.coreFacts })
                });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                memoryModal.open = false;
            } catch (e) {
                alert('保存记忆失败：' + e.message);
            } finally {
                memoryModal.saving = false;
            }
        }

        /**
         * 重置记忆：摘要 / 旧版归档 / 游标归零，<b>并作废自动整理出的条目</b>，消息保留（下次超窗会从头重新摘要）。
         * <p>确认文案必须把「自动条目会被清除」写出来：这是本次操作里唯一会让用户看到东西消失的部分，
         * 不说清楚就变成了一次静默删除。手动条目不在范围内，也写明白 —— 否则用户不敢点。
         */
        async function resetMemory() {
            if (!currentId.value || memoryModal.saving) return;
            if (!confirm('重置会清空摘要与旧版归档、清除自动整理出的长期事实条目（手动添加的条目保留），'
                + '并让下次记忆压缩从头开始。历史消息不会被删除。继续吗？')) return;
            memoryModal.saving = true;
            try {
                const resp = await apiFetch('/api/chat/conversation/' + encodeURIComponent(currentId.value) + '/memory', {
                    method: 'DELETE'
                });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                memoryModal.summary = '';
                memoryModal.coreFacts = '';
                memoryModal.summarizedCount = 0;
                await reloadFacts();
            } catch (e) {
                alert('重置记忆失败：' + e.message);
            } finally {
                memoryModal.saving = false;
            }
        }

        /** 记忆覆盖度文案：直接回答「它为什么还记着那么早的事」。 */
        function memoryCoverage() {
            const total = memoryModal.messageCount || 0;
            const done = memoryModal.summarizedCount || 0;
            if (total === 0) return '本会话还没有消息';
            return '已压缩 ' + done + ' / ' + total + ' 条消息'
                + (done >= total ? '（全部已进摘要）' : '（其余落在近期窗口内，尚未压缩）');
        }

        // ===== 智能体 =====
        async function loadAgents() {
            try {
                const resp = await apiFetch('/api/agent');
                if (resp.ok) agents.value = await resp.json();
            } catch (e) { /* 忽略 */ }
        }

        // ===== 智能体导入 / 导出（把智能体当资产搬进搬出）=====
        /** 导出全部智能体为 JSON 文件。包内不含专属知识库内容（那属于知识库模块，需另行重建）。 */
        async function exportAgents() {
            try {
                const resp = await apiFetch('/api/agent/export');
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                const list = await resp.json();
                if (!list || !list.length) { alert('当前没有可导出的智能体'); return; }
                const stamp = new Date().toISOString().slice(0, 10).replace(/-/g, '');
                downloadBlob(JSON.stringify(list, null, 2), 'agents-' + stamp + '.json', 'application/json');
            } catch (e) {
                alert('导出失败：' + e.message);
            }
        }

        /** 点击「导入」→ 触发隐藏的文件选择器（与附件选择器同一套做法）。 */
        function triggerAgentImport() {
            if (agentImportInput.value) agentImportInput.value.click();
        }

        /**
         * 读取并导入智能体文件。接受导出格式的数组，或含 agents 数组的对象。
         * 冲突策略用一次 confirm 二选一：「覆盖」与「跳过」都是有意义的行为，不需要第三种态。
         */
        async function onAgentImportFile(event) {
            const file = event.target.files && event.target.files[0];
            event.target.value = '';   // 清空 value，否则连续选同一个文件不会再触发 change
            if (!file) return;
            let items;
            try {
                const parsed = JSON.parse(await file.text());
                items = Array.isArray(parsed) ? parsed : (parsed && Array.isArray(parsed.agents) ? parsed.agents : null);
                if (!items) throw new Error('JSON 顶层应为数组，或含 agents 数组的对象');
            } catch (e) {
                alert('解析文件失败：' + e.message);
                return;
            }
            if (!items.length) { alert('文件里没有智能体'); return; }
            const overwrite = confirm(
                '即将导入 ' + items.length + ' 个智能体。\n\n'
                + '【确定】覆盖同编码的智能体（保留其 id 与创建时间）\n'
                + '【取消】跳过同编码的，只新增不存在的'
            );
            try {
                const resp = await apiFetch('/api/agent/import', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ agents: items, onConflict: overwrite ? 'overwrite' : 'skip' })
                });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                const r = await resp.json();
                let msg = '导入完成：新增 ' + r.created + '，更新 ' + r.updated + '，跳过 ' + r.skipped;
                if (r.errors && r.errors.length) {
                    msg += '\n\n失败 ' + r.errors.length + ' 条：\n' + r.errors.slice(0, 5).join('\n');
                    if (r.errors.length > 5) msg += '\n…';
                }
                alert(msg);
                await loadAgents();
            } catch (e) {
                alert('导入失败：' + e.message);
            }
        }

        // ===== 智能体管理：搜索 / 统计 / 卡片展示辅助 =====
        const agentQuery = ref('');

        /** 按关键字过滤（名称 / 编码 / 描述），纯前端过滤，不请求后端。 */
        const shownAgents = computed(() => {
            const q = agentQuery.value.trim().toLowerCase();
            if (!q) return agents.value;
            return agents.value.filter(a =>
                ((a.name || '') + ' ' + (a.agentCode || '') + ' ' + (a.description || '')).toLowerCase().includes(q));
        });

        /** 一览统计：总数 / 已配参数补全 / 已限定工具 / 已挂专属知识库。 */
        const agentStats = computed(() => {
            const list = agents.value;
            return {
                total: list.length,
                withParams: list.filter(a => parseParamSchema(a.paramSchema).length > 0).length,
                withTools: list.filter(a => parseTools(a.toolsJson).mode !== 'all').length,
                withKb: list.filter(a => agentHasKb(a)).length
            };
        });

        /** 卡片图标配色：优先智能体主题色，缺省主色（与知识库专属库图标同一口径）。 */
        function agentIconStyle(a) {
            const c = (a && a.avatarColor) || '#3b82f6';
            return { background: c + '1f', color: c };
        }

        /** 参数补全项数量（0 = 未配置） */
        function agentParamCount(a) {
            return parseParamSchema(a && a.paramSchema).length;
        }

        /** 工具装配摘要；返回空串表示「不限制（挂全部工具）」，卡片上不占标签位。 */
        function agentToolLabel(a) {
            const t = parseTools(a && a.toolsJson);
            if (t.mode === 'none') return '🧰 不用工具';
            if (t.mode === 'custom') return '🧰 工具 ' + t.names.length + ' 个';
            return '';
        }

        /** 是否已绑定专属知识库（知识库列表在 onMounted 已加载） */
        function agentHasKb(a) {
            return !!(a && kbs.value.some(k => k.agentId === a.id));
        }

        /** 拉取可用工具清单（工具集在应用启动时固定，拉一次即可）。失败静默，不影响智能体管理主流程。 */
        async function loadTools() {
            try {
                const resp = await apiFetch('/api/agent/tools');
                if (resp.ok) availableTools.value = await resp.json();
            } catch (e) { /* 忽略 */ }
        }

        /** 工具清单按 group（所属 ToolProvider 类名）分组，用于弹窗里分块展示与勾选。
         *  带搜索词时只保留「工具名或描述」命中的项（过滤仅作用展示层，已勾选结果不受影响）。 */
        const toolGroups = computed(() => {
            const q = (agentModal.toolSearch || '').trim().toLowerCase();
            const map = new Map();
            for (const t of availableTools.value) {
                if (q && !((t.name || '') + ' ' + (t.description || '')).toLowerCase().includes(q)) continue;
                const g = t.group || '其他';
                if (!map.has(g)) map.set(g, []);
                map.get(g).push(t);
            }
            return Array.from(map, ([group, tools]) => ({ group, tools }));
        });

        /** 工具分组显示名（后端 group 为 ToolProvider 类名，这里转为中文便于阅读）。 */
        function toolGroupLabel(group) {
            const s = String(group || '');
            if (s.includes('Weather')) return '天气';
            if (s.includes('Date')) return '日期';
            if (s.includes('SqlQuery')) return '数据查询';
            if (s.includes('SqlSchema')) return '数据表结构';
            if (s.includes('Chart')) return '图表';
            if (s.includes('SubAgent')) return '智能体协作';
            return s || '其他';
        }

        /** 全选：勾上「当前列表可见」的工具（有搜索词时只选命中的，无搜索词即全部）。
         *  与已有勾选合并去重，不会取消先前搜别的词时勾上的工具。 */
        function toolSelectAll() {
            const set = new Set(agentModal.toolNames);
            for (const g of toolGroups.value) {
                for (const t of g.tools) set.add(t.name);
            }
            agentModal.toolNames = Array.from(set);
        }

        /** 清空：取消全部勾选（不受当前搜索词影响）。 */
        function toolClearAll() {
            agentModal.toolNames = [];
        }

        function openCreateAgent() {
            Object.assign(agentModal, {
                open: true, id: null,
                name: '', agentCode: '', icon: '', description: '', systemPrompt: '',
                model: '', temperature: null, avatarColor: '',
                paramList: [],
                toolMode: 'all', toolNames: [], toolSearch: '',
                saving: false, genLoading: false
            });
        }

        /** 按后端规则由名称生成智能体编码（小写、非字母数字转连字符、截 40 位、空名兜底 agent）。
         *  仅作前端预填，唯一冲突由后端在保存时校验（409）。 */
        function autoGenCode(name) {
            const base = (name || '').trim().toLowerCase()
                .replace(/[^a-z0-9]+/g, '-')
                .replace(/^-+|-+$/g, '');
            return (base || 'agent').slice(0, 40);
        }

        // 名称失焦后自动填充编码（仅创建模式且编码为空时，用户可继续修改）
        function autoFillCode() {
            const m = agentModal;
            if (m.id || (m.agentCode || '').trim()) return;
            m.agentCode = autoGenCode(m.name);
        }

        function openEditAgent(a) {
            const t = parseTools(a.toolsJson);
            Object.assign(agentModal, {
                open: true, id: a.id,
                name: a.name || '',
                agentCode: a.agentCode || '',
                icon: a.icon || '',
                description: a.description || '',
                systemPrompt: a.systemPrompt || '',
                model: a.model || '',
                temperature: a.temperature ?? null,
                avatarColor: a.avatarColor || '',
                paramList: parseParamSchema(a.paramSchema),
                toolMode: t.mode, toolNames: t.names, toolSearch: '',
                saving: false, genLoading: false
            });
            agentView.open = false; // 从查看弹窗进入编辑时关闭查看弹窗
        }

        /**
         * 把后端 toolsJson（字符串）解析为前端三态编辑模型：
         * null/空 → { mode:'all' }（不限制，挂全部工具）；"[]" → { mode:'none' }（不使用工具）；
         * ["a","b"] → { mode:'custom', names:['a','b'] }。解析失败按 all 处理（与后端回退策略一致）。
         */
        function parseTools(str) {
            if (str == null || String(str).trim() === '') return { mode: 'all', names: [] };
            try {
                const arr = JSON.parse(str);
                if (!Array.isArray(arr) || arr.length === 0) return { mode: 'none', names: [] };
                return { mode: 'custom', names: arr.map(String) };
            } catch (e) {
                return { mode: 'all', names: [] };
            }
        }

        /**
         * 把三态编辑模型序列化为后端 toolsJson：
         * all → null（不限制，等价于存量智能体的默认行为）；none → "[]"；custom → JSON 数组字符串。
         */
        function buildToolsJson() {
            const mode = agentModal.toolMode;
            if (mode === 'none') return '[]';
            if (mode === 'custom') {
                const names = (agentModal.toolNames || []).filter(Boolean);
                return JSON.stringify(names);
            }
            return null;   // all：不限制，挂全部工具
        }

        /**
         * 把后端返回的 paramSchema（JSON 字符串）解析为前端可编辑的结构化列表。
         * 每项：{ key, label, required, hint, optionsText }；optionsText 为可选项用「、」连接的文本。
         */
        function parseParamSchema(str) {
            if (!str) return [];
            try {
                const arr = JSON.parse(str);
                if (!Array.isArray(arr)) return [];
                return arr.map(p => ({
                    key: p.key || '',
                    label: p.label || '',
                    required: p.required !== false,
                    hint: p.hint || '',
                    optionsText: Array.isArray(p.options) ? p.options.join('、') : ''
                }));
            } catch (e) {
                return [];
            }
        }

        /**
         * 把结构化 paramList 序列化为后端 paramSchema（JSON 字符串）。
         * 过滤掉没填 key 的空行；无有效参数时返回空串（等价「无参数」，且可在编辑时清空已有配置）。
         */
        function buildParamSchema() {
            const list = (agentModal.paramList || [])
                .map(p => ({
                    key: (p.key || '').trim(),
                    label: (p.label || '').trim(),
                    required: !!p.required,
                    hint: (p.hint || '').trim(),
                    options: (p.optionsText || '').split(/[、,，]/).map(s => s.trim()).filter(Boolean)
                }))
                .filter(p => p.key);
            return list.length === 0 ? '' : JSON.stringify(list);
        }

        // 新增一个空白参数行
        function addParam() {
            agentModal.paramList.push({ key: '', label: '', required: true, hint: '', optionsText: '' });
        }

        // 删除指定索引的参数行
        function removeParam(idx) {
            agentModal.paramList.splice(idx, 1);
        }

        function closeAgentModal() {
            agentModal.open = false;
        }

        // 打开只读详情弹窗（查看智能体）
        function viewAgent(a) {
            if (!a) return;
            agentView.agent = a;
            agentView.open = true;
        }

        // 调用后端 AI 生成系统提示词，填入文本框
        async function genPrompt() {
            const m = agentModal;
            if (!m.name.trim()) {
                alert('请先填写智能体名称');
                return;
            }
            m.genLoading = true;
            try {
                const resp = await apiFetch('/api/agent/generate-prompt', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ name: m.name, description: m.description })
                });
                if (!resp.ok) {
                    let msg = 'HTTP ' + resp.status;
                    try {
                        const e = await resp.json();
                        if (e && e.message) msg = e.message;
                    } catch (_) { /* 忽略解析失败 */ }
                    throw new Error(msg);
                }
                const data = await resp.json();
                m.systemPrompt = (data && data.prompt) || '';
            } catch (e) {
                alert('提示词生成失败：' + e.message);
            } finally {
                m.genLoading = false;
            }
        }

        async function saveAgent() {
            const m = agentModal;
            if (!m.name.trim() || !m.agentCode.trim() || !m.description.trim() || !m.systemPrompt.trim()) return;
            if (!(m.agentCode || '').trim()) m.agentCode = autoGenCode(m.name); // 编码不允许为空：空则自动生成
            m.saving = true;
            const payload = {
                name: m.name,
                agentCode: m.agentCode || null,
                icon: m.icon || null,
                description: m.description,
                systemPrompt: m.systemPrompt,
                model: m.model || null,
                temperature: (m.temperature === '' || m.temperature == null) ? null : Number(m.temperature),
                avatarColor: m.avatarColor || null,
                paramSchema: buildParamSchema(),
                toolsJson: buildToolsJson()
            };
            const url = m.id ? ('/api/agent/' + encodeURIComponent(m.id)) : '/api/agent';
            const method = m.id ? 'PUT' : 'POST';
            try {
                const resp = await apiFetch(url, {
                    method,
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                await loadAgents();
                closeAgentModal();
                agentView.open = false;
            } catch (e) {
                alert('保存失败：' + e.message);
            } finally {
                m.saving = false;
            }
        }

        async function deleteAgent(id) {
            if (!id) return;
            if (!confirm('确定删除该智能体吗？已绑定它的会话将退回为默认助手，历史消息保留。')) return;
            try {
                const resp = await apiFetch('/api/agent/' + encodeURIComponent(id), { method: 'DELETE' });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                agents.value = agents.value.filter(a => a.id !== id);
                if (agentView.open && agentView.agent && agentView.agent.id === id) {
                    agentView.open = false; // 删除的正是查看中的智能体
                }
                loadConversations(); // 刷新会话列表，清除被解除绑定的会话徽标
            } catch (e) {
                alert('删除失败：' + e.message);
            }
        }

        // ===== 知识库（RAG） =====
        async function loadKbs() {
            try {
                const resp = await apiFetch('/api/kb');
                if (resp.ok) kbs.value = await resp.json();
            } catch (e) { /* 忽略 */ }
        }

        // 知识库卡片图标配色：全局库绿色系，专属库用智能体主题色
        function kbIconStyle(kb) {
            const c = kb.agentId ? ((agents.value.find(x => x.id === kb.agentId) || {}).avatarColor || '#3b82f6') : '#10b981';
            return { background: c + '1f', color: c };
        }

        // 知识块来源 -> 左侧色条：同一来源文件同色，长列表里便于一眼分辨块的归属
        const KB_SOURCE_COLORS = ['#639922', '#378add', '#ba7517', '#d4537e', '#1d9e75', '#6b7f9e'];
        function chunkBarStyle(c) {
            const s = c && c.source;
            if (!s) return {};
            let h = 0;
            for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
            return { borderLeftColor: KB_SOURCE_COLORS[h % KB_SOURCE_COLORS.length] };
        }

        // 后端 LocalDateTime 字符串（如 2026-09-02T13:32:25）转可读格式
        function fmtTime(s) {
            if (!s) return '';
            if (Array.isArray(s)) s = s.join('-');
            return String(s).replace('T', ' ').slice(0, 16);
        }

        // 字节数转可读大小（B / KB / MB）
        function fmtSize(n) {
            const v = Number(n) || 0;
            if (v < 1024) return v + ' B';
            if (v < 1024 * 1024) return (v / 1024).toFixed(1).replace(/\.0$/, '') + ' KB';
            return (v / 1024 / 1024).toFixed(2).replace(/\.?0+$/, '') + ' MB';
        }

        // 按文件类型给出图标（用于文件列表展示）
        function typeIcon(type) {
            const t = String(type || '').toLowerCase();
            if (t === 'pdf') return '📕';
            if (t === 'docx' || t === 'doc') return '📘';
            if (t === 'xlsx' || t === 'xls' || t === 'csv') return '📗';
            if (t === 'md' || t === 'markdown' || t === 'txt') return '📄';
            return '🗂️';
        }

        // 分片策略：key → 展示名（未知 key 原样返回，兼容历史数据缺省 recursive 前的文件）
        function strategyLabel(key) {
            const hit = (kbDetail.strategies || []).find(s => s.key === key);
            return hit ? hit.label : (key || 'recursive');
        }

        // 分片策略：key → 说明文案。显示在弹窗下拉的下方，而不是塞进 option 文本 ——
        // 原生 select 的 option 不换行，长说明挤在一起只会被硬裁。
        function strategyDesc(key) {
            const hit = (kbDetail.strategies || []).find(s => s.key === key);
            return hit ? hit.desc : '';
        }

        // 重叠候选值（0 = 不重叠；受后端 maxOverlap=200 约束，列表内均合法）
        const overlapOptions = [0, 40, 60, 100, 150, 200];

        // 重叠值 → 下拉选项文本
        function overlapText(ov) {
            const n = Number(ov);
            return n === 0 ? '不重叠' : n + ' 字';
        }

        // 重叠值 → 文件行标签（null/空 = 老数据，视为「默认」）
        function overlapLabel(ov) {
            if (ov == null || ov === '') return '默认';
            const n = Number(ov);
            return n === 0 ? '不重叠' : n + ' 字';
        }

        // 拉取分片策略元数据与重叠默认值（key/label/desc + defaultOverlap），供设置弹窗与展示使用
        async function loadChunkStrategies() {
            try {
                const resp = await apiFetch('/api/kb/chunk-strategies');
                if (resp.ok) {
                    const data = await resp.json();
                    kbDetail.strategies = (data && data.strategies) || [];
                    if (data && data.defaultOverlap != null) {
                        kbDetail.defaultOverlap = data.defaultOverlap;
                        kbDetail.uploadOverlap = data.defaultOverlap;
                    }
                    if (kbDetail.strategies.length && !kbDetail.strategies.some(s => s.key === kbDetail.uploadStrategy)) {
                        kbDetail.uploadStrategy = kbDetail.strategies[0].key;
                    }
                }
            } catch (e) { /* 忽略：保持默认策略 */ }
        }

        // 确保策略元数据已加载（打开设置弹窗 / 库详情时调用一次；接口失败则保持默认）
        async function ensureChunkStrategies() {
            if (!kbDetail.strategies.length) await loadChunkStrategies();
        }

        // 用当前库（kbDetail.kb）的设置刷新「上传默认分片 / 默认重叠」展示值（kb 来自列表，含 chunkStrategy/chunkOverlap）
        function syncKbDefaults() {
            const kb = kbDetail.kb;
            if (!kb) return;
            if (kb.chunkStrategy && kbDetail.strategies.some(s => s.key === kb.chunkStrategy)) {
                kbDetail.uploadStrategy = kb.chunkStrategy;
            }
            if (kb.chunkOverlap != null) kbDetail.uploadOverlap = Number(kb.chunkOverlap);
        }

        // 打开「新建知识库」弹窗：预选第一个暂无库的智能体，名称自动填充（分片配置取后端默认）
        async function openCreateKb() {
            await ensureChunkStrategies();
            const first = availableAgents.value[0];
            Object.assign(kbModal, { open: true, mode: 'create', id: null, agentId: first ? first.id : null, name: '', description: '',
                chunkStrategy: 'recursive', chunkOverlap: kbDetail.defaultOverlap, saving: false });
            autoKbName();
        }

        async function openRenameKb(kb) {
            await ensureChunkStrategies();
            Object.assign(kbModal, { open: true, mode: 'rename', id: kb.id, agentId: kb.agentId,
                name: kb.name, description: kb.description || '',
                chunkStrategy: kb.chunkStrategy || 'recursive',
                chunkOverlap: kb.chunkOverlap != null ? Number(kb.chunkOverlap) : kbDetail.defaultOverlap,
                saving: false });
        }

        // 切换归属智能体后自动带出默认库名
        function autoKbName() {
            const a = agents.value.find(x => x.id === kbModal.agentId);
            if (a && (!kbModal.name || kbModal.name === kbModal._lastName)) {
                kbModal.name = a.name + ' 的知识库';
                kbModal._lastName = kbModal.name;
            }
        }

        async function saveKb() {
            if (kbModal.mode === 'create' && !kbModal.agentId) { alert('请选择归属智能体'); return; }
            kbModal.saving = true;
            const url = kbModal.mode === 'create' ? '/api/kb' : '/api/kb/' + kbModal.id;
            const method = kbModal.mode === 'create' ? 'POST' : 'PUT';
            try {
                const resp = await apiFetch(url, {
                    method,
                    headers: { 'Content-Type': 'application/json' },
                    // rename 时 agentId 恒为 null：归属不可变更（全局/专属由创建决定）
                    body: JSON.stringify({
                        name: kbModal.name,
                        description: kbModal.description || null,
                        agentId: kbModal.mode === 'create' ? kbModal.agentId : null,
                        chunkStrategy: kbModal.chunkStrategy || 'recursive',
                        chunkOverlap: kbModal.chunkOverlap != null ? kbModal.chunkOverlap : kbDetail.defaultOverlap
                    })
                });
                if (!resp.ok) {
                    let msg = 'HTTP ' + resp.status;
                    try { const e = await resp.json(); if (e && e.message) msg = e.message; } catch (_) { /* 忽略 */ }
                    throw new Error(msg);
                }
                const saved = await resp.json();
                kbModal.open = false;
                await loadKbs();
                // 详情视图打开时同步库对象（改名/默认分片配置可能正属当前详情库），并刷新上传默认展示
                if (kbDetail.open && kbDetail.kb && kbDetail.kb.id === saved.id) {
                    kbDetail.kb = saved;
                    syncKbDefaults();
                }
            } catch (e) {
                alert('保存失败：' + e.message);
            } finally {
                kbModal.saving = false;
            }
        }

        async function deleteKb(id) {
            const kb = kbs.value.find(x => x.id === id);
            if (!kb) return;
            const tip = kb.agentId
                ? '确定删除知识库「' + kb.name + '」及其全部 ' + (kb.docCount || 0) + ' 个知识块吗？\n删除后该智能体对话将不再检索这些资料，且无法恢复。'
                : '确定删除「通用知识库」及其全部 ' + (kb.docCount || 0) + ' 个知识块吗？\n删除后所有对话将不再检索全局资料；下次进入会重建一个空库。';
            if (!confirm(tip)) return;
            try {
                const resp = await apiFetch('/api/kb/' + encodeURIComponent(id), { method: 'DELETE' });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                kbs.value = kbs.value.filter(x => x.id !== id);
                if (kbDetail.open && kbDetail.kb && kbDetail.kb.id === id) kbDetail.open = false;
            } catch (e) {
                alert('删除失败：' + e.message);
            }
        }

        // 打开库详情（文件列表与知识块列表态清零后加载）
        async function openKbDetail(kb) {
            kbDetail.kb = kb;
            kbDetail.chunks = [];
            kbDetail.total = 0;
            kbDetail.files = [];
            kbDetail.fileList = [];
            kbDetail.uploading = false;
            kbDetail.msg = '';
            kbDetail.rechunk = null;
            chunkFilter.q = '';        // 每次进库重置筛选，避免带着上一个库的条件看新库
            chunkFilter.source = '';
            kbDetail.open = true;
            await loadChunkStrategies();
            syncKbDefaults();      // 上传按该库默认分片策略 / 重叠执行（知识库设置）
            await loadFiles();
            await loadChunks(false);
            await loadChromaStatus();   // 顶部展示向量副本（Chroma）连接状态与向量条数
        }

        // Chroma 向量副本状态：确认上传的知识块是否真的同步进了向量库（MySQL 是源，Chroma 是加速副本）
        async function loadChromaStatus() {
            try {
                const resp = await apiFetch('/api/kb/chroma/status');
                if (resp.ok) Object.assign(chroma.value, await resp.json());
            } catch (e) { /* 忽略：状态条保持上一次结果 */ }
        }

        // 把本库（MySQL 存量）知识块幂等回填到 Chroma：副本缺失 / 故障恢复后用
        async function syncChroma() {
            if (!kbDetail.kb || chroma.value.syncing) return;
            chroma.value.syncing = true;
            chroma.value.msg = '';
            try {
                const resp = await apiFetch('/api/kb/chroma/sync', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ kbId: kbDetail.kb.id })
                });
                let data = null;
                try { data = await resp.json(); } catch (_) { /* 非 JSON 响应 */ }
                if (!resp.ok) {
                    const msg = data && data.message ? data.message : ('HTTP ' + resp.status);
                    throw new Error(msg);
                }
                chroma.value.msg = '已回填 ' + ((data && data.synced) || 0) + ' 个知识块到向量副本';
                await loadChromaStatus();
            } catch (e) {
                chroma.value.msg = '同步失败：' + e.message;
            } finally {
                chroma.value.syncing = false;
            }
        }

        // 加载该库已登记的文件列表
        async function loadFiles() {
            if (!kbDetail.kb) return;
            try {
                const resp = await apiFetch('/api/kb/' + kbDetail.kb.id + '/files');
                if (resp.ok) kbDetail.fileList = (await resp.json()) || [];
            } catch (e) { /* 忽略 */ }
        }

        // 删除库内文件（连带删除其全部知识块）
        async function deleteKbFile(f) {
            if (!f || !f.id) return;
            const tip = '确定删除文件「' + f.fileName + '」及其 ' + (f.chunkCount || 0)
                    + ' 个知识块吗？\n删除后对话将不再检索到该文件的内容，且无法恢复。';
            if (!confirm(tip)) return;
            try {
                const resp = await apiFetch('/api/kb/' + kbDetail.kb.id + '/files/' + f.id, { method: 'DELETE' });
                let data = null;
                try { data = await resp.json(); } catch (_) { /* 非 JSON 响应 */ }
                if (!resp.ok) {
                    const msg = data && data.message ? data.message : ('HTTP ' + resp.status);
                    throw new Error(msg);
                }
                kbDetail.fileList = kbDetail.fileList.filter(x => x.id !== f.id);
                const removed = (data && data.removed) || 0;
                if (removed > 0) {
                    kbDetail.total = Math.max(0, kbDetail.total - removed);
                    await loadChunks(false);   // 分页数据可能已被删空，刷新第一页
                }
                if (kbDetail.kb && kbDetail.kb.docCount) {
                    kbDetail.kb.docCount = Math.max(0, (kbDetail.kb.docCount || 0) - removed);
                }
                await loadKbs();               // 同步卡片上的知识块计数
            } catch (e) {
                alert('删除失败：' + e.message);
            }
        }

        // 打开某文件的「重新分片」交互（在文件行下展开策略 + 重叠选择条）
        function openRechunk(f) {
            kbDetail.rechunk = {
                file: f,
                strategy: f.chunkStrategy || kbDetail.uploadStrategy,
                overlap: f.chunkOverlap != null ? f.chunkOverlap : kbDetail.defaultOverlap,
                busy: false
            };
        }

        // 确认重新分片：按所选新策略 + 重叠重切该文件（读后端保存的原文，无需重传）
        async function doRechunk() {
            const r = kbDetail.rechunk;
            if (!r || !r.file || r.busy) return;
            const curOv = r.file.chunkOverlap != null ? r.file.chunkOverlap : kbDetail.defaultOverlap;
            const newOv = Number(r.overlap);
            if (r.strategy === (r.file.chunkStrategy || 'recursive') && newOv === Number(curOv)) {
                alert('该文件当前已是「' + strategyLabel(r.strategy) + '」分片、重叠 ' + overlapText(curOv) + '，无需重复操作');
                return;
            }
            r.busy = true;
            try {
                const resp = await apiFetch('/api/kb/' + kbDetail.kb.id + '/files/' + r.file.id + '/rechunk', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ strategy: r.strategy, overlap: newOv })
                });
                let data = null;
                try { data = await resp.json(); } catch (_) { /* 非 JSON 响应 */ }
                if (!resp.ok) {
                    const msg = data && data.message ? data.message : ('HTTP ' + resp.status);
                    throw new Error(msg);
                }
                kbDetail.msgOk = true;
                kbDetail.msg = '文件「' + r.file.fileName + '」已按「' + strategyLabel(r.strategy) + '」分片、重叠 '
                        + overlapText(data && data.chunkOverlap != null ? data.chunkOverlap : newOv)
                        + ' 重新切块，现有 ' + (data && data.chunkCount != null ? data.chunkCount : '') + ' 个知识块';
                kbDetail.rechunk = null;
                await loadFiles();       // 刷新策略标签与块数
                await loadChunks(false); // 刷新右侧知识块列表
                await loadKbs();         // 同步卡片计数
            } catch (e) {
                kbDetail.msgOk = false;
                kbDetail.msg = '重新分片失败：' + e.message;
                kbDetail.rechunk = null;
            }
        }

        // 加载知识块：append=false 覆盖当前页（从第 0 块），append=true 追加（加载更多）
        async function loadChunks(append) {
            if (!kbDetail.kb) return;
            try {
                const offset = append ? kbDetail.chunks.length : 0;
                const resp = await apiFetch('/api/kb/' + kbDetail.kb.id + '/chunks?offset=' + offset + '&limit=20');
                if (resp.ok) {
                    const d = await resp.json();
                    kbDetail.total = d.total || 0;
                    kbDetail.chunks = append ? kbDetail.chunks.concat(d.list || []) : (d.list || []);
                }
            } catch (e) { /* 忽略 */ }
        }

        function loadMoreChunks() {
            loadChunks(true);
        }

        // ===== 文件上传导入知识 =====

        // 点击上传区 → 触发隐藏的 file input
        function pickFiles() {
            if (!kbDetail.uploading && kbFileInput.value) kbFileInput.value.click();
        }

        // 拖拽释放：接收 dataTransfer.files
        function onDropFiles(e) {
            kbDetail.drag = false;
            if (e && e.dataTransfer && e.dataTransfer.files) addFiles(e.dataTransfer.files);
        }

        // 文件选择框 change：合并到待上传列表（同名去重，避免重复导入）
        function onFilesChosen(e) {
            if (e && e.target && e.target.files) addFiles(e.target.files);
            if (e && e.target) e.target.value = '';   // 允许再次选择同一文件
        }

        function addFiles(fileList) {
            if (kbDetail.uploading) return;
            for (const f of Array.from(fileList || [])) {
                if (!kbDetail.files.some(x => x.name === f.name && x.size === f.size)) {
                    kbDetail.files.push(f);
                }
            }
            kbDetail.msg = '';
        }

        function removeFile(idx) {
            kbDetail.files.splice(idx, 1);
        }

        // 上传全部已选文件：不指定分片策略与重叠，后端按该库「知识库设置」的默认配置切块入库
        async function uploadFiles() {
            if (kbDetail.uploading || kbDetail.files.length === 0 || !kbDetail.kb) return;
            kbDetail.uploading = true;
            kbDetail.msg = '';
            try {
                const fd = new FormData();
                for (const f of kbDetail.files) fd.append('files', f);
                const resp = await apiFetch('/api/kb/' + kbDetail.kb.id + '/upload', { method: 'POST', body: fd });
                let data = null;
                try { data = await resp.json(); } catch (_) { /* 非 JSON 响应 */ }
                if (!resp.ok) {
                    const msg = data && data.message ? data.message : ('HTTP ' + resp.status);
                    throw new Error(msg);
                }
                const list = (data && data.results) || [];
                const okList = list.filter(r => r.status === 'ok');
                const errList = list.filter(r => r.status === 'error');
                let totalAdded = 0;
                for (const r of okList) totalAdded += r.added || 0;
                let text = '';
                if (okList.length) text += '成功导入 ' + okList.length + ' 个文件，新增 ' + totalAdded + ' 个知识块';
                if (errList.length) {
                    text += (text ? '；' : '') + errList.length + ' 个文件失败：'
                            + errList.map(r => r.fileName + (r.message ? '（' + r.message + '）' : '')).join('；');
                }
                // 不再依据响应里的 chromaSynced 提示用户：向量副本写入已延后到事务提交之后，
                // 接口返回时结果尚未产生。副本实况由下方 loadChromaStatus() 拉取（顶部状态条显示
                // 实际向量条数，可据此点「同步」回填）。
                kbDetail.msgOk = errList.length === 0;
                kbDetail.msg = text || '没有处理任何文件';
                kbDetail.files = [];
                await loadFiles();     // 刷新库内文件列表（登记的文件与块数）
                await loadChunks(false);
                await loadKbs();   // 同步卡片上的知识块计数
                await loadChromaStatus();   // 刷新向量副本状态与向量条数
                if (kbDetail.kb) {
                    // 成功后刷新库计数（后端返回 updated 库信息最准，这里按结果累加并随后续 loadKbs 校正）
                    const old = kbDetail.kb.docCount || 0;
                    const fresh = kbs.value.find(k => k.id === kbDetail.kb.id);
                    if (fresh) kbDetail.kb.docCount = fresh.docCount;
                    else kbDetail.kb.docCount = old + totalAdded;
                }
            } catch (e) {
                kbDetail.msgOk = false;
                kbDetail.msg = '上传失败：' + e.message;
            } finally {
                kbDetail.uploading = false;
            }
        }

        async function deleteChunk(cid) {
            if (!confirm('删除该知识块？删除后对话将不再检索到它。')) return;
            try {
                const resp = await apiFetch('/api/kb/' + kbDetail.kb.id + '/chunks/' + cid, { method: 'DELETE' });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                kbDetail.chunks = kbDetail.chunks.filter(c => c.id !== cid);
                kbDetail.total = Math.max(0, kbDetail.total - 1);
                if (kbDetail.kb && kbDetail.kb.docCount) kbDetail.kb.docCount = Math.max(0, kbDetail.kb.docCount - 1);
                await loadKbs();
                await loadFiles();   // 同步所属文件的分块计数
            } catch (e) {
                alert('删除失败：' + e.message);
            }
        }

        // ===== 链路追踪（可观测） =====
        // 每轮对话在回复产出后由后端异步落库到 agent_trace，这里只读展示：
        // 回答「这轮为什么路由到它」「规划器排了哪几步」「RAG 有没有命中」「调了哪些工具、花了多少 token、耗时多久」。
        // items：追踪列表（按时间倒序）；expanded：按索引记录哪几条展开了明细。
        // 可见性由后端按「会话归属」判定：普通用户只看得到自己名下会话的记录，ADMIN 走全量视角（可切到「全部会话」）。
        // isAdmin 必须在打开时从 Auth 取一次存进 reactive —— Auth.getUser() 不是响应式的，
        // 模板里直接调函数读它，登录态后到也永远不会触发重渲染，切换入口就不出来了。
        const traceModal = reactive({
            open: false, loading: false, items: [], expanded: {}, error: '',
            isAdmin: false, allConversations: false,
            page: 1,
        });
        /** 每页条数：列表固定分页，行高不随数据量变化（此前一次性渲染，行被 flex 压扁）。 */
        const TRACE_PAGE_SIZE = 10;
        /** 列表滚动容器：翻页后滚回顶部，否则停在上一页的滚动位置，看着像「点了没反应」。 */
        const traceListRef = ref(null);
        /** 总页数（空列表也按 1 页算，页脚语感一致）。 */
        const tracePageCount = computed(() =>
            Math.max(1, Math.ceil((traceModal.items.length || 0) / TRACE_PAGE_SIZE)));
        /** 当前页切片。注意统计条仍基于**全量** items —— 它回答「这个范围的总体情况」，不是「这一页」。 */
        const tracePageItems = computed(() => {
            const start = (traceModal.page - 1) * TRACE_PAGE_SIZE;
            return (traceModal.items || []).slice(start, start + TRACE_PAGE_SIZE);
        });
        /** 追踪列表的聚合统计（看板视角）：轮次 / token 合计 / 平均耗时 / 工具调用数 / 错误轮。纯客户端聚合，不改后端。 */
        const traceStats = computed(() => {
            const list = traceModal.items || [];
            const count = list.length;
            let totalTokens = 0, toolCalls = 0, errorCount = 0, elapsedSum = 0;
            for (const t of list) {
                totalTokens += t.totalTokens || 0;
                toolCalls += (t.toolCalls && t.toolCalls.length) || 0;
                if (t.status === 'error') errorCount++;
                elapsedSum += t.elapsedMs || 0;
            }
            return {
                count,
                totalTokens,
                toolCalls,
                errorCount,
                avgElapsed: count ? fmtElapsed(Math.round(elapsedSum / count)) : '-',
            };
        });

        async function openTrace() {
            traceModal.open = true;
            traceModal.loading = true;
            traceModal.items = [];
            traceModal.expanded = {};
            traceModal.error = '';
            traceModal.page = 1;   // 重查/换范围一律回到第 1 页（否则可能停在一个已经不存在的页码上）
            // 角色在这里定一次：弹窗只有点开才渲染，此时 Auth 早已就绪（页面未解锁时点不到按钮）。
            traceModal.isAdmin = !!(window.Auth && window.Auth.hasRole && window.Auth.hasRole('ADMIN'));
            try {
                // 范围：默认限定当前会话。仅 ADMIN 勾了「全部会话」时不带 conversationId ——
                // 后端据此按角色返回「本人的全部」或「全站全部」，前端不承担归属判定。
                const scopeAll = traceModal.isAdmin && traceModal.allConversations;
                const q = (!scopeAll && currentId.value)
                    ? ('?conversationId=' + encodeURIComponent(currentId.value)) : '';
                const resp = await apiFetch('/api/trace' + q);
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                const list = await resp.json();
                traceModal.items = Array.isArray(list) ? list : [];
                if (!traceModal.items.length) {
                    traceModal.error = traceScopeEmptyText();
                }
            } catch (e) {
                traceModal.error = '加载失败：' + e.message;
            } finally {
                traceModal.loading = false;
            }
        }
        /** 切换追踪范围（仅 ADMIN 可见入口）：切完立即按新范围重查，避免出现「按钮亮了但列表还是旧的」。 */
        function setTraceScope(all) {
            if (traceModal.allConversations === all) return;
            traceModal.allConversations = all;
            openTrace();
        }
        /** 工具栏说明文案：把当前实际生效的可见性范围讲清楚，别让人以为「本会话」永远是对的。 */
        function traceScopeHint() {
            if (traceModal.isAdmin && traceModal.allConversations) {
                return '管理员视图：显示全站所有会话的最近 50 轮（含已删除会话遗留的记录）。';
            }
            return '仅显示当前会话的最近 50 轮；追踪在本轮回复产出后异步落库，刚发出的那一轮可能稍有延迟。';
        }
        /** 空列表提示：按范围给不同措辞 —— 「本会话没有」和「全站都没有」是两回事。 */
        function traceScopeEmptyText() {
            if (traceModal.isAdmin && traceModal.allConversations) {
                return '暂无追踪记录（追踪在本轮回复产出后异步落库，可稍后重试）';
            }
            return '本会话暂无追踪记录（追踪在本轮回复产出后异步落库，可稍后重试）';
        }
        /** 展开/收起某轮明细。key 用 traceId 而不是列表下标 —— 分页后下标会跨页重复，
            用下标会让「第 1 页第 3 条」和「第 2 页第 3 条」共用同一个展开态。 */
        function toggleTrace(traceId) {
            traceModal.expanded[traceId] = !traceModal.expanded[traceId];
        }
        /** 翻页：夹在 [1, 总页数] 内；翻完把列表滚回顶部，否则停在上一页的滚动位置像「没反应」。 */
        function traceGoPage(p) {
            const next = Math.min(Math.max(1, p), tracePageCount.value);
            if (next === traceModal.page) return;
            traceModal.page = next;
            const el = traceListRef.value;
            if (el) el.scrollTop = 0;
        }
        /** 处理方来源 → 中文标签（与后端 agent_trace.route_source 取值对应）。 */
        function routeLabel(src) {
            return ({ BOUND: '会话绑定', ROUTE: '智能路由', NONE: '通用助手', PLAN: '规划编排', REVIEW: '并行评审' })[src] || (src || '-');
        }
        /** 形态 → 中文标签。 */
        function modeLabel(mode) {
            return mode === 'planner' ? '规划模式' : '普通对话';
        }
        /** 耗时 → 可读文本。 */
        function fmtElapsed(ms) {
            if (!ms && ms !== 0) return '-';
            return ms < 1000 ? (ms + ' ms') : ((ms / 1000).toFixed(2) + ' s');
        }
        /** 相关度分 → 百分比文本（精排分为 0~1，余弦也可能为负，负数一律显示 0%）。 */
        function fmtScore(score) {
            const n = Number(score);
            if (!isFinite(n)) return '-';
            return Math.round(Math.max(0, Math.min(1, n)) * 100) + '%';
        }

        // ===== 成本看板（全量成本口径） =====
        // 与「追踪」弹窗（会话级、只算回答成本）不同：这里跨会话聚合 agent_trace（回答本身）与
        // llm_usage（路由/参数抽取/查询改写/视觉/记忆合并等裸调用），做按天趋势 + 按用途拆解。
        const costModal = reactive({ open: false, loading: false, days: 30, data: null, error: '' });

        // ---- 提示词回归评测（改完 prompts.yaml 跑一批固定用例，与上一批对比看「新增失败」）----
        // 场景切换只影响「跑哪些用例」；每个场景各跑一批、批次独立落库，对比时也各比各的（混场景对比没意义）。
        // ADMIN 专属（后端 EvalController 标了 @RequireRole(ADMIN)）：「跑一批」是真实模型调用、消耗计入成本流水，
        // 与成本看板同为「会花钱的运维动作」，顶栏入口按同一角色显隐（见 chat.html 的 v-if="isAdmin"）。
        const evalScenarios = [
            { value: '', label: '全部' },
            { value: 'ROUTE', label: '路由' },
            { value: 'PLAN', label: '规划' }
        ];
        const evalModal = reactive({
            open: false, scenario: '', caseCount: 0,
            running: false, error: '', result: null, batches: [], compare: null,
            // 库内用例（yaml 之外的增量，目前唯一来源是「用户反馈转用例」）：json / 读取错误 / 正在删的那条 id
            dbCases: [], dbError: '', dbBusy: 0
        });

        /** 打开评测弹窗：先读用例集把条数显示出来（点「跑一批」前就该知道会发起多少次模型调用）。 */
        async function openEval() {
            evalModal.open = true;
            evalModal.result = null;
            evalModal.error = '';
            await loadEvalCases();
            await loadEvalDbCases();
            await loadEvalBatches();
        }

        function closeEval() {
            evalModal.open = false;
            evalModal.result = null;
            evalModal.compare = null;
        }

        /**
         * 库内用例（来自反馈的那些）：展示 + 删除。
         * <p>{@code eval-cases.yaml} 打包进 jar 后运行时写不了，所以「点踩 → 转成回归用例」只能落在库里；
         * yaml 退化为只读种子，本区块展示的是运行时增量。同名以 yaml 为准（种子是人工审校过的，不该被一条
         * 自动记录静默顶掉）—— 所以两侧可能有同名条目，这里只列库内的，不假装是全集。
         */
        async function loadEvalDbCases() {
            try {
                const resp = await apiFetch('/api/eval/cases/db');
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                evalModal.dbCases = await resp.json();
                evalModal.dbError = '';
            } catch (e) {
                evalModal.dbCases = [];
                evalModal.dbError = '库内用例读取失败：' + e.message;
            }
        }

        /** 删除库内用例。只影响后续跑批 —— 历史批次里已落库的结果不受影响（故确认文案里明说）。 */
        async function deleteEvalCase(c) {
            if (!c || evalModal.dbBusy) return;
            if (!confirm('删除回归用例「' + c.name + '」？\n\n'
                    + '删除只影响后续跑批；历史批次里已落库的结果不受影响。')) return;
            evalModal.dbBusy = c.id;
            evalModal.dbError = '';
            try {
                const resp = await apiFetch('/api/eval/cases/db/' + encodeURIComponent(c.id), { method: 'DELETE' });
                const body = await resp.json().catch(() => ({}));
                if (!resp.ok) throw new Error(body.message || ('HTTP ' + resp.status));
                await loadEvalDbCases();
                await loadEvalCases();   // 用例条数随之变化，头部那行得跟着更新
            } catch (e) {
                evalModal.dbError = '删除失败：' + e.message;
            } finally {
                evalModal.dbBusy = 0;
            }
        }

        /** 只读用例集（不跑批），仅取条数。 */
        async function loadEvalCases() {
            try {
                const resp = await apiFetch('/api/eval/cases' + evalScenarioQuery());
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                evalModal.caseCount = (await resp.json()).length;
            } catch (e) {
                evalModal.caseCount = 0;
            }
        }

        /** 跑一批。后端同步返回（单批上限 50 条、单条超时 60s），最长可能等数十秒，故按钮期间置忙。 */
        async function runEval() {
            evalModal.running = true;
            evalModal.error = '';
            try {
                const resp = await apiFetch('/api/eval/run' + evalScenarioQuery(), { method: 'POST' });
                // 403 说清楚原因：这是角色不够，不是跑批本身出错。入口已按角色隐藏，
                // 走到这里多半是「同一浏览器换了小号登录」或角色被管理员摘掉后的残留状态。
                if (resp.status === 403) throw new Error('评测仅管理员可用（当前账号无 ADMIN 角色）');
                if (!resp.ok) throw new Error(await evalErrorText(resp));
                evalModal.result = await resp.json();
                await loadEvalBatches();
            } catch (e) {
                evalModal.error = '跑批失败：' + e.message;
            } finally {
                evalModal.running = false;
            }
        }

        /** 拉历史批次；有 ≥2 批时顺带算出最近两批的对比。 */
        async function loadEvalBatches() {
            try {
                const resp = await apiFetch('/api/eval/batches');
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                evalModal.batches = await resp.json();
            } catch (e) {
                evalModal.batches = [];
            }
            await loadEvalCompare();
        }

        /**
         * 对比最近两批：batches 按批次时间倒序，故 [1] 是基准、[0] 是新批。
         * <p>不足两批、或某批的用例全是配置错误（后端 404）时不做对比 —— 这是正常情况，不是错误，
         * 不弹提示（提示了反而让人以为哪里坏了）。
         */
        async function loadEvalCompare() {
            evalModal.compare = null;
            if (evalModal.batches.length < 2) return;
            const from = evalModal.batches[1].batchId;
            const to = evalModal.batches[0].batchId;
            try {
                const resp = await apiFetch('/api/eval/compare?from=' + encodeURIComponent(from)
                    + '&to=' + encodeURIComponent(to));
                if (!resp.ok) return;
                evalModal.compare = await resp.json();
            } catch (e) {
                evalModal.compare = null;
            }
        }

        /** 场景过滤的查询串（空场景 = 全部，不传参数）。 */
        function evalScenarioQuery() {
            return evalModal.scenario ? ('?scenario=' + encodeURIComponent(evalModal.scenario)) : '';
        }

        /** 读后端错误体的 message（统一响应形状 {code,message,data}），读不到则退回 HTTP 状态码。 */
        async function evalErrorText(resp) {
            try {
                const j = await resp.json();
                if (j && j.message) return j.message;
            } catch (e) { /* 非 JSON 响应，退回状态码 */ }
            return 'HTTP ' + resp.status;
        }

        /** 用例结果的三态样式：配置错误（用例自己写错了）与失败（提示词改坏了）必须能一眼分开。 */
        function evalItemClass(r) {
            if (r.configError) return 'eval-config';
            return r.passed ? 'eval-pass' : 'eval-fail';
        }

        function evalTag(r) {
            if (r.configError) return '配置错误';
            return r.passed ? '通过' : '失败';
        }
        /** 两个图表实例引用（关闭弹窗时 dispose，避免复用残留）。 */
        let costTrendChart = null, costPurposeChart = null;

        /** token 数 → 可读文本（千分位 + 万/百万缩略）。 */
        function fmtTokens(n) {
            if (n == null) return '0';
            const v = Number(n);
            if (!isFinite(v)) return '-';
            if (v >= 1000000) return (v / 1000000).toFixed(2) + 'M';
            if (v >= 10000) return (v / 10000).toFixed(1) + 'w';
            return v.toLocaleString('en-US');
        }

        async function loadCost() {
            costModal.loading = true;
            costModal.error = '';
            try {
                const resp = await apiFetch('/api/cost/summary?days=' + costModal.days);
                // 403 说清楚原因：这是角色不够，不是网络或服务端故障。顶栏入口已按角色隐藏，
                // 走到这里多半是「同一浏览器换了小号登录」或角色被管理员摘掉后的残留状态。
                if (resp.status === 403) throw new Error('该看板仅管理员可见（当前账号无 ADMIN 角色）');
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                const data = await resp.json();
                costModal.data = data;
                renderCostCharts();
            } catch (e) {
                costModal.error = '加载失败：' + e.message;
            } finally {
                costModal.loading = false;
            }
        }

        async function openCost() {
            costModal.open = true;
            // 上次关闭若未 dispose（如遮罩点击关闭），图表实例仍指向已销毁 DOM，先清理再加载
            disposeCostCharts();
            await loadCost();
        }

        /** 关闭成本看板并释放图表实例（v-if 移除 DOM 后实例必须 dispose，否则指向游离节点、内存泄漏）。 */
        function closeCost() {
            disposeCostCharts();
            costModal.open = false;
        }

        function disposeCostCharts() {
            if (costTrendChart) { costTrendChart.dispose(); costTrendChart = null; }
            if (costPurposeChart) { costPurposeChart.dispose(); costPurposeChart = null; }
        }

        /** 渲染成本看板两个图表（按天趋势堆叠柱 + 按用途饼图）。复用 agent-dark 主题。 */
        function renderCostCharts() {
            if (typeof echarts === 'undefined' || !costModal.data) return;
            nextTick(() => {
                const trendEl = document.getElementById('cost-trend-el');
                const purposeEl = document.getElementById('cost-purpose-el');
                if (trendEl) {
                    if (!costTrendChart) { ensureEchartsTheme(); costTrendChart = echarts.init(trendEl, ECHARTS_THEME); }
                    costTrendChart.setOption(buildTrendOption(costModal.data), true);
                }
                if (purposeEl) {
                    if (!costPurposeChart) { ensureEchartsTheme(); costPurposeChart = echarts.init(purposeEl, ECHARTS_THEME); }
                    costPurposeChart.setOption(buildPurposeOption(costModal.data), true);
                }
            });
        }

        function buildTrendOption(data) {
            const days = (data.daily || []).map(d => d.day);
            const answer = (data.daily || []).map(d => d.answerTokens || 0);
            const aux = (data.daily || []).map(d => d.auxTokens || 0);
            return {
                tooltip: { trigger: 'axis' },
                legend: { data: ['回答本身', '辅助调用'] },
                grid: { left: 48, right: 16, top: 32, bottom: 28 },
                xAxis: { type: 'category', data: days, axisLabel: { rotate: days.length > 14 ? 45 : 0 } },
                yAxis: { type: 'value', name: 'token' },
                series: [
                    { name: '回答本身', type: 'bar', stack: 'total', data: answer, itemStyle: { color: '#3b82f6' }, barMaxWidth: 28 },
                    { name: '辅助调用', type: 'bar', stack: 'total', data: aux, itemStyle: { color: '#a78bfa' }, barMaxWidth: 28 }
                ]
            };
        }

        function buildPurposeOption(data) {
            const items = (data.byPurpose || []).map(p => ({
                name: p.label || p.purpose, value: p.totalTokens || 0
            }));
            return {
                tooltip: { trigger: 'item', formatter: '{b}: {c} token（{d}%）' },
                legend: { orient: 'vertical', right: 8, top: 'middle' },
                series: [{
                    type: 'pie', radius: ['40%', '70%'], center: ['40%', '50%'],
                    itemStyle: { borderColor: 'rgba(8,14,26,1)', borderWidth: 2 },
                    label: { show: false }, labelLine: { show: false },
                    data: items
                }]
            };
        }

        // ===== 本轮生成的中断控制 =====
        // 服务端是 SSE：客户端断开后服务端仍会把本轮跑完并落库，「停止」的语义是「不再接收」，
        // 不是「取消服务端计算」—— 这点必须说清楚，否则用户会以为点了停止就等于白跑一轮。
        let streamAbort = null;      // 当前这轮的 AbortController；null = 没有在跑
        let streamIdleTimer = null;  // 空闲计时器：连心跳都没有了，说明这条连接其实已经死了
        let stopReason = '';         // 主动断开的原因：'' 未断开 / 'user' 用户点停止 / 'idle' 空闲超时
        // 空闲上限：后端 SSE 心跳 15s（app.sse.heartbeat-seconds），这里取 4 个心跳的余量。
        // 没有它时，连接被中间层静默掐断会让 reader.read() 一直挂着 —— 输入框永久锁死，只能刷新页面。
        const STREAM_IDLE_MS = 60000;

        /** 开始一轮生成：拿到本轮的中断开关，并清掉上一轮遗留的断开原因。 */
        function beginStream() {
            stopReason = '';
            streamAbort = new AbortController();
            return streamAbort.signal;
        }

        /** 结束一轮生成：清计时器、释放中断开关（finally 里无条件调用）。 */
        function endStream() {
            if (streamIdleTimer) { clearTimeout(streamIdleTimer); streamIdleTimer = null; }
            streamAbort = null;
        }

        /** 每收到一段数据就重置空闲计时；到点仍未收到任何字节 → 判定连接已死，主动断开。 */
        function resetIdleTimer() {
            if (streamIdleTimer) clearTimeout(streamIdleTimer);
            streamIdleTimer = setTimeout(() => {
                if (streamAbort) { stopReason = 'idle'; streamAbort.abort(); }
            }, STREAM_IDLE_MS);
        }

        /** 用户点「停止」：断开本轮 SSE（服务端那轮仍会跑完，见本节开头说明）。 */
        function stopGeneration() {
            if (streamAbort) { stopReason = 'user'; streamAbort.abort(); }
        }

        // ===== 发送消息（流式） =====
        async function send() {
            const text = input.value.trim();
            // 允许「只有图片没有文字」的场景（如「这张图是什么」）；两者都空才拒绝
            if (loading.value) return;
            if (!text && attachments.value.length === 0) return;
            // 空白页（首页进入 / 刷新）时用户可能已经拨好规划 / RAG / 评审 / 跨会话开关，此刻还没有会话可写回。
            // newConversation() 会把开关复位为默认关闭，所以先暂存本次选择，建会话后再补写：
            // 否则用户拨动的选择被静默丢弃，且 RAG / 跨会话不写回会话时本轮根本不会走检索。
            const pendingPlanner = planMode.value;
            const pendingRag = ragEnabled.value;
            const pendingPlannerConfirm = plannerConfirm.value;
            const pendingReview = reviewEnabled.value;
            const pendingCrossSession = crossSession.value;
            if (!currentId.value) await newConversation();
            const convId = currentId.value;
            if (pendingPlanner && !planMode.value) { planMode.value = true; await onPlannerChange(); }
            if (pendingRag && !ragEnabled.value) { ragEnabled.value = true; await onRagEnabledChange(); }
            if (pendingPlannerConfirm && !plannerConfirm.value) { plannerConfirm.value = true; await onPlannerConfirmChange(); }
            // 补写顺序有讲究：先规划后评审。两者互斥且后端开启任一方会关掉另一方，
            // 按「用户最后拨动的是哪一个」无从得知，这里以规划优先（与后端 selectHandler 的防御一致）。
            if (pendingReview && !reviewEnabled.value) { reviewEnabled.value = true; await onReviewChange(); }
            if (pendingCrossSession && !crossSession.value) { crossSession.value = true; await onCrossSessionChange(); }

            // 编辑重发 / 重新生成：先给这一轮开一个新版本（旧版本一条不删，留在库里可翻回），
            // 再用输入框里的文本走下面同一条流式通路。开版本本身不改任何已有消息，失败就中止本轮
            // （保留编辑态，用户可重试或取消）—— 不能在半途状态下重发，否则新旧两条提问会同时挂在这一轮里。
            let branch = null;
            if (editingIndex.value >= 0) {
                const keep = editingIndex.value;
                try {
                    branch = await prepareBranch(keep + 1);   // 第 keep+1 条 = 被改写的那条提问
                } catch (e) {
                    alert('重发失败：' + e.message);
                    return;
                }
                editingIndex.value = -1;
                // 本地视图裁掉这一轮及其后，紧接着下面会把新版本推上来；服务端的旧版本仍在，可切回
                messages.value = messages.value.slice(0, keep);
            }
            // 新版本号连续递增（后端保证），故「总版本数」就等于本次的版本号 —— 不必再回查一次
            const branchTurn = branch
                ? { groupId: branch.groupId, version: branch.version, versionCount: branch.version }
                : null;

            // 发起新一轮：此前留下的待确认计划卡片一律失效。
            // 新的一轮规划会把旧 RUNNING 任务结为 CANCELLED（TaskService.cancelRunning），
            // 卡片若仍可点，就会去执行一个已被取消的任务。
            messages.value.forEach(x => { if (x.plan) x.planDone = true; });

            // 1) 把本轮所有附件一次性发给 /api/chat/attachment/process：
            //    图片→视觉模型识别、文档→解析为文本，后端按入参顺序返回 {type, filename, content}。
            //    任一文件解析失败不影响其余（该文件 content 为占位说明），前端照常发送。
            if (attachments.value.length) {
                try {
                    const fd = new FormData();
                    attachments.value.forEach(a => fd.append('files', a.file));
                    const resp = await apiFetch('/api/chat/attachment/process', { method: 'POST', body: fd });
                    if (!resp.ok) throw new Error('HTTP ' + resp.status);
                    const data = await resp.json();
                    const results = data.results || [];
                    for (let i = 0; i < attachments.value.length && i < results.length; i++) {
                        const a = attachments.value[i];
                        const r = results[i];
                        a.type = r.type || (a.isImage ? 'image' : 'file');
                        a.content = r.content || '';
                        if (r.filename) a.filename = r.filename;
                        // 落盘信息：服务端 URL 供历史/气泡直接访问；本会话仍优先用本地 blob 预览（零延迟）
                        a.storedName = r.storedName || null;
                        a.size = (r.size === undefined ? null : r.size);
                        a.url = r.url || (a.storedName ? ('/files/' + a.storedName) : null);
                    }
                } catch (e) {
                    alert('附件处理失败：' + e.message + '\n已取消本次发送，请稍后重试');
                    return;
                }
            }

            // 2) 组装本轮消息：用户原文 + 附件（解析文本供当轮注入、落盘 URL 供回看）。
            //    气泡渲染优先用本地 blob 预览（previewUrl，零延迟），历史加载则用服务端 url(/files/xxx)。
            const userText = text;
            const attMeta = attachments.value
                .filter(a => a.content != null)
                .map(a => ({
                    type: a.type || (a.isImage ? 'image' : 'file'),
                    content: a.content,
                    filename: a.filename || (a.isImage ? 'image' : 'file'),
                    previewUrl: a.previewUrl,
                    url: a.url || null,
                    storedName: a.storedName || null,
                    size: a.size,
                    isImage: a.isImage
                }));
            // 用户气泡：有文字显示文字；纯附件时 content 为空，靠 attachments 渲染缩略图/文件 chip
            const displayContent = userText;

            messages.value.push({
                role: 'user', content: displayContent, html: '', version: 0,
                attachments: attMeta.length ? attMeta : undefined,
                turn: branchTurn
            });
            // steps：本次运行的执行过程（规划与逐步进展）。仅前端临时展示，后端不写入会话记忆，
            // 因此刷新页面或重新打开会话时不会出现（历史消息只有最终结果）。
            // citesOpen：引用来源列表默认展开（有引用时才是视觉焦点）。
            messages.value.push({ role: 'assistant', content: '', html: '', version: 0, steps: [], stepsOpen: true, citesOpen: true, turn: branchTurn });
            input.value = '';
            loading.value = true;
            scrollToBottom();

            const lastIndex = messages.value.length - 1;
            const signal = beginStream();   // 本轮的中断开关：由「停止」按钮或空闲超时触发

            try {
                const resp = await apiFetch('/api/chat/stream', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    signal: signal,
                    body: JSON.stringify({
                        conversationId: convId,
                        message: userText,
                        planner: planMode.value,
                        // 发给后端的 attachments：带 content（当轮注入 LLM）+ storedName/size（落库供历史回看），
                        // 不带 previewUrl（那是浏览器本地 blob，无意义且后端用不到）
                        attachments: attMeta.length ? attMeta.map(a => ({
                            type: a.type, content: a.content, filename: a.filename,
                            storedName: a.storedName, size: a.size
                        })) : undefined,
                        // 分叉时带上分支组与版本号：服务端在本轮消息确实落库之后才打标，
                        // 并让同组旧版本失效（失败会补推一条 progress 明说，不是静默无反应）
                        branchGroupId: branch ? branch.groupId : undefined,
                        branchVersion: branch ? branch.version : undefined
                    })
                });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);

                const reader = resp.body.getReader();
                const decoder = new TextDecoder();
                let buf = '';

                const flushEvents = () => {
                    let evIdx;
                    while ((evIdx = buf.indexOf('\n\n')) >= 0) {
                        const block = buf.slice(0, evIdx);
                        buf = buf.slice(evIdx + 2);
                        let data = '';
                        // 后端用 JSON 包裹 token（{"token":"..."}），内容换行已被 JSON 转义，
                        // 事件流里不再出现裸 \n\n，这里只需取 data: 行并按 JSON 解析。
                        block.split('\n').forEach(line => {
                            if (line.startsWith('data:')) data += line.slice(5).replace(/^ /, '');
                        });
                        if (!data) continue;
                        // 后端按事件类型给字段名：token=正文分片（进记忆）；progress=执行过程（不进记忆）；
                        // citations=RAG 引用来源（正文推完后发一次，不进记忆，单独落库供历史回看）；
                        // plan=待确认计划；approval=执行到审批关卡暂停的待批步骤；error=本轮出错（红字提示、不进正文）
                        let progress = '';
                        let error = '';
                        let citations = '';
                        let planJson = '';
                        let approvalJson = '';
                        let reviewJson = '';
                        let recallJson = '';
                        try {
                            const parsed = JSON.parse(data);
                            if (parsed && typeof parsed.error === 'string') {
                                error = parsed.error;
                                data = '';
                            } else if (parsed && typeof parsed.progress === 'string') {
                                progress = parsed.progress;
                                data = '';
                            } else if (parsed && typeof parsed.citations === 'string') {
                                citations = parsed.citations;
                                data = '';
                            } else if (parsed && typeof parsed.plan === 'string') {
                                planJson = parsed.plan;
                                data = '';
                            } else if (parsed && typeof parsed.approval === 'string') {
                                approvalJson = parsed.approval;
                                data = '';
                            } else if (parsed && typeof parsed.review === 'string') {
                                // 并行评审候选（C1）：正文之前先到，最终答案由裁决者综合而来
                                reviewJson = parsed.review;
                                data = '';
                            } else if (parsed && typeof parsed.recall === 'string') {
                                // 跨会话回忆（A3）：本轮从本人其他会话召回的历史片段（JSON 数组）
                                recallJson = parsed.recall;
                                data = '';
                            } else {
                                data = (parsed && typeof parsed.token === 'string') ? parsed.token : '';
                            }
                        } catch (e) {
                            // 兼容旧格式：非 JSON 时按原始文本处理
                        }
                        const m = messages.value[lastIndex];
                        if (!m) return;   // 消息列表已被切会话/新建会话清空：没有可写的目标
                        if (error) {
                            // 错误事件：既在「执行过程」区留痕（红字），也在气泡里醒目展示（errText 直接可见，
                            // 不再藏在折叠的步骤里）；绝不混进正文（正文会进会话记忆）。
                            if (!m.steps) m.steps = [];
                            m.steps.push('❌ ' + error);
                            m.stepsOpen = true;
                            m.errText = error;
                        } else if (progress) {
                            // 执行过程：只收集到 steps 单独展示，绝不混进正文
                            if (!m.steps) m.steps = [];
                            m.steps.push(progress);
                        } else if (citations) {
                            // 引用来源：解析为列表挂在当前消息上，气泡底部渲染「引用来源」区（永不进正文）
                            try {
                                const list = JSON.parse(citations);
                                if (Array.isArray(list) && list.length) {
                                    m.citations = list;
                                    m.citesOpen = true;
                                }
                            } catch (e) { /* 引用解析失败不影响正文展示 */ }
                        } else if (planJson) {
                            // 待确认计划（规划模式「先看计划」）：渲染成可点「执行计划」的卡片。
                            // 计划明细同时也作为正文推了一遍，所以这里解析失败也不影响用户看到内容。
                            try {
                                const p = JSON.parse(planJson);
                                if (p && Array.isArray(p.steps) && p.steps.length) {
                                    m.plan = p;
                                    m.planRunning = false;
                                    m.planDone = false;
                                }
                            } catch (e) { /* 计划解析失败不影响正文展示 */ }
                        } else if (approvalJson) {
                            // 执行到审批关卡暂停：渲染审批卡片（批准并继续 / 终止计划）。
                            // 暂停说明同时也作为正文推了一遍，解析失败也不影响用户看到「卡在哪一步」。
                            try {
                                const p = JSON.parse(approvalJson);
                                if (p && typeof p.stepIndex === 'number') {
                                    m.approval = p;
                                    m.approvalRunning = false;
                                    m.approvalDone = false;
                                }
                            } catch (e) { /* 审批信息解析失败不影响正文展示 */ }
                        } else if (reviewJson) {
                            // 并行评审：解析成候选列表挂在当前消息上，气泡底部渲染「⚖ 并行评审」区。
                            // 默认折叠 —— 候选是完整作答，展开后可能很长；正文才是裁决后的答案。
                            try {
                                const p = JSON.parse(reviewJson);
                                if (p && Array.isArray(p.candidates) && p.candidates.length) {
                                    m.review = p;
                                    m.reviewOpen = false;
                                }
                            } catch (e) { /* 评审信息解析失败不影响正文展示 */ }
                        } else if (recallJson) {
                            // 跨会话回忆：解析成命中列表挂在当前消息上，气泡底部渲染「🔎 回忆到的历史」区。
                            // 它对应注入过的上下文，不进正文、不进会话记忆；刷新后不可回看（后端刻意不落库）。
                            try {
                                const list = JSON.parse(recallJson);
                                if (Array.isArray(list) && list.length) {
                                    m.recall = list;
                                    m.recallOpen = true;
                                }
                            } catch (e) { /* 回忆列表解析失败不影响正文展示 */ }
                        } else if (data) {
                            // 正文首个分片到达：自动收起执行过程，让最终结果成为视觉焦点（仍可手动展开）
                            if (!m.content && m.steps && m.steps.length) m.stepsOpen = false;
                            m.content += data;
                            m.html = renderMd(m.content);   // 更新 html 供 v-html 渲染
                            m.version++;                    // 版本号自增，强制 v-html 节点重建，保证 Markdown 始终渲染
                            renderCharts();                 // 防抖渲染图表（若内容已含完整 echarts 块）
                        }
                    }
                };

                resetIdleTimer();
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;
                    buf += decoder.decode(value, { stream: true });
                    flushEvents();
                    scrollToBottom();
                    resetIdleTimer();
                }
                buf += decoder.decode();
                flushEvents();
                renderChartsNow(); // 流结束：强制渲染图表（防抖可能还没到点）
                // 刷新侧边栏（标题/排序可能因首条消息而更新）
                loadConversations();
            } catch (e) {
                const m = messages.value[lastIndex];
                if (!m) return;   // 同上：列表已被清空，没有可写的目标
                if (e && e.name === 'AbortError') {
                    // 主动断开有两种来源，靠 stopReason 区分 —— 用户点的停止与连接自己死掉，说法不一样
                    m.errText = stopReason === 'idle'
                        ? '超过 ' + (STREAM_IDLE_MS / 1000) + ' 秒未收到新数据，已断开连接（网络中断或服务端未响应）。'
                        : '已停止接收本轮回复（服务端可能仍会完成本轮并落库，重开该会话可见完整结果）。';
                } else {
                    m.errText = '请求失败：' + e.message +
                        '（请确认后端已配置有效的 API Key / 服务地址，且 MySQL 已启动、服务已运行）';
                }
            } finally {
                endStream();
                loading.value = false;
                scrollToBottom();
                // 释放预览 URL 并清空待发送附件（用户消息气泡已带 previewUrl 引用，可继续显示）
                for (const a of attachments.value) {
                    if (a.previewUrl) URL.revokeObjectURL(a.previewUrl);
                }
                attachments.value = [];
                // 发送完成后刷新未完成任务状态（规划任务执行中断会留下 RUNNING，正常跑完会 DONE）
                loadRunningTask();
                loadQuota();   // 本轮消耗刚计入流水，刷新配额刻度
            }
        }

        // ===== 续跑未完成任务（显式按钮触发）=====
        // 走 POST /api/chat/task/resume（SSE），消费 progress/token/citations/error 事件，与 send 一致。
        // 续跑不重新规划、不新建会话：回填已完成步骤、只跑剩余步骤，最终结果推为一条 assistant 消息。
        async function resumeTask() {
            if (loading.value || !currentId.value) return;
            const convId = currentId.value;
            // 推一条空的 assistant 消息承接续跑输出（无用户气泡；续跑是对既有任务的延续）。
            // turn 恒为 null：它只用于「同一轮提问的多版本切换器」，而续跑不是给哪一轮开新版本 ——
            // 挂上一个版本上下文只会凭空多出一个翻不到第 2 版的切换器。
            // （此处原先写的是 `turn: branchTurn`，而 branchTurn 是 send() 里的局部量 —— 点「继续执行」
            //   会先抛 ReferenceError，整条续跑通路连请求都发不出去。2026-10-07 由 probe_intervene 抓到。）
            messages.value.push({ role: 'assistant', content: '', html: '', version: 0, steps: [], stepsOpen: true, citesOpen: true, turn: null });
            const lastIndex = messages.value.length - 1;
            const signal = beginStream();   // 续跑同样可中断（与 send 共用一套中断控制）
            loading.value = true;
            taskExecuting.value = true;
            scrollToBottom();

            try {
                const resp = await apiFetch('/api/chat/task/resume', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    signal: signal,
                    body: JSON.stringify({ conversationId: convId })
                });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);

                const reader = resp.body.getReader();
                const decoder = new TextDecoder();
                let buf = '';

                const flushEvents = () => {
                    let evIdx;
                    while ((evIdx = buf.indexOf('\n\n')) >= 0) {
                        const block = buf.slice(0, evIdx);
                        buf = buf.slice(evIdx + 2);
                        let data = '';
                        block.split('\n').forEach(line => {
                            if (line.startsWith('data:')) data += line.slice(5).replace(/^ /, '');
                        });
                        if (!data) continue;
                        let progress = '';
                        let error = '';
                        let citations = '';
                        let planJson = '';
                        let approvalJson = '';
                        let reviewJson = '';
                        let recallJson = '';
                        try {
                            const parsed = JSON.parse(data);
                            if (parsed && typeof parsed.error === 'string') {
                                error = parsed.error;
                                data = '';
                            } else if (parsed && typeof parsed.progress === 'string') {
                                progress = parsed.progress;
                                data = '';
                            } else if (parsed && typeof parsed.citations === 'string') {
                                citations = parsed.citations;
                                data = '';
                            } else if (parsed && typeof parsed.plan === 'string') {
                                planJson = parsed.plan;
                                data = '';
                            } else if (parsed && typeof parsed.approval === 'string') {
                                approvalJson = parsed.approval;
                                data = '';
                            } else if (parsed && typeof parsed.review === 'string') {
                                // 并行评审候选（C1）：正文之前先到，最终答案由裁决者综合而来
                                reviewJson = parsed.review;
                                data = '';
                            } else if (parsed && typeof parsed.recall === 'string') {
                                // 跨会话回忆（A3）：本轮从本人其他会话召回的历史片段（JSON 数组）
                                recallJson = parsed.recall;
                                data = '';
                            } else {
                                data = (parsed && typeof parsed.token === 'string') ? parsed.token : '';
                            }
                        } catch (e) { /* 兼容旧格式 */ }
                        const m = messages.value[lastIndex];
                        if (!m) return;   // 消息列表已被切会话/新建会话清空：没有可写的目标
                        if (error) {
                            if (!m.steps) m.steps = [];
                            m.steps.push('❌ ' + error);
                            m.stepsOpen = true;
                            m.errText = error;
                        } else if (progress) {
                            if (!m.steps) m.steps = [];
                            m.steps.push(progress);
                        } else if (citations) {
                            try {
                                const list = JSON.parse(citations);
                                if (Array.isArray(list) && list.length) {
                                    m.citations = list;
                                    m.citesOpen = true;
                                }
                            } catch (e) { /* 引用解析失败不影响正文 */ }
                        } else if (planJson) {
                            try {
                                const p = JSON.parse(planJson);
                                if (p && Array.isArray(p.steps) && p.steps.length) {
                                    m.plan = p;
                                    m.planRunning = false;
                                    m.planDone = false;
                                }
                            } catch (e) { /* 计划解析失败不影响正文 */ }
                        } else if (approvalJson) {
                            // 续跑途中又撞上下一个审批点：同样渲染审批卡片，用户在此继续决定
                            try {
                                const p = JSON.parse(approvalJson);
                                if (p && typeof p.stepIndex === 'number') {
                                    m.approval = p;
                                    m.approvalRunning = false;
                                    m.approvalDone = false;
                                }
                            } catch (e) { /* 审批信息解析失败不影响正文 */ }
                        } else if (reviewJson) {
                            // 续跑一般不会走评审（评审与规划互斥、续跑是规划的延续），但事件解析保持一致口径：
                            // 少一个分支就会让「意外到来」的事件被当成正文 token 拼进回答里。
                            try {
                                const p = JSON.parse(reviewJson);
                                if (p && Array.isArray(p.candidates) && p.candidates.length) {
                                    m.review = p;
                                    m.reviewOpen = false;
                                }
                            } catch (e) { /* 评审信息解析失败不影响正文 */ }
                        } else if (recallJson) {
                            try {
                                const list = JSON.parse(recallJson);
                                if (Array.isArray(list) && list.length) {
                                    m.recall = list;
                                    m.recallOpen = true;
                                }
                            } catch (e) { /* 回忆列表解析失败不影响正文 */ }
                        } else if (data) {
                            if (!m.content && m.steps && m.steps.length) m.stepsOpen = false;
                            m.content += data;
                            m.html = renderMd(m.content);
                            m.version++;
                            renderCharts();
                        }
                    }
                };

                resetIdleTimer();
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;
                    buf += decoder.decode(value, { stream: true });
                    flushEvents();
                    scrollToBottom();
                    resetIdleTimer();
                }
                buf += decoder.decode();
                flushEvents();
                renderChartsNow();
                loadConversations();
            } catch (e) {
                const m = messages.value[lastIndex];
                if (!m) return;
                if (e && e.name === 'AbortError') {
                    m.errText = stopReason === 'idle'
                        ? '超过 ' + (STREAM_IDLE_MS / 1000) + ' 秒未收到新数据，已断开连接（网络中断或服务端未响应）。'
                        : '已停止接收本轮续跑（服务端可能仍会完成本轮并落库）。';
                } else {
                    m.errText = '续跑失败：' + e.message;
                }
            } finally {
                endStream();
                loading.value = false;
                taskExecuting.value = false;
                // 续跑完成后刷新未完成任务状态：任务已 DONE/FAILED 则提示条消失
                await loadRunningTask();
                loadQuota();   // 续跑同样消耗 token，刷新配额刻度
                scrollToBottom();
            }
        }

        // ===== 执行「先看计划」卡片上的待确认计划 =====
        // 走的就是续跑通路：计划已作为 RUNNING 任务落库、步骤全 PENDING，续跑自然会「从头跑完整计划」。
        // 这样「确认执行」与「断点续跑」共用一套后端逻辑，不需要第二套执行入口。
        async function runPlan(m) {
            if (loading.value || !currentId.value || m.planRunning) return;
            m.planRunning = true;
            try {
                await resumeTask();
            } finally {
                m.planRunning = false;
                m.planDone = true;   // 无论成败都置为已消费：任务已 DONE/FAILED/CANCELLED，卡片不再可点
            }
        }

        // ===== 计划步骤就地编辑（「先看计划」）=====
        // 只改尚未执行的步骤。后端改的是 task_step 里 PENDING 的行，而执行侧（续跑通路）是从库里读步骤
        // 重建执行的，所以「保存 → 点执行计划」天然按新值运行，不需要重新规划、也不需要第二套执行入口。
        function editPlanStep(m, s) {
            if (m.planRunning || m.planDone) return;
            // 编辑值放在 editXxx 上，原值不动 —— 取消时直接丢弃即可，无需备份还原
            s.editAgentCode = s.agentCode;
            s.editInstruction = s.instruction || '';
            s.editDeps = Array.isArray(s.dependsOn) ? s.dependsOn.slice() : [];
            s.editError = '';
            s.editing = true;
        }

        function cancelPlanStep(s) {
            if (s.saving) return;
            s.editing = false;
            s.editError = '';
        }

        async function savePlanStep(m, s) {
            if (s.saving || !currentId.value) return;
            if (!s.editAgentCode) { s.editError = '请选择智能体'; return; }
            s.saving = true;
            s.editError = '';
            try {
                const resp = await apiFetch('/api/chat/task/step', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        conversationId: currentId.value,
                        stepIndex: s.index - 1,          // 卡片上是 1 基展示，接口要 0 基下标
                        agentCode: s.editAgentCode,
                        instruction: s.editInstruction,
                        dependsOn: s.editDeps
                    })
                });
                if (!resp.ok) {
                    // 后端业务校验失败会带中文原因（如「第 2 步已开始执行」），原样展示比显示状态码有用
                    let msg = 'HTTP ' + resp.status;
                    try {
                        const body = await resp.json();
                        if (body && body.message) msg = body.message;
                    } catch (_) { /* 非 JSON 响应：保留状态码 */ }
                    s.editError = msg;
                    return;
                }
                // 保存成功：把编辑值写回展示字段（后端已按同一口径落库，两边保持一致）
                const a = agents.value.find(x => x.agentCode === s.editAgentCode);
                s.agentCode = s.editAgentCode;
                if (a) s.agentName = a.name;
                s.instruction = s.editInstruction;
                s.dependsOn = s.editDeps.slice();
                s.editing = false;
            } catch (e) {
                s.editError = '保存失败：' + e.message;
            } finally {
                s.saving = false;
            }
        }

        // ===== 步骤审批点（执行到「需审批」步骤先暂停，批准后继续）=====
        // 审批标记与步骤定义一样落在 task_step 上，执行侧（续跑通路）读库即生效，因此本组函数只做两件事：
        // 写标记（勾选 / 批准）、以及「批准后立刻复用续跑」。没有任何第二套执行逻辑。

        /** 勾选 / 取消「需审批」并立即落库。失败回滚勾选态 —— 界面不能显示成已生效而库里没变。 */
        async function toggleStepApproval(m, s, checked) {
            if (!currentId.value || s.approving) return;
            const prev = !!s.approvalRequired;
            s.approving = true;
            s.approvalRequired = checked;
            try {
                const resp = await apiFetch('/api/chat/task/step', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        conversationId: currentId.value,
                        stepIndex: s.index - 1,          // 卡片上是 1 基展示，接口要 0 基下标
                        agentCode: s.agentCode,          // 该接口必填；此处原样回传，不动智能体
                        instruction: s.instruction || '',
                        dependsOn: Array.isArray(s.dependsOn) ? s.dependsOn : [],
                        approvalRequired: checked
                    })
                });
                if (!resp.ok) {
                    // 后端会给中文原因（如「第 2 步已开始执行，不能再修改」），原样展示比显示状态码有用
                    let msg = 'HTTP ' + resp.status;
                    try { const b = await resp.json(); if (b && b.message) msg = b.message; } catch (_) { /* 非 JSON */ }
                    s.approvalRequired = prev;
                    pushNotice('设置审批失败：' + msg);
                }
            } catch (e) {
                s.approvalRequired = prev;
                pushNotice('设置审批失败：' + e.message);
            } finally {
                s.approving = false;
            }
        }

        /** 批准待审批步骤并立刻继续执行：先 approve 写标记，再走续跑通路接着跑。 */
        async function approveAndResume(m) {
            if (loading.value || m.approvalRunning || m.approvalDone || !currentId.value) return;
            m.approvalRunning = true;
            try {
                const resp = await apiFetch('/api/chat/task/approve', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ conversationId: currentId.value, stepIndex: m.approval.stepIndex })
                });
                if (!resp.ok) {
                    let msg = 'HTTP ' + resp.status;
                    try { const b = await resp.json(); if (b && b.message) msg = b.message; } catch (_) { /* 非 JSON */ }
                    m.errText = '批准失败：' + msg;
                    return;
                }
                // 标记已落库，卡片即视为处理完毕：后续输出由续跑推入的新消息承接（可能又是一张审批卡）
                m.approvalDone = true;
                await resumeTask();
            } catch (e) {
                m.errText = '批准失败：' + e.message;
            } finally {
                m.approvalRunning = false;
            }
        }

        /** 终止当前计划：RUNNING → CANCELLED，剩余步骤不再执行（已完成步骤的产出保留在库里）。 */
        async function cancelPlanTask(m) {
            if (loading.value || m.approvalRunning || !currentId.value) return;
            if (!confirm('终止后剩余步骤不会再执行（已完成步骤的产出仍保留在库里）。继续吗？')) return;
            m.approvalRunning = true;
            try {
                const resp = await apiFetch('/api/chat/task/cancel', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ conversationId: currentId.value })
                });
                if (!resp.ok) {
                    let msg = 'HTTP ' + resp.status;
                    try { const b = await resp.json(); if (b && b.message) msg = b.message; } catch (_) { /* 非 JSON */ }
                    m.errText = '终止失败：' + msg;
                    return;
                }
                m.approvalDone = true;
                m.planDone = true;          // 同一条消息上的计划卡片一并失效，避免还能点「执行计划」
                await loadRunningTask();    // 任务已 CANCELLED：顶部「继续执行」提示条应随之消失
            } catch (e) {
                m.errText = '终止失败：' + e.message;
            } finally {
                m.approvalRunning = false;
            }
        }

        // ===== 执行中干预：暂停 / 跳过卡住的步骤 =====
        // 两个出口共用一条原则：都只改库、都不新开执行通路 —— 停或跳之后，都由用户点「继续执行」走既有的
        // 断点续跑（resumeTask）。所以这里不需要任何新的执行逻辑。

        /** 请求暂停正在执行的计划。不是硬中断：当前这一层跑完后才停，回执原文转述这一点。 */
        async function pauseTask() {
            if (!currentId.value || pausing.value) return;
            pausing.value = true;
            try {
                const resp = await apiFetch('/api/chat/task/pause', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ conversationId: currentId.value })
                });
                const body = await resp.json().catch(() => ({}));
                if (!resp.ok) {
                    pushNotice('暂停失败：' + (body.message || ('HTTP ' + resp.status)));
                    return;
                }
                pushNotice(body.message || '已请求暂停');
                if (runningTask.value) runningTask.value.pauseRequested = true;
            } catch (e) {
                pushNotice('暂停失败：' + e.message);
            } finally {
                pausing.value = false;
            }
        }

        /** 跳过卡住的那一步（PENDING / FAILED → SKIPPED）；成功后重拉提示条，进度随之更新。 */
        async function skipStep(stepIndex) {
            if (!currentId.value || skipping.value) return;
            if (!confirm('跳过第 ' + (stepIndex + 1) + ' 步？\n\n'
                    + '它不会被执行、产出为空；依赖它的后续步骤拿不到这段输入。\n'
                    + '（跳过只落库，之后点「继续执行」才接着跑）')) return;
            skipping.value = true;
            try {
                const resp = await apiFetch('/api/chat/task/step/skip', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ conversationId: currentId.value, stepIndex: stepIndex })
                });
                const body = await resp.json().catch(() => ({}));
                if (!resp.ok) {
                    pushNotice('跳过失败：' + (body.message || ('HTTP ' + resp.status)));
                    return;
                }
                pushNotice('已跳过第 ' + (stepIndex + 1) + ' 步，点「继续执行」接着跑');
                await loadRunningTask();
            } catch (e) {
                pushNotice('跳过失败：' + e.message);
            } finally {
                skipping.value = false;
            }
        }

        /**
         * 提示条上「可跳过的步骤」：第一个未成功的步骤（PENDING / FAILED）；没有则 null（不出跳过按钮）。
         *
         * 用 computed 而不是模板里调函数：流式期间消息区会频繁重渲染，模板里的函数调用每次都要重跑一遍
         * 查找。这个列表本身也跟着 runningTask 变，正是 computed 的适用场景。
         */
        const nextSkippable = computed(() => {
            const rt = runningTask.value;
            if (!rt || !Array.isArray(rt.steps)) return null;
            return rt.steps.find(s => s.status === 'PENDING' || s.status === 'FAILED') || null;
        });

        // ===== 局部重规划（只重排「第一个未成功步骤及其之后」的一段）=====
        // 与「续跑」的区别：续跑是原计划再跑一遍，重规划是这一段本身行不通时换一套。
        // 后端只改库、不执行（与「先看计划」同一节奏），所以这里把新计划作为一张卡片推入消息区，
        // 用户确认后再点卡片上的「执行计划」走续跑通路。
        /** 往消息区推一条纯提示（无助手气泡语义，仅用 errText 位置展示原因）。 */
        function pushNotice(text) {
            messages.value.push({
                role: 'assistant', content: '', html: '', version: 0,
                steps: [], plan: null, errText: text
            });
            scrollToBottom();
        }

        async function replanTask() {
            if (replanning.value || loading.value || !currentId.value) return;
            const convId = currentId.value;
            replanning.value = true;
            try {
                const resp = await apiFetch('/api/chat/task/replan', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ conversationId: convId })
                });
                if (!resp.ok) {
                    let msg = 'HTTP ' + resp.status;
                    try {
                        const body = await resp.json();
                        if (body && body.message) msg = body.message;
                    } catch (_) { /* 非 JSON 响应：保留状态码 */ }
                    pushNotice('重新规划失败：' + msg);
                    return;
                }
                const data = await resp.json();
                let plan = null;
                try { plan = JSON.parse(data.plan); } catch (_) { plan = null; }
                if (!plan || !Array.isArray(plan.steps) || !plan.steps.length) {
                    pushNotice('重新规划没有产出可用步骤，原计划保持不变。');
                    return;
                }
                // 请求期间用户可能切了会话：过期结果直接丢弃，不要写进别的会话的消息列表
                if (currentId.value !== convId) return;
                // 旧卡片标记为「已消费」：它展示的还是重规划前的步骤，不能再点执行
                messages.value.forEach(x => { if (x.plan) { x.planDone = true; x.planRunning = false; } });
                const msg = {
                    role: 'assistant', content: data.text || '', html: '', version: 0,
                    steps: [], stepsOpen: false, citesOpen: false,
                    plan: plan, planRunning: false, planDone: false
                };
                messages.value.push(msg);
                msg.html = renderMd(msg.content);
                msg.version++;
                scrollToBottom();
                await loadRunningTask();   // 任务仍是 RUNNING（尚未执行），提示条应继续在
            } catch (e) {
                pushNotice('重新规划失败：' + e.message);
            } finally {
                replanning.value = false;
            }
        }

        // ===== 规划模板（存 / 列 / 套用 / 删）=====
        // 模板只存「怎么排」（智能体 + 指令 + 依赖的快照），不含「做什么」—— 所以套用时必须填本次目标，
        // 它会成为后端 task.user_goal，并在执行时作为第一步的输入。套用**只生成计划卡片、不自动执行**：
        // 与「先看计划」同一节奏，用户可以先逐条改（卡片上的「编辑」），再点「执行计划」走续跑通路。

        /** 后端错误体是 {code,message,data}：优先展示服务端给的中文原因，取不到才退回状态码。 */
        async function tplErrText(resp) {
            try {
                const body = await resp.json();
                if (body && body.message) return body.message;
            } catch (_) { /* 非 JSON 响应：保留状态码 */ }
            return 'HTTP ' + resp.status;
        }

        /** 打开「从模板开始」：拉取本人模板列表（按创建时间倒序）。 */
        async function openTemplateList() {
            if (!currentId.value) { pushNotice('请先选择或新建一个会话'); return; }
            Object.assign(tplModal, { open: true, mode: 'use', loading: true, items: [], selectedId: null,
                confirmId: null, goal: '', saving: false, error: '' });
            try {
                const resp = await apiFetch('/api/chat/task/template/list');
                if (!resp.ok) throw new Error(await tplErrText(resp));
                tplModal.items = (await resp.json()) || [];
            } catch (e) {
                tplModal.error = '模板列表加载失败：' + e.message;
            } finally {
                tplModal.loading = false;
            }
        }

        /** 打开「存为模板」：来源是某张计划卡片，用它的 taskId 定位（后端以库里的当前步骤为准）。 */
        function openSaveTemplate(m) {
            const plan = m && m.plan;
            if (!plan || !plan.taskId) { pushNotice('这份计划没有可保存的任务'); return; }
            Object.assign(tplModal, { open: true, mode: 'save', loading: false, items: [], selectedId: null,
                confirmId: null, goal: '', saving: false, error: '',
                name: '', description: '', taskId: plan.taskId, stepCount: (plan.steps || []).length });
        }

        function closeTplModal() {
            if (tplModal.saving) return;   // 保存 / 生成进行中不关，避免请求结果无处安放
            tplModal.open = false;
        }

        function selectTemplate(t) {
            tplModal.selectedId = t.id;
            tplModal.confirmId = null;     // 改选别的模板即撤销「待确认删除」
            tplModal.error = '';
        }

        /** 把计划卡片的步骤骨架存为模板。 */
        async function submitSaveTemplate() {
            if (tplModal.saving) return;
            const name = (tplModal.name || '').trim();
            if (!name) { tplModal.error = '请填写模板名称'; return; }
            tplModal.saving = true;
            tplModal.error = '';
            try {
                const resp = await apiFetch('/api/chat/task/template/save', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ taskId: tplModal.taskId, name: name,
                        description: (tplModal.description || '').trim() })
                });
                if (!resp.ok) { tplModal.error = await tplErrText(resp); return; }
                tplModal.open = false;
                pushNotice('已存为模板：' + name + '（在输入区「模板」里可套用）');
            } catch (e) {
                tplModal.error = '保存失败：' + e.message;
            } finally {
                tplModal.saving = false;
            }
        }

        /** 套用模板：按骨架生成计划卡片（不执行）。 */
        async function submitApplyTemplate() {
            if (tplModal.saving || loading.value) return;
            const convId = currentId.value;
            const goal = (tplModal.goal || '').trim();
            if (!tplModal.selectedId) { tplModal.error = '请先选择模板'; return; }
            if (!goal) { tplModal.error = '请填写本次目标'; return; }
            tplModal.saving = true;
            tplModal.error = '';
            try {
                const resp = await apiFetch('/api/chat/task/template/apply', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ conversationId: convId, templateId: tplModal.selectedId, goal: goal })
                });
                if (!resp.ok) { tplModal.error = await tplErrText(resp); return; }
                const data = await resp.json();
                let plan = null;
                try { plan = JSON.parse(data.plan); } catch (_) { plan = null; }
                if (!plan || !Array.isArray(plan.steps) || !plan.steps.length) {
                    tplModal.error = '该模板没有可用步骤';
                    return;
                }
                tplModal.open = false;
                // 请求期间用户可能切了会话：过期结果直接丢弃，不要写进别的会话的消息列表
                if (currentId.value !== convId) return;
                // 旧卡片标为已消费：新任务已落库，旧卡片上的「执行计划」现在会去跑新任务
                messages.value.forEach(x => { if (x.plan) { x.planDone = true; x.planRunning = false; } });
                const msg = {
                    role: 'assistant', content: data.text || '', html: '', version: 0,
                    steps: [], stepsOpen: false, citesOpen: false,
                    plan: plan, planRunning: false, planDone: false
                };
                messages.value.push(msg);
                msg.html = renderMd(msg.content);
                msg.version++;
                scrollToBottom();
                await loadRunningTask();   // 新任务处于 RUNNING（未执行）：顶部提示条应随之出现
            } catch (e) {
                tplModal.error = '生成计划失败：' + e.message;
            } finally {
                tplModal.saving = false;
            }
        }

        /** 删除模板：第一次点只是进入待确认态，再点同一个才真删（按钮文案会跟着变）。 */
        async function removeTemplate(t) {
            if (tplModal.confirmId !== t.id) {
                tplModal.confirmId = t.id;
                return;
            }
            tplModal.confirmId = null;
            try {
                const resp = await apiFetch('/api/chat/task/template/' + encodeURIComponent(t.id), { method: 'DELETE' });
                if (!resp.ok) { pushNotice('删除失败：' + await tplErrText(resp)); return; }
                tplModal.items = tplModal.items.filter(x => x.id !== t.id);
                if (tplModal.selectedId === t.id) tplModal.selectedId = null;
            } catch (e) {
                pushNotice('删除失败：' + e.message);
            }
        }

        onMounted(() => {
            // 不自动打开最近会话：进入与刷新都落在空白欢迎页（点侧边栏历史才打开）。
            loadConversations();
            loadAgents();
            loadTools(); // 可用工具清单（智能体弹窗「工具装配」用）
            loadKbs(); // 侧边栏「知识库」计数
            loadQuota(); // 本日成本配额用量（后端未启用时 enabled=false，输入区不显示）
        });

        return {
            conversations, agents, messages, input, loading, currentId, planMode, ragEnabled, plannerConfirm,
            reviewEnabled, crossSession,
            runningTask, resumeTask, runPlan, replanning, replanTask,
            editPlanStep, cancelPlanStep, savePlanStep,
            // 步骤审批点：勾选「需审批」/ 批准并继续 / 终止计划
            toggleStepApproval, approveAndResume, cancelPlanTask,
            // 执行中干预：暂停（canPause/pausing/pauseTask）、跳过卡住的步骤（nextSkippable/skipping/skipStep）
            canPause, pausing, pauseTask, nextSkippable, skipping, skipStep,
            // 成本配额刻度（只展示，闸门在后端；fmtTokens 已在成本看板处导出）
            quota,
            // 规划模板：存 / 列 / 套用 / 删
            tplModal, openTemplateList, openSaveTemplate, closeTplModal,
            selectTemplate, submitSaveTemplate, submitApplyTemplate, removeTemplate,
            onRagEnabledChange, onPlannerChange, onPlannerConfirmChange,
            onReviewChange, onCrossSessionChange,
            // 多模态附件
            attachments, fileInput, triggerFilePicker, onFilePicked, removeAttachment, ACCEPT,
            editingId, editingTitle, agentModal, agentView,
            currentAgentName, currentAgentIcon, currentAgentId, currentPlanner, currentRagOn,
            currentClarifyAsked, currentClarifyMax,
            mainView, iconPresets,
            kbs, kbModal, kbDetail, availableAgents, kbFileInput, chroma, loadChromaStatus, syncChroma,
            send, stopGeneration, newConversation, startAgentChat, selectConversation,
            startEdit, commitEdit, deleteConversation,
            // 消息重做（重新生成 / 编辑重发）、消息分叉、会话导出、长期记忆面板
            editingIndex, regenerate, startEditMessage, cancelEditMessage, switchTurn, canRedo,
            // 引用回链：正文 [n] 角标点击 → 定位来源；来源条目「原文」→ 取知识块详情
            chunkModal, openCite, closeCite, onCiteClick,
            exportConversation, memoryModal, openMemory, saveMemory, resetMemory, memoryCoverage,
            // 长期事实条目（逐条）：增 / 改 / 删 + 行内编辑态（factTopics 与后端 TOPICS 同口径）
            factTopics, factBusy, factAdd, factEdit, addFact, startFactEdit, cancelFactEdit, saveFact, deleteFact,
            // 记忆透明化：单条「不参与记忆」开关（面板里的「当前窗口构成」读 memoryModal.window）
            toggleMemoryExcluded,
            // 消息反馈（👎 → 回归用例）：fbReasons 是原因下拉的可选项（与后端 REASONS 同口径）
            fbReasons, loadFeedback, openFeedback, submitFeedback, markFeedbackUp, promoteFeedback,
            // 智能体导入 / 导出
            exportAgents, triggerAgentImport, onAgentImportFile, agentImportInput,
            goChat, goAgents, goKbs,
            openCreateAgent, openEditAgent, closeAgentModal, saveAgent, deleteAgent,
            // 智能体管理页：搜索过滤 / 一览统计 / 卡片展示辅助
            agentQuery, shownAgents, agentStats, agentIconStyle, agentParamCount, agentToolLabel, agentHasKb,
            viewAgent, genPrompt, autoFillCode,
            addParam, removeParam, parseParamSchema,
            availableTools, toolGroups, parseTools, toolGroupLabel, toolSelectAll, toolClearAll,
            agentName, agentIcon, renderMd, scroll,
            loadKbs, kbIconStyle, fmtTime, fmtSize, typeIcon, openCreateKb, openRenameKb, autoKbName,
            kbTotalChunks, kbScopedCount, kbGlobalCount,
            chunkFilter, chunkSources, shownChunks, chunkBarStyle,
            strategyChoices, overlapChoices, agentChoices, sourceChoices,
            saveKb, deleteKb, openKbDetail, loadMoreChunks, deleteChunk,
            loadFiles, deleteKbFile,
            strategyLabel, strategyDesc, overlapOptions, overlapText, overlapLabel,
            openRechunk, doRechunk,
            pickFiles, onFilesChosen, onDropFiles, removeFile, uploadFiles,
            // 链路追踪（可观测）：traceModal + 分页 + 展示辅助函数 + ADMIN 的范围切换
            traceModal, traceStats, openTrace, toggleTrace, routeLabel, modeLabel, fmtElapsed, fmtScore,
            setTraceScope, traceScopeHint, traceListRef, tracePageItems, tracePageCount, traceGoPage,
            // 成本看板（全量成本口径，仅 ADMIN）：costModal + 加载/关闭 + token 格式化
            costModal, openCost, closeCost, loadCost, fmtTokens,
            // 页面级角色视点：顶栏按角色显隐的入口都读它（当前仅 💰 成本）
            isAdmin,
            // 提示词回归评测：evalModal + 跑批/用例数/批次对比 + 库内用例（来自反馈）的列出与删除
            evalScenarios, evalModal, openEval, closeEval, loadEvalCases, runEval, evalItemClass, evalTag,
            deleteEvalCase
        };
    }
});

app.component('ui-select', UiSelect);
app.mount('#app');

// 挂载成功 → 摘掉 chat.html 里那条静态启动提示（它只在 JS 未就绪时可见）
document.getElementById('boot-tip')?.remove();
