// 用户管理页（/user.html）。Vue 由 /js/lib/vue.global.prod.js 提供。
// 登录态与鉴权统一走 auth.js；本页所有 /api 请求都经 apiFetch（附 Authorization）。
//
// 权限：整个 /api/user/** 与 /api/role/** 在后端由 @RequireRole("ADMIN") 把关，
// 前端这里只做「非管理员直接劝返」，不作为安全边界。
if (typeof Vue === 'undefined') {
    console.error('[启动失败] Vue 未加载：请确认 /js/lib/vue.global.prod.js 可访问（HTTP 200）。');
    throw new Error('Vue 未加载，前端无法启动');
}
const { createApp, ref, reactive, computed, onMounted } = Vue;

// 审计动作编码 → 界面文案。取值与后端 org.luo.system.constant.AuditAction 一一对应：
// 后端加动作时这里要同步补，否则新动作会在界面上显示成原始编码（不会报错，但可读性掉档）。
const AUDIT_ACTIONS = [
    { value: 'CREATE_USER', label: '新增用户' },
    { value: 'UPDATE_USER', label: '修改用户' },
    { value: 'DELETE_USER', label: '删除用户' },
    { value: 'UPDATE_PASSWORD', label: '重置口令' },
    { value: 'CREATE_ROLE', label: '新增角色' },
    { value: 'UPDATE_ROLE', label: '修改角色' },
    { value: 'DELETE_ROLE', label: '删除角色' }
];
const AUDIT_LABELS = Object.fromEntries(AUDIT_ACTIONS.map(a => [a.value, a.label]));

// ==================== API 封装（与 edu.js 同一套） ====================
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

/** 统一的「发请求 → 解包 / 报错」：后端失败一律是 {code,message}，直接弹出来。 */
async function request(url, options, alertTitle) {
    let resp;
    try {
        resp = await apiFetch(url, options);
    } catch (e) {
        await askAlert('网络请求失败，请检查服务是否可用。', { title: alertTitle || '请求失败' });
        return null;
    }
    let body = {};
    try { body = await resp.json(); } catch (e) { body = {}; }
    if (!resp.ok) {
        await askAlert(body.message || `请求失败（HTTP ${resp.status}）`, { title: alertTitle || '请求失败' });
        return null;
    }
    return body.data;
}

createApp({
    setup() {
        // 顶栏登录用户区（用户名 + 下拉菜单）不在这里 —— 整块由 js/auth.js 渲染到 data-auth-nav
        // 挂载点上，四页共用同一份实现，本页不再持有登录态判断或退出逻辑。

        const tab = ref('user');

        // ==================== 角色（用户表单的角色多选 + 筛选下拉共用） ====================
        const roles = ref([]);

        // ==================== 用户列表 ====================
        const userRows = ref([]);
        const userPage = ref(1);
        const userSize = ref(10);
        const userTotal = ref(0);
        const userPages = ref(0);
        const userLoading = ref(false);
        const userQuery = reactive({ keyword: '', status: '', roleId: '' });

        // ==================== 角色列表 ====================
        const roleRows = ref([]);
        const rolePage = ref(1);
        const roleSize = ref(10);
        const roleTotal = ref(0);
        const rolePages = ref(0);
        const roleLoading = ref(false);
        const roleQuery = reactive({ keyword: '' });

        // ==================== 审计列表 ====================
        const auditRows = ref([]);
        const auditPage = ref(1);
        const auditSize = ref(20);
        const auditTotal = ref(0);
        const auditPages = ref(0);
        const auditLoading = ref(false);
        const auditQuery = reactive({ action: '' });
        const auditActions = AUDIT_ACTIONS;

        // ==================== 弹窗 ====================
        const userModal = reactive({
            open: false, mode: 'create', saving: false, error: '',
            form: { id: null, username: '', password: '', nickname: '', email: '', status: 1, roleIds: [] }
        });
        const pwdModal = reactive({ open: false, saving: false, error: '', id: null, username: '', password: '' });
        const roleModal = reactive({
            open: false, mode: 'create', saving: false, error: '',
            form: { id: null, code: '', name: '', description: '' }
        });

        // 自绘确认 / 提示（原生 confirm/alert 由系统绘制，与页面两个风格）
        const dialog = reactive({ open: false, type: 'alert', title: '', message: '', okText: '确定', danger: false });
        let dialogResolve = null;

        function openDialog(cfg) {
            dialog.open = true;
            dialog.type = cfg.type || 'alert';
            dialog.title = cfg.title || '';
            dialog.message = cfg.message || '';
            dialog.okText = cfg.okText || '确定';
            dialog.danger = !!cfg.danger;
            return new Promise((resolve) => { dialogResolve = resolve; });
        }
        function closeDialog(ok) {
            dialog.open = false;
            const resolve = dialogResolve;
            dialogResolve = null;
            if (resolve) resolve(!!ok);
        }
        function askConfirm(message, opts) {
            return openDialog(Object.assign({ type: 'confirm', message: message, okText: '确定' }, opts || {}));
        }
        function askAlert(message, opts) {
            return openDialog(Object.assign({ type: 'alert', message: message, okText: '知道了' }, opts || {}));
        }

        // ==================== 展示辅助 ====================
        /** 序号：跨页连续（第 2 页第 1 行 = 11） */
        function rowNo(index, page, size) {
            return (Number(page) - 1) * Number(size) + index + 1;
        }
        /** LocalDateTime（2026-09-22T11:43:14）→ 2026-09-22 11:43 */
        function fmtTime(value) {
            if (!value) return '—';
            return String(value).replace('T', ' ').slice(0, 16);
        }
        /** 动作编码 → 文案；未登记的新编码原样显示（宁可露出编码，也别显示成空白）。 */
        function auditActionLabel(action) {
            return AUDIT_LABELS[action] || action || '—';
        }

        // ==================== 加载 ====================
        async function loadRoles() {
            const r = await apiFetch('/api/role/list');
            if (!r.ok) {
                await askAlert('角色列表加载失败，请确认已执行 sql/system.sql 建表。', { title: '加载失败' });
                return;
            }
            const body = await r.json();
            roles.value = body.data || [];
        }

        async function loadUsers(page) {
            const target = Number(page) || 1;
            userLoading.value = true;
            const body = { page: target, size: userSize.value };
            if (userQuery.keyword.trim()) body.keyword = userQuery.keyword.trim();
            if (userQuery.status !== '') body.status = Number(userQuery.status);
            if (userQuery.roleId !== '') body.roleId = Number(userQuery.roleId);
            const data = await request('/api/user/page', { method: 'POST', body: body }, '用户列表加载失败');
            userLoading.value = false;
            if (!data) return;
            userRows.value = data.records || [];
            userTotal.value = data.total || 0;
            userPages.value = data.pages || 0;
            userPage.value = data.page || target;
        }

        async function loadRolesPage(page) {
            const target = Number(page) || 1;
            roleLoading.value = true;
            const body = { page: target, size: roleSize.value };
            if (roleQuery.keyword.trim()) body.keyword = roleQuery.keyword.trim();
            const data = await request('/api/role/page', { method: 'POST', body: body }, '角色列表加载失败');
            roleLoading.value = false;
            if (!data) return;
            roleRows.value = data.records || [];
            roleTotal.value = data.total || 0;
            rolePages.value = data.pages || 0;
            rolePage.value = data.page || target;
        }

        async function loadAudit(page) {
            const target = Number(page) || 1;
            auditLoading.value = true;
            // 审计接口是 GET + query（分页/筛选都走参数），与用户/角色的 POST body 口径不同
            const qs = new URLSearchParams({ page: String(target), size: String(auditSize.value) });
            if (auditQuery.action) qs.set('action', auditQuery.action);
            const data = await request('/api/audit/page?' + qs.toString(), { method: 'GET' }, '审计记录加载失败');
            auditLoading.value = false;
            if (!data) return;
            auditRows.value = data.records || [];
            auditTotal.value = data.total || 0;
            auditPages.value = data.pages || 0;
            auditPage.value = data.page || target;
        }

        function resetUserQuery() {
            userQuery.keyword = '';
            userQuery.status = '';
            userQuery.roleId = '';
            loadUsers(1);
        }
        function resetRoleQuery() {
            roleQuery.keyword = '';
            loadRolesPage(1);
        }
        function resetAuditQuery() {
            auditQuery.action = '';
            loadAudit(1);
        }
        /** 切视图：只加载目标视图，避免每次切换都打两组请求 */
        function switchTab(name) {
            tab.value = name;
            if (name === 'user') {
                loadRoles();
                loadUsers(userPage.value);
            } else if (name === 'role') {
                loadRolesPage(rolePage.value);
            } else {
                loadAudit(auditPage.value);
            }
        }

        // ==================== 用户：新增 / 编辑 ====================
        function openUserCreate() {
            if (!roles.value.length) {
                askAlert('还没有任何角色，请先在「角色管理」里创建角色。', { title: '无法新增用户' });
                return;
            }
            userModal.mode = 'create';
            userModal.error = '';
            userModal.form = { id: null, username: '', password: '', nickname: '', email: '', status: 1, roleIds: [] };
            userModal.open = true;
        }

        function openUserEdit(row) {
            userModal.mode = 'edit';
            userModal.error = '';
            userModal.form = {
                id: row.id,
                username: row.username,
                password: '',
                nickname: row.nickname || '',
                email: row.email || '',
                status: row.status === 0 ? 0 : 1,
                // 拷贝一份：直接引用 row.roleIds 会让 checkbox 改动立刻反映到表格行上
                roleIds: Array.isArray(row.roleIds) ? row.roleIds.slice() : []
            };
            userModal.open = true;
        }

        function closeUserModal() {
            userModal.open = false;
        }

        async function saveUser() {
            const form = userModal.form;
            let error = '';
            if (userModal.mode === 'create') {
                if (!form.username.trim()) error = '请填写登录名';
                else if (!form.password) error = '请填写口令';
                else if (form.password.length < 6 || form.password.length > 64) error = '口令长度需为 6~64 位';
            }
            if (!error && form.email && form.email.indexOf('@') < 0) error = '邮箱格式不正确';
            if (error) {
                userModal.error = error;
                return;
            }
            userModal.error = '';
            userModal.saving = true;

            const url = userModal.mode === 'create' ? '/api/user/save' : '/api/user/update';
            const method = userModal.mode === 'create' ? 'POST' : 'PUT';
            const body = userModal.mode === 'create'
                ? {
                    username: form.username.trim(), password: form.password,
                    nickname: form.nickname.trim(), email: form.email.trim(),
                    status: form.status, roleIds: form.roleIds
                }
                : {
                    id: form.id, nickname: form.nickname.trim(), email: form.email.trim(),
                    status: form.status, roleIds: form.roleIds
                };

            const data = await request(url, { method: method, body: body }, '保存失败');
            userModal.saving = false;
            if (data === null) return;
            userModal.open = false;
            await loadUsers(userPage.value);
        }

        // ==================== 用户：重置口令 / 删除 ====================
        function openResetPassword(row) {
            pwdModal.id = row.id;
            pwdModal.username = row.username;
            pwdModal.password = '';
            pwdModal.error = '';
            pwdModal.saving = false;
            pwdModal.open = true;
        }

        function closePwdModal() {
            pwdModal.open = false;
        }

        async function submitPassword() {
            if (!pwdModal.password || pwdModal.password.length < 6 || pwdModal.password.length > 64) {
                pwdModal.error = '口令长度需为 6~64 位';
                return;
            }
            pwdModal.error = '';
            pwdModal.saving = true;
            const data = await request(`/api/user/${pwdModal.id}/password`,
                { method: 'PUT', body: { password: pwdModal.password } }, '重置口令失败');
            pwdModal.saving = false;
            if (data === null) return;
            pwdModal.open = false;
            await askAlert(`已重置「${pwdModal.username}」的口令。该账号此前签发的 token 仍然有效，直到自然过期。`,
                { title: '重置成功' });
        }

        async function removeUser(row) {
            const ok = await askConfirm(`确认删除用户「${row.username}」？此操作不可恢复。`,
                { title: '删除确认', okText: '删除', danger: true });
            if (!ok) return;
            const data = await request(`/api/user/delete/${row.id}`, { method: 'DELETE' }, '删除失败');
            if (data === null) return;
            await loadUsers(userPage.value);
        }

        // ==================== 角色：新增 / 编辑 / 删除 ====================
        function openRoleCreate() {
            roleModal.mode = 'create';
            roleModal.error = '';
            roleModal.form = { id: null, code: '', name: '', description: '' };
            roleModal.open = true;
        }

        function openRoleEdit(row) {
            roleModal.mode = 'edit';
            roleModal.error = '';
            roleModal.form = { id: row.id, code: row.code, name: row.name, description: row.description || '' };
            roleModal.open = true;
        }

        function closeRoleModal() {
            roleModal.open = false;
        }

        async function saveRole() {
            const form = roleModal.form;
            if (!form.code.trim()) {
                roleModal.error = '请填写角色编码';
                return;
            }
            if (!form.name.trim()) {
                roleModal.error = '请填写角色名称';
                return;
            }
            roleModal.error = '';
            roleModal.saving = true;

            const url = roleModal.mode === 'create' ? '/api/role/save' : '/api/role/update';
            const method = roleModal.mode === 'create' ? 'POST' : 'PUT';
            const body = roleModal.mode === 'create'
                ? { code: form.code.trim(), name: form.name.trim(), description: form.description.trim() }
                : { id: form.id, name: form.name.trim(), description: form.description.trim() };

            const data = await request(url, { method: method, body: body }, '保存失败');
            roleModal.saving = false;
            if (data === null) return;
            roleModal.open = false;
            await loadRolesPage(rolePage.value);
            await loadRoles();
        }

        async function removeRole(row) {
            const ok = await askConfirm(`确认删除角色「${row.name}（${row.code}）」？已分配给用户的角色无法删除。`,
                { title: '删除确认', okText: '删除', danger: true });
            if (!ok) return;
            const data = await request(`/api/role/delete/${row.id}`, { method: 'DELETE' }, '删除失败');
            if (data === null) return;
            await loadRolesPage(rolePage.value);
            await loadRoles();
        }

        // ==================== 初始化 ====================
        onMounted(async () => {
            const tip = document.getElementById('boot-tip');
            if (tip) tip.remove();
            if (!window.Auth || !Auth.hasRole('ADMIN')) {
                await askAlert('当前账号没有用户管理权限（需要 ADMIN 角色）。', { title: '无权限' });
                window.location.href = '/chat.html';
                return;
            }
            await loadRoles();
            await loadUsers(1);
        });

        return {
            tab, switchTab,
            roles,
            userRows, userPage, userSize, userTotal, userPages, userLoading, userQuery, loadUsers, resetUserQuery,
            roleRows, rolePage, roleSize, roleTotal, rolePages, roleLoading, roleQuery,
            loadRolesPage, resetRoleQuery,
            auditRows, auditPage, auditSize, auditTotal, auditPages, auditLoading, auditQuery, auditActions,
            loadAudit, resetAuditQuery, auditActionLabel,
            rowNo, fmtTime,
            userModal, openUserCreate, openUserEdit, closeUserModal, saveUser,
            pwdModal, openResetPassword, closePwdModal, submitPassword,
            removeUser,
            roleModal, openRoleCreate, openRoleEdit, closeRoleModal, saveRole, removeRole,
            dialog, closeDialog
        };
    }
}).mount('#app');
