const { createApp, ref, reactive, computed, onMounted, nextTick } = Vue;

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
 *  规避流式高频更新下 v-html 未刷新（DOM 停留在中间态）导致的 Markdown 未渲染问题。 */
function toMsg(role, content) {
    return { role, content: content || '', html: renderMd(content || ''), version: 0 };
}

createApp({
    setup() {
        const conversations = ref([]);
        const agents = ref([]);
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

        // 主区域视图：chat=聊天 | agents=智能体管理 | kbs=知识库管理
        const mainView = ref('chat');

        // ===== 知识库（RAG）状态 =====
        const kbs = ref([]);   // 知识库列表（全局库置顶；列表来自后端，含 docCount/agentId）
        // 新建 / 重命名弹窗：mode=create 为某智能体建专属库；mode=rename 改名与说明
        const kbModal = reactive({ open: false, mode: 'create', id: null, agentId: null, name: '', description: '', saving: false });
        // 库详情视图：kb=当前库；chunks/total=知识块分页；source/text=添加知识表单
        const kbDetail = reactive({ open: false, kb: null, chunks: [], total: 0, source: '', text: '', adding: false });

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
                const resp = await fetch('/api/chat/conversations');
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
                const resp = await fetch('/api/chat/conversation', { method: 'POST' });
                if (!resp.ok) { alert('创建会话失败'); return; }
                const data = await resp.json();
                currentId.value = data.conversationId;
                messages.value = [];
                conversations.value = [
                    { id: data.conversationId, title: data.title, updatedAt: data.createdAt, agentId: data.agentId, planner: data.planner },
                    ...conversations.value
                ];
                input.value = '';
                planMode.value = false; // 新建普通会话：规划开关默认关闭
                mainView.value = 'chat';
                scrollToBottom();
            } catch (e) {
                alert('创建会话失败：' + e.message);
            }
        }

        // 用某个智能体开启新对话
        async function startAgentChat(a) {
            try {
                const resp = await fetch('/api/chat/conversation', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ agentId: a.id })
                });
                if (!resp.ok) { alert('创建会话失败'); return; }
                const data = await resp.json();
                currentId.value = data.conversationId;
                messages.value = [];
                conversations.value = [
                    { id: data.conversationId, title: data.title, updatedAt: data.createdAt, agentId: data.agentId, planner: data.planner },
                    ...conversations.value
                ];
                input.value = '';
                planMode.value = false; // 智能体会话不支持规划（planner 与 agentId 互斥），开关强制关闭
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
            try {
                const resp = await fetch('/api/chat/history?conversationId=' + encodeURIComponent(id));
                if (resp.ok) {
                    const data = await resp.json();
                    messages.value = (data.messages || []).map(m => toMsg(m.role, m.content));
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
                const resp = await fetch('/api/chat/conversation/' + encodeURIComponent(id), {
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
                const resp = await fetch('/api/chat/conversation/' + encodeURIComponent(id), {
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
                const resp = await fetch('/api/agent');
                if (resp.ok) agents.value = await resp.json();
            } catch (e) { /* 忽略 */ }
        }

        function openCreateAgent() {
            Object.assign(agentModal, {
                open: true, id: null,
                name: '', agentCode: '', icon: '', description: '', systemPrompt: '',
                model: '', temperature: null, avatarColor: '',
                paramList: [],
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
                saving: false, genLoading: false
            });
            agentView.open = false; // 从查看弹窗进入编辑时关闭查看弹窗
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
                const resp = await fetch('/api/agent/generate-prompt', {
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
                paramSchema: buildParamSchema()
            };
            const url = m.id ? ('/api/agent/' + encodeURIComponent(m.id)) : '/api/agent';
            const method = m.id ? 'PUT' : 'POST';
            try {
                const resp = await fetch(url, {
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
                const resp = await fetch('/api/agent/' + encodeURIComponent(id), { method: 'DELETE' });
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
                const resp = await fetch('/api/kb');
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

        // 打开「新建知识库」弹窗：预选第一个暂无库的智能体，名称自动填充
        function openCreateKb() {
            const first = availableAgents.value[0];
            Object.assign(kbModal, { open: true, mode: 'create', id: null, agentId: first ? first.id : null, name: '', description: '', saving: false });
            autoKbName();
        }

        function openRenameKb(kb) {
            Object.assign(kbModal, { open: true, mode: 'rename', id: kb.id, agentId: kb.agentId, name: kb.name, description: kb.description || '', saving: false });
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
                const resp = await fetch(url, {
                    method,
                    headers: { 'Content-Type': 'application/json' },
                    // rename 时 agentId 恒为 null：归属不可变更（全局/专属由创建决定）
                    body: JSON.stringify({
                        name: kbModal.name,
                        description: kbModal.description || null,
                        agentId: kbModal.mode === 'create' ? kbModal.agentId : null
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
                // 详情视图打开时同步库名（改名的正是当前详情库）
                if (kbDetail.open && kbDetail.kb && kbDetail.kb.id === saved.id) kbDetail.kb = saved;
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
                const resp = await fetch('/api/kb/' + encodeURIComponent(id), { method: 'DELETE' });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                kbs.value = kbs.value.filter(x => x.id !== id);
                if (kbDetail.open && kbDetail.kb && kbDetail.kb.id === id) kbDetail.open = false;
            } catch (e) {
                alert('删除失败：' + e.message);
            }
        }

        // 打开库详情（知识块列表态清零后加载第一页）
        async function openKbDetail(kb) {
            kbDetail.kb = kb;
            kbDetail.chunks = [];
            kbDetail.total = 0;
            kbDetail.source = '';
            kbDetail.text = '';
            kbDetail.open = true;
            await loadChunks(false);
        }

        // 加载知识块：append=false 覆盖当前页（从第 0 块），append=true 追加（加载更多）
        async function loadChunks(append) {
            if (!kbDetail.kb) return;
            try {
                const offset = append ? kbDetail.chunks.length : 0;
                const resp = await fetch('/api/kb/' + kbDetail.kb.id + '/chunks?offset=' + offset + '&limit=20');
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

        // 预计分块数（与服务端分块策略一致：按 600 字粗估）
        const estimateBlocks = computed(() => {
            const t = (kbDetail.text || '').trim();
            return t ? Math.max(1, Math.ceil(t.length / 600)) : 0;
        });

        // 添加知识：整段文本交后端分块 + 向量化入库
        async function addChunks() {
            const text = (kbDetail.text || '').trim();
            if (!text || kbDetail.adding) return;
            kbDetail.adding = true;
            try {
                const resp = await fetch('/api/kb/' + kbDetail.kb.id + '/chunks', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ source: (kbDetail.source || '').trim() || null, texts: [text] })
                });
                if (!resp.ok) {
                    let msg = 'HTTP ' + resp.status;
                    try { const e = await resp.json(); if (e && e.message) msg = e.message; } catch (_) { /* 忽略 */ }
                    throw new Error(msg);
                }
                const d = await resp.json();
                kbDetail.text = '';
                kbDetail.source = '';
                await loadChunks(false);
                await loadKbs();   // 同步卡片上的知识块计数
                if (kbDetail.kb && d && typeof d.added === 'number') {
                    kbDetail.kb.docCount = (kbDetail.kb.docCount || 0) + d.added;
                }
            } catch (e) {
                alert('添加失败：' + e.message);
            } finally {
                kbDetail.adding = false;
            }
        }

        async function deleteChunk(cid) {
            if (!confirm('删除该知识块？删除后对话将不再检索到它。')) return;
            try {
                const resp = await fetch('/api/kb/' + kbDetail.kb.id + '/chunks/' + cid, { method: 'DELETE' });
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                kbDetail.chunks = kbDetail.chunks.filter(c => c.id !== cid);
                kbDetail.total = Math.max(0, kbDetail.total - 1);
                if (kbDetail.kb && kbDetail.kb.docCount) kbDetail.kb.docCount = Math.max(0, kbDetail.kb.docCount - 1);
                await loadKbs();
            } catch (e) {
                alert('删除失败：' + e.message);
            }
        }

        // ===== 发送消息（流式） =====
        async function send() {
            const text = input.value.trim();
            if (!text || loading.value) return;
            if (!currentId.value) await newConversation();
            const convId = currentId.value;

            messages.value.push({ role: 'user', content: text, html: '', version: 0 });
            // steps：本次运行的执行过程（规划与逐步进展）。仅前端临时展示，后端不写入会话记忆，
            // 因此刷新页面或重新打开会话时不会出现（历史消息只有最终结果）。
            messages.value.push({ role: 'assistant', content: '', html: '', version: 0, steps: [], stepsOpen: true });
            input.value = '';
            loading.value = true;
            scrollToBottom();

            const lastIndex = messages.value.length - 1;

            try {
                const resp = await fetch('/api/chat/stream', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ conversationId: convId, message: text, planner: planMode.value })
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
                        // error=本轮出错（红字提示、不进正文）
                        let progress = '';
                        let error = '';
                        try {
                            const parsed = JSON.parse(data);
                            if (parsed && typeof parsed.error === 'string') {
                                error = parsed.error;
                                data = '';
                            } else if (parsed && typeof parsed.progress === 'string') {
                                progress = parsed.progress;
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
            }
        }

        onMounted(() => {
            loadConversations();
            loadAgents();
            loadKbs(); // 侧边栏「知识库」计数
        });

        return {
            conversations, agents, messages, input, loading, currentId, planMode,
            editingId, editingTitle, agentModal, agentView,
            currentAgentName, currentAgentIcon, currentAgentId, currentPlanner,
            mainView, iconPresets,
            kbs, kbModal, kbDetail, availableAgents, estimateBlocks,
            send, newConversation, startAgentChat, selectConversation,
            startEdit, commitEdit, deleteConversation,
            goChat, goAgents, goKbs,
            openCreateAgent, openEditAgent, closeAgentModal, saveAgent, deleteAgent,
            viewAgent, genPrompt, autoFillCode,
            addParam, removeParam, parseParamSchema,
            agentName, agentIcon, renderMd, scroll,
            loadKbs, kbIconStyle, fmtTime, openCreateKb, openRenameKb, autoKbName,
            saveKb, deleteKb, openKbDetail, loadMoreChunks, addChunks, deleteChunk
        };
    }
}).mount('#app');
