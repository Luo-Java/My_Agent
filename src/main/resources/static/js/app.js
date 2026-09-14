const { createApp, ref, reactive, computed, onMounted, nextTick } = Vue;

// ==================== 接口访问密钥（X-Api-Key） ====================
/** 密钥在浏览器本地的存储键。只存本机、不进页面源码（服务端配置 APP_API_KEY 后才需要填）。 */
const API_KEY_STORAGE = 'my_agent_api_key';

/** 读取已保存的访问密钥；localStorage 不可用（隐私模式等）时返回空串。 */
function getApiKey() {
    try { return localStorage.getItem(API_KEY_STORAGE) || ''; } catch (e) { return ''; }
}

/** 统一 API 请求：自动附加 X-Api-Key 头。
 *  所有 /api/** 调用都必须走这里 —— 服务端一旦配置 app.api-key，裸 fetch 会全部 401。
 *  附件图片走 /files/**（不在 ApiKeyInterceptor 的 /api/** 范围内），<img> 直连即可，无需带头。 */
function apiFetch(url, options) {
    const opts = Object.assign({}, options || {});
    const headers = Object.assign({}, opts.headers || {});
    const key = getApiKey();
    if (key) headers['X-Api-Key'] = key;
    opts.headers = headers;
    return window.fetch(url, opts);
}


// 配置 marked：启用 GFM（表格/任务列表/删除线），关闭 sanitize 让代码块正常渲染
if (typeof marked !== 'undefined') {
    marked.setOptions({
        gfm: true,
        breaks: true
    });
}

/** 将 Markdown 文本转为 HTML（防 XSS：先转义 HTML 标签后交给 marked）。
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
    // 3) 还原图表占位：JSON 合法 → 图表容器 div；非法（流式未完整）→ 保留代码块
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
    return html;
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
                const chart = echarts.init(el);
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
 *  citations：AI 消息的 RAG 引用来源（[{index,kbName,source,score}]），来自历史接口或当轮 SSE citations 事件。 */
function toMsg(role, content, attachments, citations) {
    return {
        role, content: content || '', html: renderMd(content || ''), version: 0,
        attachments: (attachments && attachments.length) ? attachments : undefined,
        citations: (citations && citations.length) ? citations : undefined,
        citesOpen: true
    };
}

createApp({
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

        // 顶栏「访问密钥」按钮状态：true = 本浏览器已保存密钥。
        // 只存布尔值，密钥本身不进 Vue 状态（避免出现在调试面板/组件实例里），读取统一走 getApiKey()。
        const apiKeySet = ref(!!getApiKey());

        // 输入框「📚 RAG」开关（会话级 RAG 开关）：false=不使用 RAG；true=每轮自动检索
        // 「通用知识库 + 路由智能体专属库」（不手动选库）。变更即写回会话（刷新后保持）。
        const ragEnabled = ref(false);

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

        // 尚无专属知识库的智能体（新建知识库下拉只列这些，一个智能体至多一个库）
        const availableAgents = computed(() =>
            agents.value.filter(a => !kbs.value.some(k => k.agentId === a.id)));

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

        // 当前会话是否开启 RAG（顶部 📚 徽标 / 输入框开关回显依据）
        const currentRagOn = computed(() => {
            const conv = conversations.value.find(c => c.id === currentId.value);
            return !!(conv && conv.ragEnabled);
        });

        // 📚 RAG 开关变更 → 即时写回会话（纯开关，不选库），刷新后保持上次选择。
        // 开启后每轮自动检索「通用知识库 + 路由到智能体时其专属库」，由后端 KbService 决定目标库。
        async function onRagEnabledChange() {
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

        // 🧭 智能规划开关变更 → 即时写回会话（与 RAG 开关对称，拨动即持久化），刷新后保持上次选择。
        // planner 与 agentId 互斥：绑定智能体的会话开关已置灰，后端另有防御校验拒绝越权开启。
        async function onPlannerChange() {
            if (!currentId.value) { planMode.value = false; return; }
            try {
                const resp = await apiFetch('/api/chat/conversation/' + encodeURIComponent(currentId.value) + '/planner', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ enabled: planMode.value })
                });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                // 同步侧边栏会话对象：顶部 🧭 徽标与会话列表「🧭 规划」标记即时更新
                const conv = conversations.value.find(c => c.id === currentId.value);
                if (conv) conv.planner = planMode.value;
            } catch (e) {
                planMode.value = !planMode.value;   // 保存失败回滚开关，避免 UI 与后端形态脱节
                alert('保存智能规划开关失败：' + e.message);
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
        async function loadConversations() {
            try {
                const resp = await apiFetch('/api/chat/conversations');
                if (!resp.ok) return;
                const list = await resp.json();
                conversations.value = list;
                if (list.length > 0) {
                    if (!currentId.value || !list.some(c => c.id === currentId.value)) {
                        await selectConversation(list[0].id);
                    }
                } else {
                    await newConversation();
                }
            } catch (e) { /* 忽略：后端未启动或数据库未就绪 */ }
        }

        // 开启新对话（默认助手，不绑定智能体）
        async function newConversation() {
            try {
                const resp = await apiFetch('/api/chat/conversation', { method: 'POST' });
                if (!resp.ok) { alert('创建会话失败'); return; }
                const data = await resp.json();
                currentId.value = data.conversationId;
                messages.value = [];
                conversations.value = [
                    { id: data.conversationId, title: data.title, updatedAt: data.createdAt, agentId: data.agentId, planner: data.planner, ragEnabled: !!data.ragEnabled },
                    ...conversations.value
                ];
                input.value = '';
                planMode.value = false; // 新建普通会话：规划开关默认关闭
                ragEnabled.value = false;   // 新建会话默认关闭 RAG；需要时在输入框自行开启
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
                currentId.value = data.conversationId;
                messages.value = [];
                conversations.value = [
                    { id: data.conversationId, title: data.title, updatedAt: data.createdAt, agentId: data.agentId, planner: data.planner, ragEnabled: !!data.ragEnabled },
                    ...conversations.value
                ];
                input.value = '';
                planMode.value = false; // 智能体会话不支持规划（planner 与 agentId 互斥），开关强制关闭
                ragEnabled.value = false;   // 新会话默认关闭 RAG；需要时在输入框自行开启
                agentView.open = false; // 从查看弹窗发起对话后关闭弹窗
                mainView.value = 'chat'; // 切回聊天视图
                scrollToBottom();
            } catch (e) {
                alert('创建会话失败：' + e.message);
            }
        }

        async function selectConversation(id) {
            currentId.value = id;
            messages.value = [];
            mainView.value = 'chat'; // 从智能体管理视图点击历史时切回聊天视图
            // 规划开关跟随会话形态：规划会话默认勾选（写回机制保证刷新后仍保持上次选择）
            const conv = conversations.value.find(c => c.id === id);
            planMode.value = !!(conv && conv.planner);
            // RAG 开关跟随会话：上次的开关状态回显（false=关闭）
            ragEnabled.value = !!(conv && conv.ragEnabled);
            try {
                const resp = await apiFetch('/api/chat/history?conversationId=' + encodeURIComponent(id));
                if (resp.ok) {
                    const data = await resp.json();
                    messages.value = (data.messages || []).map(m => toMsg(m.role, m.content, m.attachments, m.citations));
                }
            } catch (e) { /* 忽略 */ }
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

        // ===== 智能体 =====
        async function loadAgents() {
            try {
                const resp = await apiFetch('/api/agent');
                if (resp.ok) agents.value = await resp.json();
            } catch (e) { /* 忽略 */ }
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
        const traceModal = reactive({ open: false, loading: false, items: [], expanded: {}, error: '' });
        /** 顶栏「访问密钥」：写入/清除本浏览器的 X-Api-Key。
         *  用原生 prompt（无需新增样式）；密钥明文存 localStorage，仅本机可见。 */
        function editApiKey() {
            const typed = window.prompt(
                    '服务端未启用鉴权时无需填写。\n请输入访问密钥（与服务端 app.api-key / APP_API_KEY 一致；'
                    + '留空并确定 = 清除已保存的密钥）：',
                    getApiKey());
            if (typed === null) return;               // 用户取消
            const key = typed.trim();
            try {
                if (key) localStorage.setItem(API_KEY_STORAGE, key);
                else localStorage.removeItem(API_KEY_STORAGE);
            } catch (e) {
                alert('浏览器本地存储不可用（如隐私模式），密钥未能保存');
                return;
            }
            apiKeySet.value = !!key;
            alert(key ? '已保存访问密钥（仅存于本浏览器，刷新后仍生效）' : '已清除访问密钥');
        }

        async function openTrace() {
            traceModal.open = true;
            traceModal.loading = true;
            traceModal.items = [];
            traceModal.expanded = {};
            traceModal.error = '';
            try {
                const q = currentId.value ? ('?conversationId=' + encodeURIComponent(currentId.value)) : '';
                const resp = await apiFetch('/api/trace' + q);
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                const list = await resp.json();
                traceModal.items = Array.isArray(list) ? list : [];
                if (!traceModal.items.length) {
                    traceModal.error = '本会话暂无追踪记录（追踪在本轮回复产出后异步落库，可稍后重试）';
                }
            } catch (e) {
                traceModal.error = '加载失败：' + e.message;
            } finally {
                traceModal.loading = false;
            }
        }
        function toggleTrace(i) {
            traceModal.expanded[i] = !traceModal.expanded[i];
        }
        /** 处理方来源 → 中文标签（与后端 agent_trace.route_source 取值对应）。 */
        function routeLabel(src) {
            return ({ BOUND: '会话绑定', ROUTE: '智能路由', NONE: '通用助手', PLAN: '规划编排' })[src] || (src || '-');
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

        // ===== 发送消息（流式） =====
        async function send() {
            const text = input.value.trim();
            // 允许「只有图片没有文字」的场景（如「这张图是什么」）；两者都空才拒绝
            if (loading.value) return;
            if (!text && attachments.value.length === 0) return;
            if (!currentId.value) await newConversation();
            const convId = currentId.value;

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
                attachments: attMeta.length ? attMeta : undefined
            });
            // steps：本次运行的执行过程（规划与逐步进展）。仅前端临时展示，后端不写入会话记忆，
            // 因此刷新页面或重新打开会话时不会出现（历史消息只有最终结果）。
            // citesOpen：引用来源列表默认展开（有引用时才是视觉焦点）。
            messages.value.push({ role: 'assistant', content: '', html: '', version: 0, steps: [], stepsOpen: true, citesOpen: true });
            input.value = '';
            loading.value = true;
            scrollToBottom();

            const lastIndex = messages.value.length - 1;

            try {
                const resp = await apiFetch('/api/chat/stream', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        conversationId: convId,
                        message: userText,
                        planner: planMode.value,
                        // 发给后端的 attachments：带 content（当轮注入 LLM）+ storedName/size（落库供历史回看），
                        // 不带 previewUrl（那是浏览器本地 blob，无意义且后端用不到）
                        attachments: attMeta.length ? attMeta.map(a => ({
                            type: a.type, content: a.content, filename: a.filename,
                            storedName: a.storedName, size: a.size
                        })) : undefined
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
                        // error=本轮出错（红字提示、不进正文）
                        let progress = '';
                        let error = '';
                        let citations = '';
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
                            } else {
                                data = (parsed && typeof parsed.token === 'string') ? parsed.token : '';
                            }
                        } catch (e) {
                            // 兼容旧格式：非 JSON 时按原始文本处理
                        }
                        const m = messages.value[lastIndex];
                        if (error) {
                            // 错误事件：红色提示展示在「执行过程」区，绝不混进正文（正文会进会话记忆）
                            if (!m.steps) m.steps = [];
                            m.steps.push('❌ ' + error);
                            m.stepsOpen = true;
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

                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;
                    buf += decoder.decode(value, { stream: true });
                    flushEvents();
                    scrollToBottom();
                }
                buf += decoder.decode();
                flushEvents();
                renderChartsNow(); // 流结束：强制渲染图表（防抖可能还没到点）
                // 刷新侧边栏（标题/排序可能因首条消息而更新）
                loadConversations();
            } catch (e) {
                const m = messages.value[lastIndex];
                const msg = '⚠️ 请求失败：' + e.message +
                    '\n请确认后端已配置有效的 API Key / 服务地址，且 MySQL 已启动、服务已运行。';
                m.content = msg;
                m.html = escapeHtml(msg).replace(/\n/g, '<br>');
            } finally {
                loading.value = false;
                scrollToBottom();
                // 释放预览 URL 并清空待发送附件（用户消息气泡已带 previewUrl 引用，可继续显示）
                for (const a of attachments.value) {
                    if (a.previewUrl) URL.revokeObjectURL(a.previewUrl);
                }
                attachments.value = [];
            }
        }

        onMounted(() => {
            loadConversations();
            loadAgents();
            loadTools(); // 可用工具清单（智能体弹窗「工具装配」用）
            loadKbs(); // 侧边栏「知识库」计数
        });

        return {
            conversations, agents, messages, input, loading, currentId, planMode, ragEnabled,
            onRagEnabledChange, onPlannerChange,
            // 多模态附件
            attachments, fileInput, triggerFilePicker, onFilePicked, removeAttachment, ACCEPT,
            editingId, editingTitle, agentModal, agentView,
            currentAgentName, currentAgentIcon, currentAgentId, currentPlanner, currentRagOn,
            mainView, iconPresets,
            kbs, kbModal, kbDetail, availableAgents, kbFileInput, chroma, loadChromaStatus, syncChroma,
            send, newConversation, startAgentChat, selectConversation,
            startEdit, commitEdit, deleteConversation,
            goChat, goAgents, goKbs,
            openCreateAgent, openEditAgent, closeAgentModal, saveAgent, deleteAgent,
            viewAgent, genPrompt, autoFillCode,
            addParam, removeParam, parseParamSchema,
            availableTools, toolGroups, parseTools, toolGroupLabel, toolSelectAll, toolClearAll,
            agentName, agentIcon, renderMd, scroll,
            loadKbs, kbIconStyle, fmtTime, fmtSize, typeIcon, openCreateKb, openRenameKb, autoKbName,
            saveKb, deleteKb, openKbDetail, loadMoreChunks, deleteChunk,
            loadFiles, deleteKbFile,
            strategyLabel, overlapOptions, overlapText, overlapLabel,
            openRechunk, doRechunk,
            pickFiles, onFilesChosen, onDropFiles, removeFile, uploadFiles,
            // 顶栏「访问密钥」：状态 + 修改入口（所有 /api 请求经 apiFetch 自动带上该密钥）
            apiKeySet, editApiKey,
            // 链路追踪（可观测）：traceModal + 展示辅助函数
            traceModal, openTrace, toggleTrace, routeLabel, modeLabel, fmtElapsed, fmtScore
        };
    }
}).mount('#app');
