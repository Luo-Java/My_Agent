const { createApp, ref, reactive, computed, onMounted, nextTick } = Vue;

// 配置 marked：启用 GFM（表格/任务列表/删除线），关闭 sanitize 让代码块正常渲染
if (typeof marked !== 'undefined') {
    marked.setOptions({
        gfm: true,
        breaks: true
    });
}

/** 将 Markdown 文本转为 HTML（防 XSS：先转义 HTML 标签后交给 marked） */
function renderMd(text) {
    if (!text) return '';
    if (typeof marked === 'undefined') return escapeHtml(text);
    // 先转义用户输入中的 HTML 标签，再由 marked 生成安全的 Markdown HTML
    const escaped = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    try {
        const r = marked.parse(escaped);
        // 兼容新版 marked 偶尔返回 Promise 的情况
        return typeof r === 'string' ? r : escaped;
    } catch {
        return escaped;
    }
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

        // 主区域视图：chat=聊天 | agents=智能体管理
        const mainView = ref('chat');

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
                    { id: data.conversationId, title: data.title, updatedAt: data.createdAt, agentId: data.agentId },
                    ...conversations.value
                ];
                input.value = '';
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
                    { id: data.conversationId, title: data.title, updatedAt: data.createdAt, agentId: data.agentId },
                    ...conversations.value
                ];
                input.value = '';
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
            try {
                const resp = await fetch('/api/chat/history?conversationId=' + encodeURIComponent(id));
                if (resp.ok) {
                    const data = await resp.json();
                    messages.value = (data.messages || []).map(m => toMsg(m.role, m.content));
                }
            } catch (e) { /* 忽略 */ }
            scrollToBottom();
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

        // ===== 发送消息（流式） =====
        async function send() {
            const text = input.value.trim();
            if (!text || loading.value) return;
            if (!currentId.value) await newConversation();
            const convId = currentId.value;

            messages.value.push({ role: 'user', content: text, html: '', version: 0 });
            messages.value.push({ role: 'assistant', content: '', html: '', version: 0 });
            input.value = '';
            loading.value = true;
            scrollToBottom();

            const lastIndex = messages.value.length - 1;

            try {
                const resp = await fetch('/api/chat/stream', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ conversationId: convId, message: text })
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
                        try {
                            const parsed = JSON.parse(data);
                            data = (parsed && typeof parsed.token === 'string') ? parsed.token : '';
                        } catch (e) {
                            // 兼容旧格式：非 JSON 时按原始文本处理
                        }
                        if (data) {
                            const m = messages.value[lastIndex];
                            m.content += data;
                            m.html = renderMd(m.content);   // 更新 html 供 v-html 渲染
                            m.version++;                    // 版本号自增，强制 v-html 节点重建，保证 Markdown 始终渲染
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
        });

        return {
            conversations, agents, messages, input, loading, currentId,
            editingId, editingTitle, agentModal, agentView, currentAgentName, currentAgentIcon,
            mainView, iconPresets,
            send, newConversation, startAgentChat, selectConversation,
            startEdit, commitEdit, deleteConversation,
            goChat, goAgents,
            openCreateAgent, openEditAgent, closeAgentModal, saveAgent, deleteAgent,
            viewAgent, genPrompt, autoFillCode,
            addParam, removeParam, parseParamSchema,
            agentName, agentIcon, renderMd, scroll
        };
    }
}).mount('#app');
