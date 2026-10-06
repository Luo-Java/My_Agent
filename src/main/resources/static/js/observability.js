// 可观测面板页（/observability.html）。Vue 由 /js/lib/vue.global.prod.js 提供。
// 登录态与鉴权统一走 auth.js；本页所有 /api 请求都经 apiFetch（附 Authorization）。
//
// 权限：整个 /api/observability/** 在后端由 @RequireRole("ADMIN") 把关，
// 前端这里只做「非管理员直接劝返」，不作为安全边界。
if (typeof Vue === 'undefined') {
    console.error('[启动失败] Vue 未加载：请确认 /js/lib/vue.global.prod.js 可访问（HTTP 200）。');
    throw new Error('Vue 未加载，前端无法启动');
}
const { createApp, ref, reactive, computed, onMounted } = Vue;

// ==================== API 封装（与 user.js / edu.js 同一套） ====================
function apiFetch(url, options) {
    const opts = Object.assign({}, options || {});
    const headers = Object.assign({}, opts.headers || {});
    if (opts.body && typeof opts.body === 'object') {
        headers['Content-Type'] = 'application/json';
        opts.body = JSON.stringify(opts.body);
    }
    opts.headers = headers;
    return window.Auth ? window.Auth.fetch(url, opts) : window.fetch(url, opts);
}

// ==================== 格式化 ====================
/** 毫秒 → 可读文本（<1000 显示 ms，否则显示秒）。 */
function fmtMs(ms) {
    if (ms == null) return '-';
    const v = Number(ms);
    if (!isFinite(v)) return '-';
    if (v < 1000) return Math.round(v) + ' ms';
    return (v / 1000).toFixed(2) + ' s';
}

/** token 数 → 可读文本（千分位 + 万/百万缩略）。 */
function fmtTokens(n) {
    if (n == null) return '0';
    const v = Number(n);
    if (!isFinite(v)) return '-';
    if (v >= 1000000) return (v / 1000000).toFixed(2) + 'M';
    if (v >= 10000) return (v / 10000).toFixed(1) + 'w';
    return v.toLocaleString('en-US');
}

/** 形态 → 中文标签。 */
function modeLabel(mode) {
    return mode === 'planner' ? '规划模式' : (mode === 'agent' ? '普通对话' : (mode || '未知'));
}

/** 处理方来源 → 中文标签。 */
function routeLabel(src) {
    const map = { BOUND: '会话绑定', ROUTE: '智能路由', NONE: '通用助手', PLAN: '规划编排', REVIEW: '并行评审' };
    return map[src] || (src || '未知');
}

createApp({
    setup() {
        // 顶栏登录用户区（用户名 + 下拉菜单）不在这里 —— 整块由 js/auth.js 渲染到 data-auth-nav
        // 挂载点上，各页共用同一份实现，本页不再持有登录态判断或退出逻辑。

        // 页面级 isAdmin：setup 时从 Auth.hasRole 取一次（Auth.getUser 读 localStorage、不是响应式，
        // 模板里直接调它只求值一次）。取不到角色一律当非管理员（UI 侧失败关闭），真闸门在后端。
        const isAdmin = ref(!!(window.Auth && window.Auth.hasRole && window.Auth.hasRole('ADMIN')));

        const dayOptions = [
            { value: 7, label: '近 7 天' },
            { value: 30, label: '近 30 天' },
            { value: 90, label: '近 90 天' }
        ];
        const days = ref(30);
        const loading = ref(false);
        const error = ref('');

        const overview = reactive({ rounds: 0, errors: 0, successRate: 100, avgElapsedMs: 0, avgTokens: 0, totalTokens: 0 });
        const daily = ref([]);
        const byMode = ref([]);
        const byRouteSource = ref([]);
        const byAgent = ref([]);
        const slowest = ref([]);

        // 分布条宽度：各桶轮次相对最大值归一化（最大值恒 100%）。
        const maxModeRounds = computed(() => byMode.value.reduce((m, b) => Math.max(m, b.rounds), 0));
        const maxRouteRounds = computed(() => byRouteSource.value.reduce((m, b) => Math.max(m, b.rounds), 0));
        const maxAgentRounds = computed(() => byAgent.value.reduce((m, b) => Math.max(m, b.rounds), 0));

        /** 归一化百分比（最大值为 0 时返回 0，避免除零）。 */
        function pct(value, max) {
            if (!max) return '0%';
            return (value / max * 100).toFixed(1) + '%';
        }

        function setDays(v) {
            days.value = v;
            load();
        }

        async function load() {
            if (!isAdmin.value) return;
            loading.value = true;
            error.value = '';
            try {
                const resp = await apiFetch('/api/observability/summary?days=' + days.value);
                // 403 说清楚：角色不够（页面本已按角色隐藏内容，走到这多半是角色被摘后的残留）。
                if (resp.status === 403) {
                    error.value = '可观测面板仅管理员可用（当前账号无 ADMIN 角色）。';
                    return;
                }
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                const data = await resp.json();
                Object.assign(overview, data.overview || {});
                daily.value = data.daily || [];
                byMode.value = data.byMode || [];
                byRouteSource.value = data.byRouteSource || [];
                byAgent.value = data.byAgent || [];
                slowest.value = data.slowest || [];
            } catch (e) {
                error.value = '加载失败：' + e.message;
            } finally {
                loading.value = false;
            }
        }

        onMounted(() => {
            // 挂载成功 → 摘掉 HTML 里那条静态启动提示（它只在 JS 未就绪时可见）。
            // 必须在挂载后移除：它是一条 position:fixed;inset:0 的不透明层（z-index 200），
            // 留着不摘的后果是「DOM 里数据齐全、屏幕上一片空」—— 且不报任何错。
            // 四页统一这个姿势（见 js/app.js、js/edu.js、js/user.js）。
            const tip = document.getElementById('boot-tip');
            if (tip) tip.remove();
            load();
        });

        return {
            isAdmin, dayOptions, days, loading, error,
            overview, daily, byMode, byRouteSource, byAgent, slowest,
            maxModeRounds, maxRouteRounds, maxAgentRounds,
            pct, setDays, load, fmtMs, fmtTokens, modeLabel, routeLabel
        };
    }
}).mount('#app');
