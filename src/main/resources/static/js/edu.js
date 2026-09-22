// 教务系统前端（/edu.html）。Vue 由 /js/lib/vue.global.prod.js 提供。
// 复用 AI 对话页同一套 X-Api-Key（localStorage 键 my_agent_api_key），走 /api/edu/** 接口。
if (typeof Vue === 'undefined') {
    console.error('[启动失败] Vue 未加载：请确认 /js/lib/vue.global.prod.js 可访问（HTTP 200）。');
    throw new Error('Vue 未加载，前端无法启动');
}
const { createApp, ref, reactive, computed, onMounted, onUnmounted, nextTick } = Vue;

// ==================== API 封装（自动附加 X-Api-Key） ====================
const API_KEY_STORAGE = 'my_agent_api_key';
function getApiKey() {
    try { return localStorage.getItem(API_KEY_STORAGE) || ''; } catch (e) { return ''; }
}
function apiFetch(url, options) {
    const opts = Object.assign({}, options || {});
    const headers = Object.assign({}, opts.headers || {});
    const key = getApiKey();
    if (key) headers['X-Api-Key'] = key;
    if (opts.body && typeof opts.body === 'object') {
        headers['Content-Type'] = 'application/json';
        opts.body = JSON.stringify(opts.body);
    }
    opts.headers = headers;
    return window.fetch(url, opts);
}

// ==================== 自绘下拉组件（替代原生 select） ====================
// 原生 select 的展开弹层由操作系统绘制，option 自定义色会走「先建原生弹层再套色」路径，
// 点开闪一帧。故复用 AI 对话页的 UiSelect：页内自绘弹层 Teleport 到 body，配色/圆角/动画全可控。
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

        const currentIndex = computed(() => props.options.findIndex(o => o.value === props.modelValue));
        const currentLabel = computed(() => {
            const o = props.options[currentIndex.value];
            return o ? o.label : '';
        });

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

// ==================== 菜单分组（业务视角，非表结构平铺） ====================
// 三种视图：dashboard（看板）、table（单表 CRUD）、query（关联查询）
const menuGroups = [
    { title: '总览', items: [
        { key: 'dashboard', label: '数据看板', view: 'dashboard' },
    ] },
    { title: '基础档案', items: [
        { key: 'subject',   label: '科目', view: 'table' },
        { key: 'teacher',   label: '老师', view: 'table' },
        { key: 'class',     label: '班级', view: 'table' },
        { key: 'student',   label: '学生', view: 'table' },
        { key: 'semester',  label: '学期', view: 'table' },
    ] },
    { title: '教学安排', items: [
        { key: 'course',      label: '课程',   view: 'table' },
        { key: 'arrangement', label: '排课',   view: 'table' },
        { key: 'timetable',   label: '班级课表', view: 'query' },
    ] },
    { title: '考试与成绩', items: [
        { key: 'exam',         label: '考试安排',   view: 'table' },
        { key: 'score',        label: '成绩录入',   view: 'table' },
        { key: 'score-detail', label: '成绩明细',   view: 'query' },
        { key: 'score-stats',  label: '成绩统计',   view: 'query' },
        { key: 'schedule',     label: '考试日程',   view: 'query' },
    ] },
];

// ==================== 视图记忆（刷新回到当前页） ====================
// 视图同步进地址栏（?view=table:student）+ localStorage 兜底，
// 避免刷新或下次打开都落回总览页，同时保留「复制链接直达某页」的能力。
const VIEW_STORAGE = 'my_agent_edu_view';
const GROUP_STORAGE = 'my_agent_edu_groups';

function readStorage(key) {
    try { return localStorage.getItem(key); } catch (e) { return null; }
}
function writeStorage(key, val) {
    try { localStorage.setItem(key, val); } catch (e) { /* 隐私模式等场景忽略 */ }
}

// 菜单项 → URL 参数值：dashboard | table:xxx | query:xxx
function viewParam(item) {
    return item.view === 'dashboard' ? 'dashboard' : item.view + ':' + item.key;
}
// URL 参数值 → 菜单项，非法值返回 null（交给上层回落）
function findMenuItem(raw) {
    if (!raw) return null;
    const [kind, key] = raw.split(':');
    for (const g of menuGroups) {
        for (const it of g.items) {
            if (kind === 'dashboard' && it.view === 'dashboard') return it;
            if ((kind === 'table' || kind === 'query') && it.view === kind && it.key === key) return it;
        }
    }
    return null;
}

// 表单里的固定枚举下拉（非字典外键），如性别。
const GENDER_OPTIONS = [ { value: '男', label: '男' }, { value: '女', label: '女' } ];
const TITLE_OPTIONS = [
    { value: '特级教师', label: '特级教师' }, { value: '高级教师', label: '高级教师' },
    { value: '中级教师', label: '中级教师' }, { value: '初级教师', label: '初级教师' },
];
// 星期固定枚举下拉（班级课表筛选）
const WEEKDAY_OPTIONS = [
    { value: 1, label: '周一' }, { value: 2, label: '周二' }, { value: 3, label: '周三' },
    { value: 4, label: '周四' }, { value: 5, label: '周五' }, { value: 6, label: '周六' },
    { value: 7, label: '周日' },
];

// 单表元数据：columns 用于表格展示，editable 用于新增/编辑表单。
// 列字段说明：
//   search    —— 表格上方的搜索条件：key = 后端 DTO 字段名；带 fkOptions 则渲染下拉，否则渲染输入框
//   fkOptions —— 下拉选项来源，值是 tableMeta 的键（如 'class'）。选项文案由该表 GET /list 返回，前端不自己拼
//   options   —— 固定枚举下拉 {value,label}[]（如性别、职称），不查接口
//   numeric   —— 提交时转 Number
// 列的 key 直连接口返回的字段：需要显示成名称的外键，后端已在 VO 里 join 好了（如 headTeacherName / className）
const tableMeta = {
    subject: {
        label: '科目', endpoint: 'subjects',
        search: [ { key: 'name', label: '科目名称', placeholder: '按名称模糊搜索' } ],
        columns: [ { key: 'name', label: '科目名称' }, { key: 'code', label: '编码' } ],
        editable: [ { key: 'name', label: '科目名称' }, { key: 'code', label: '编码' } ],
    },
    teacher: {
        label: '老师', endpoint: 'teachers',
        search: [
            { key: 'name', label: '姓名', placeholder: '按姓名模糊搜索' },
            { key: 'subjectId', label: '主教学科', fkOptions: 'subject', allLabel: '全部学科' },
        ],
        columns: [
            { key: 'name', label: '姓名' }, { key: 'gender', label: '性别' },
            { key: 'subjectName', label: '主教学科' }, { key: 'title', label: '职称' }, { key: 'phone', label: '电话' } ],
        editable: [
            { key: 'name', label: '姓名' }, { key: 'gender', label: '性别', options: GENDER_OPTIONS },
            { key: 'subjectId', label: '主教学科', fkOptions: 'subject' },
            { key: 'title', label: '职称', options: TITLE_OPTIONS }, { key: 'phone', label: '电话' } ],
    },
    class: {
        label: '班级', endpoint: 'classes',
        search: [
            { key: 'name', label: '班级名称', placeholder: '按名称模糊搜索' },
            { key: 'headTeacherId', label: '班主任', fkOptions: 'teacher', allLabel: '全部老师' },
        ],
        columns: [
            { key: 'name', label: '班级名称' }, { key: 'grade', label: '年级' },
            { key: 'headTeacherName', label: '班主任' } ],
        editable: [
            { key: 'name', label: '班级名称' }, { key: 'grade', label: '年级', numeric: true },
            { key: 'headTeacherId', label: '班主任', fkOptions: 'teacher' } ],
    },
    student: {
        label: '学生', endpoint: 'students',
        search: [
            { key: 'name', label: '姓名', placeholder: '按姓名模糊搜索' },
            { key: 'classId', label: '班级', fkOptions: 'class', allLabel: '全部班级' },
        ],
        columns: [
            { key: 'name', label: '姓名' }, { key: 'gender', label: '性别' },
            { key: 'className', label: '班级' }, { key: 'studentNo', label: '学号' },
            { key: 'enrollYear', label: '入学年份' }, { key: 'isBoarding', label: '寄宿' } ],
        editable: [
            { key: 'name', label: '姓名' }, { key: 'gender', label: '性别', options: GENDER_OPTIONS },
            { key: 'classId', label: '班级', fkOptions: 'class' },
            { key: 'birthDate', label: '出生日期' }, { key: 'studentNo', label: '学号' },
            { key: 'enrollYear', label: '入学年份', numeric: true }, { key: 'isBoarding', label: '寄宿(0走读/1寄宿)', numeric: true },
            { key: 'parentName', label: '家长姓名' }, { key: 'parentPhone', label: '家长电话' },
            { key: 'address', label: '家庭住址' }, { key: 'graduationTo', label: '毕业去向' } ],
    },
    semester: {
        label: '学期', endpoint: 'semesters',
        search: [ { key: 'name', label: '学期名称', placeholder: '如 2025-2026-1' } ],
        columns: [ { key: 'name', label: '学期名称' },
            { key: 'startDate', label: '开始日期' }, { key: 'endDate', label: '结束日期' } ],
        editable: [ { key: 'name', label: '学期名称(如2025-2026-1)' },
            { key: 'startDate', label: '开始日期' }, { key: 'endDate', label: '结束日期' } ],
    },
    period: {
        label: '节次', endpoint: 'periods',
        search: [ { key: 'periodNo', label: '节次序号', placeholder: '按序号精确匹配' } ],
        columns: [ { key: 'periodNo', label: '节次序号' },
            { key: 'startTime', label: '开始时间' }, { key: 'endTime', label: '结束时间' } ],
        editable: [ { key: 'periodNo', label: '节次序号(1-8)', numeric: true },
            { key: 'startTime', label: '开始时间(HH:MM:SS)' }, { key: 'endTime', label: '结束时间(HH:MM:SS)' } ],
    },
    course: {
        label: '课程', endpoint: 'courses',
        search: [
            { key: 'classId', label: '班级', fkOptions: 'class', allLabel: '全部班级' },
            { key: 'subjectId', label: '科目', fkOptions: 'subject', allLabel: '全部科目' },
        ],
        columns: [
            { key: 'className', label: '班级' }, { key: 'subjectName', label: '科目' },
            { key: 'teacherName', label: '老师' }, { key: 'semesterName', label: '学期' } ],
        editable: [
            { key: 'subjectId', label: '科目', fkOptions: 'subject' }, { key: 'teacherId', label: '老师', fkOptions: 'teacher' },
            { key: 'classId', label: '班级', fkOptions: 'class' }, { key: 'semesterId', label: '学期', fkOptions: 'semester' } ],
    },
    arrangement: {
        label: '排课', endpoint: 'arrangements',
        search: [
            { key: 'classId', label: '班级', fkOptions: 'class', allLabel: '全部班级' },
            { key: 'teacherId', label: '老师', fkOptions: 'teacher', allLabel: '全部老师' },
        ],
        columns: [
            { key: 'courseLabel', label: '课程' },
            { key: 'dayOfWeek', label: '星期' }, { key: 'startPeriodLabel', label: '开始节次' },
            { key: 'endPeriodLabel', label: '结束节次' }, { key: 'classroom', label: '教室' } ],
        editable: [
            { key: 'courseId', label: '课程', fkOptions: 'course' }, { key: 'dayOfWeek', label: '星期(1-7)', numeric: true },
            { key: 'startPeriodId', label: '开始节次', fkOptions: 'period' },
            { key: 'endPeriodId', label: '结束节次', fkOptions: 'period' },
            { key: 'classroom', label: '教室' } ],
    },
    exam: {
        label: '考试', endpoint: 'exams',
        search: [
            { key: 'classId', label: '班级', fkOptions: 'class', allLabel: '全部班级' },
            { key: 'subjectId', label: '科目', fkOptions: 'subject', allLabel: '全部科目' },
            { key: 'semesterId', label: '学期', fkOptions: 'semester', allLabel: '全部学期' },
        ],
        columns: [
            { key: 'className', label: '班级' }, { key: 'subjectName', label: '科目' },
            { key: 'semesterName', label: '学期' }, { key: 'examDate', label: '考试日期' }, { key: 'examTime', label: '考试时间' } ],
        editable: [
            { key: 'semesterId', label: '学期', fkOptions: 'semester' }, { key: 'classId', label: '班级', fkOptions: 'class' },
            { key: 'subjectId', label: '科目', fkOptions: 'subject' },
            { key: 'examDate', label: '考试日期' }, { key: 'examTime', label: '考试时间' } ],
    },
    score: {
        label: '成绩', endpoint: 'scores',
        search: [
            { key: 'classId', label: '班级', fkOptions: 'class', allLabel: '全部班级' },
            { key: 'subjectId', label: '科目', fkOptions: 'subject', allLabel: '全部科目' },
        ],
        columns: [
            { key: 'studentName', label: '学生' }, { key: 'className', label: '班级' },
            { key: 'subjectName', label: '科目' }, { key: 'examDate', label: '考试日期' }, { key: 'score', label: '分数' } ],
        editable: [
            { key: 'studentId', label: '学生', fkOptions: 'student' }, { key: 'examId', label: '考试', fkOptions: 'exam' },
            { key: 'score', label: '分数', numeric: true } ],
    },
};

// 关联查询元数据（列 key 对应 XML 里 AS 的驼峰别名）
// search 与 tableMeta.search 同形：key = 后端参数名；fkOptions = 某表 /list 下拉；
// options = 固定枚举下拉；都没有则渲染输入框。查询/重置由共用的一套逻辑处理。
const queryMeta = {
    'score-detail': {
        label: '成绩明细', endpoint: 'score-detail',
        columns: ['studentName', 'studentNo', 'className', 'subjectName', 'semesterName', 'examDate', 'examTime', 'score'],
        search: [
            { key: 'classId', label: '班级', fkOptions: 'class', allLabel: '全部班级' },
            { key: 'subjectId', label: '科目', fkOptions: 'subject', allLabel: '全部科目' },
            { key: 'semesterId', label: '学期', fkOptions: 'semester', allLabel: '全部学期' },
            { key: 'keyword', label: '学生', placeholder: '按姓名或学号搜索' },
        ],
    },
    'score-stats': {
        label: '成绩统计', endpoint: 'score-stats',
        columns: ['className', 'subjectName', 'semesterName', 'examDate', 'studentCount', 'avgScore', 'maxScore', 'minScore'],
        search: [
            { key: 'classId', label: '班级', fkOptions: 'class', allLabel: '全部班级' },
            { key: 'subjectId', label: '科目', fkOptions: 'subject', allLabel: '全部科目' },
            { key: 'semesterId', label: '学期', fkOptions: 'semester', allLabel: '全部学期' },
        ],
    },
    'timetable': {
        label: '班级课表', endpoint: 'timetable',
        columns: ['className', 'subjectName', 'teacherName', 'dayOfWeek', 'startPeriod', 'startTime', 'endPeriod', 'endTime', 'classroom'],
        search: [
            { key: 'classId', label: '班级', fkOptions: 'class', allLabel: '全部班级' },
            { key: 'teacherId', label: '老师', fkOptions: 'teacher', allLabel: '全部老师' },
            { key: 'dayOfWeek', label: '星期', options: WEEKDAY_OPTIONS, allLabel: '全部星期' },
        ],
    },
    'schedule': {
        label: '考试日程', endpoint: 'schedule',
        columns: ['className', 'subjectName', 'semesterName', 'examDate', 'examTime'],
        search: [
            { key: 'classId', label: '班级', fkOptions: 'class', allLabel: '全部班级' },
            { key: 'subjectId', label: '科目', fkOptions: 'subject', allLabel: '全部科目' },
            { key: 'semesterId', label: '学期', fkOptions: 'semester', allLabel: '全部学期' },
        ],
    },
};

// 关联查询列的中文表头
const queryColumnLabels = {
    studentName: '学生', studentNo: '学号', className: '班级', subjectName: '科目',
    semesterName: '学期', examDate: '考试日期', examTime: '考试时间', score: '分数',
    studentCount: '人数', avgScore: '平均分', maxScore: '最高分', minScore: '最低分',
    teacherName: '老师', dayOfWeek: '星期', startPeriod: '开始节次', startTime: '开始时间',
    endPeriod: '结束节次', endTime: '结束时间', classroom: '教室',
};

// 星期几中文
const WEEK_NAMES = { 1: '周一', 2: '周二', 3: '周三', 4: '周四', 5: '周五', 6: '周六', 7: '周日' };

// ==================== Vue 应用 ====================
createApp({
    setup() {
        // current.view: 'dashboard' | 'table' | 'query'
        const current = reactive({ view: 'dashboard', table: null, query: null });

        // 侧边栏二级目录折叠状态：默认全收起，沿用上次的展开情况
        const openGroups = reactive({});
        try {
            const saved = JSON.parse(readStorage(GROUP_STORAGE) || '{}');
            for (const k of Object.keys(saved)) openGroups[k] = saved[k] === true;
        } catch (e) { /* 存的内容坏了就按默认全收起 */ }
        function saveGroups() { writeStorage(GROUP_STORAGE, JSON.stringify(openGroups)); }
        function toggleGroup(g) { openGroups[g.title] = !openGroups[g.title]; saveGroups(); }
        function isGroupOpen(g) { return openGroups[g.title] === true; }
        // 恢复视图时把所在分组展开，否则菜单里看不到当前高亮项
        function revealGroup(item) {
            const g = menuGroups.find(gr => gr.items.indexOf(item) >= 0);
            if (g && !openGroups[g.title]) { openGroups[g.title] = true; saveGroups(); }
        }
        const rows = ref([]);
        const page = ref(1);
        const pageSize = ref(10);
        const total = ref(0);
        const totalPages = ref(0);

        const queryRows = ref([]);

        // 下拉选项缓存：{ 表key: [{value,label}] }。按需从各表自己的 GET /list 取，文案后端已拼好
        const optionsCache = reactive({});
        const optionsLoading = {};

        // 看板数据
        const dash = ref(null);

        const form = reactive({ open: false, id: null, data: {}, saving: false });

        // ==================== 派生 ====================
        const currentMeta = computed(() => current.table ? tableMeta[current.table] : null);
        const currentLabel = computed(() => {
            if (current.view === 'dashboard') return '数据看板';
            if (current.view === 'table') return currentMeta.value ? currentMeta.value.label : '';
            const q = queryMeta[current.query];
            return q ? q.label : '';
        });
        const currentColumns = computed(() => currentMeta.value ? currentMeta.value.columns : []);
        const editableColumns = computed(() => currentMeta.value ? currentMeta.value.editable : []);

        const queryMetaObj = computed(() => current.query ? queryMeta[current.query] : null);
        const queryColumns = computed(() => queryMetaObj.value ? queryMetaObj.value.columns : []);

        // 表单里下拉字段的选项：固定枚举（col.options）优先，否则取该表 /list 的结果
        function fieldOptions(col) {
            if (col.options) return col.options;
            return (col.fkOptions && optionsCache[col.fkOptions]) || [];
        }

        // 下拉选项带「全部」占位（值为 null 表示不筛）
        function withAll(allLabel, tableKey) {
            return [{ value: null, label: allLabel }, ...(optionsCache[tableKey] || [])];
        }

        // 表格序号：按「当前页 + 每页条数」计算，首列展示（不再显示主键 ID）
        function rowNo(i) {
            return (page.value - 1) * Number(pageSize.value) + i + 1;
        }

        // 分页大小选项
        const pageSizeOptions = [
            { value: 10, label: '10 条/页' },
            { value: 20, label: '20 条/页' },
            { value: 50, label: '50 条/页' },
            { value: 100, label: '100 条/页' },
        ];

        // 跳页输入框（提交后清空）
        const jumpTo = ref('');

        // 表格视图与关联查询视图共用一套翻页控件，按当前视图分派到对应加载器
        function goPage(p) {
            return current.view === 'query' ? loadQuery(p) : loadPage(p);
        }

        // 跳页：非数字输入直接忽略并清空；越界向最近的边界收敛（不是报错）
        function doJump() {
            const raw = String(jumpTo.value == null ? '' : jumpTo.value).replace(/\D/g, '');
            jumpTo.value = '';
            if (!raw) return;
            const n = Math.min(Math.max(Number(raw), 1), totalPages.value || 1);
            if (n !== page.value) goPage(n);
        }

        // ==================== 搜索条件（表格页与关联查询页共用一套） ====================
        const searchForm = reactive({});
        // 字段声明按当前视图取：table 用当前表，query 用当前查询
        const searchFields = computed(() => {
            if (current.view === 'table') return (currentMeta.value && currentMeta.value.search) || [];
            if (current.view === 'query') return (queryMetaObj.value && queryMetaObj.value.search) || [];
            return [];
        });

        // 下拉型字段的选项（含「全部」占位，选中即清空该条件）
        function searchOptions(f) {
            if (f.options) return [{ value: null, label: f.allLabel || '全部' }, ...f.options];
            return withAll(f.allLabel || '全部', f.fkOptions);
        }

        // 已填的搜索条件（空值不下传；null 表示下拉里的「全部」）
        function collectSearch() {
            const cond = {};
            for (const f of searchFields.value) {
                const v = searchForm[f.key];
                if (v !== '' && v !== null && v !== undefined) cond[f.key] = v;
            }
            return cond;
        }

        // 清空当前视图的搜索字段（切表/切查询、点重置都走它）
        function clearSearch() {
            for (const f of searchFields.value) searchForm[f.key] = (f.fkOptions || f.options) ? null : '';
        }

        function doSearch() {
            return current.view === 'query' ? loadQuery(1) : loadPage(1);
        }

        function resetSearch() {
            clearSearch();
            doSearch();
        }

        // ==================== 格式化 ====================
        function fmtCell(v, col) {
            if (v === null || v === undefined || v === '') return '—';
            if (col.key === 'isBoarding') return v === 1 || v === '1' ? '寄宿' : '走读';
            if (col.key === 'dayOfWeek') return WEEK_NAMES[v] || v;
            return v;
        }
        function fmtQueryCell(v, key) {
            if (v === null || v === undefined || v === '') return '—';
            if (key === 'dayOfWeek') return WEEK_NAMES[v] || v;
            return v;
        }

        // ==================== 数据加载 ====================
        async function loadPage(p) {
            page.value = p;
            const meta = currentMeta.value;
            const cond = collectSearch();
            // 各表统一命令式：分页与筛选条件一起走 body
            const r = await apiFetch(`/api/edu/${meta.endpoint}/page`, {
                method: 'POST', body: Object.assign({ page: p, size: pageSize.value }, cond),
            });
            if (!r.ok) { const e = await r.json(); await askAlert(e.message || '加载失败', { title: '加载失败' }); return; }
            const res = await r.json();
            const data = res.data || {};
            rows.value = data.records || [];
            total.value = data.total || 0;
            totalPages.value = data.pages || 0;
            // 当前页可能因删除而越界（末页被删空）→ 回退到最后一页
            if (!rows.value.length && data.pages && p > data.pages) return loadPage(data.pages);
        }

        // 关联查询：四个查询都是分页接口，和单表页共用 page / pageSize 状态
        async function loadQuery(p) {
            page.value = p;
            const q = queryMetaObj.value;
            const r = await apiFetch(`/api/edu/${q.endpoint}?page=${p}&size=${pageSize.value}` +
                queryString(collectSearch(), '&'));
            if (!r.ok) { const e = await r.json().catch(() => ({})); await askAlert(e.message || '加载失败', { title: '加载失败' }); return; }
            const res = await r.json();
            const data = res.data || {};
            queryRows.value = data.records || [];
            total.value = data.total || 0;
            totalPages.value = data.pages || 0;
            // 与单表页一致：末页数据被删后回退，避免停在空页
            if (!queryRows.value.length && data.pages && p > data.pages) return loadQuery(data.pages);
        }

        // 查询参数串：值统一 encodeURIComponent，空值由调用方先过滤掉
        function queryString(cond, sep) {
            return Object.entries(cond || {})
                .map(([k, v]) => (sep || '') + k + '=' + encodeURIComponent(v)).join('');
        }

        async function loadDashboard() {
            const r = await apiFetch('/api/edu/dashboard');
            if (!r.ok) { const e = await r.json().catch(() => ({})); await askAlert(e.message || '看板数据加载失败', { title: '加载失败' }); return; }
            const res = await r.json();
            dash.value = res.data || null;
            renderCharts();
        }

        function renderCharts() {
            if (!dash.value) return;
            // 图表延迟到 DOM 就绪后渲染
            Vue.nextTick(() => {
                const el = document.getElementById('dash-grade-chart');
                if (el && window.echarts) {
                    const c = echarts.getInstanceByDom(el) || echarts.init(el);
                    const gradeKeys = Object.keys(dash.value.gradeDist || {});
                    c.setOption({
                        backgroundColor: 'transparent',
                        grid: { left: 40, right: 16, top: 20, bottom: 28 },
                        xAxis: { type: 'category', data: gradeKeys, axisLabel: { color: '#93a6c8' }, axisLine: { lineStyle: { color: '#26324a' } } },
                        yAxis: { type: 'value', axisLabel: { color: '#93a6c8' }, splitLine: { lineStyle: { color: '#1a2436' } } },
                        series: [{ type: 'bar', data: Object.values(dash.value.gradeDist || {}),
                            itemStyle: { color: '#5b8cff', borderRadius: [4, 4, 0, 0] }, barMaxWidth: 40 }],
                    });
                }
                const el2 = document.getElementById('dash-subject-chart');
                if (el2 && window.echarts) {
                    const c2 = echarts.getInstanceByDom(el2) || echarts.init(el2);
                    const keys = Object.keys(dash.value.subjectTeacher || {});
                    const vals = Object.values(dash.value.subjectTeacher || {});
                    c2.setOption({
                        backgroundColor: 'transparent',
                        tooltip: { trigger: 'item' },
                        series: [{
                            type: 'pie', radius: ['40%', '70%'],
                            data: keys.map((k, i) => ({ name: k, value: vals[i] })),
                            label: { color: '#93a6c8', fontSize: 12 },
                            itemStyle: { borderColor: '#0b1220', borderWidth: 2 },
                        }],
                    });
                }
            });
        }

        // 下拉选项按表懒加载：只拉当前视图用得到的那几张表，拉过就缓存
        async function ensureOptions(keys) {
            const need = (keys || []).filter(k => k && tableMeta[k] && !optionsCache[k] && !optionsLoading[k]);
            if (!need.length) return;
            await Promise.all(need.map(async k => {
                optionsLoading[k] = true;
                try {
                    const r = await apiFetch(`/api/edu/${tableMeta[k].endpoint}/list`);
                    if (!r.ok) return;
                    const res = await r.json();
                    optionsCache[k] = (res.data || []).map(o => ({ value: o.id, label: o.label }));
                } catch (e) {
                    /* 选项加载失败不阻塞主流程：下拉为空，列表照常可用 */
                } finally {
                    optionsLoading[k] = false;
                }
            }));
        }

        // ==================== 导航 ====================
        function switchView(item) {
            current.view = item.view;
            current.table = null;
            current.query = null;
            page.value = 1;
            if (item.view === 'table') {
                current.table = item.key;
                const meta = tableMeta[item.key];
                clearSearch();  // 切表清空搜索条件，避免残留到另一张表
                // 搜索条与表单用到的下拉先备好选项，再取数据
                ensureOptions([...(meta.search || []).map(f => f.fkOptions),
                    ...(meta.editable || []).map(c => c.fkOptions)]).then(() => loadPage(1));
            } else if (item.view === 'query') {
                current.query = item.key;
                clearSearch();  // 切查询同样清空条件
                // 查询条的下拉同样来自各表的 /list
                ensureOptions((queryMeta[item.key].search || []).map(f => f.fkOptions))
                    .then(() => loadQuery(1));
            } else {
                loadDashboard();
            }
            rememberView(item);
        }

        // 记住当前视图：地址栏同步（刷新即停在原页，链接可分享）+ localStorage 兜底
        function rememberView(item) {
            const param = viewParam(item);
            writeStorage(VIEW_STORAGE, param);
            try {
                const url = new URL(location.href);
                url.searchParams.set('view', param);
                history.replaceState(null, '', url);   // replaceState：不往后退历史里塞记录
            } catch (e) { /* 地址栏同步失败不影响页面本身 */ }
        }

        // 定位初始视图：URL 参数优先，其次上次停留的页面，最后回落总览
        function switchFromUrl() {
            let raw = null;
            try { raw = new URLSearchParams(location.search).get('view'); } catch (e) { /* ignore */ }
            const item = findMenuItem(raw) || findMenuItem(readStorage(VIEW_STORAGE)) || menuGroups[0].items[0];
            revealGroup(item);
            switchView(item);
        }

        function isActive(item) {
            if (item.view === 'dashboard') return current.view === 'dashboard';
            if (item.view === 'table') return current.view === 'table' && current.table === item.key;
            return current.view === 'query' && current.query === item.key;
        }

        // ==================== 新增/编辑 ====================
        // 表单里的外键下拉要选项先就位，避免打开弹窗后是空的
        function editableOptionKeys() {
            return editableColumns.value.map(c => c.fkOptions);
        }

        async function openCreate() {
            await ensureOptions(editableOptionKeys());
            form.id = null;
            form.data = {};
            for (const c of editableColumns.value) form.data[c.key] = '';
            form.open = true;
        }

        async function openEdit(row) {
            await ensureOptions(editableOptionKeys());
            form.id = row.id;
            form.data = {};
            for (const c of editableColumns.value) {
                let v = row[c.key];
                form.data[c.key] = (v === null || v === undefined) ? '' : String(v);
            }
            form.open = true;
        }

        async function saveForm() {
            form.saving = true;
            try {
                const body = {};
                for (const c of editableColumns.value) {
                    let v = form.data[c.key];
                    // 空值统一转 null；外键/数值字段转 Number；其余原样
                    if (v === '' || v === null || v === undefined) {
                        body[c.key] = null;
                    } else if (c.options) {
                        body[c.key] = v;            // 枚举下拉：原样字符串
                    } else if (c.fkOptions || c.numeric) {
                        body[c.key] = Number(v);
                    } else {
                        body[c.key] = v;
                    }
                }
                const meta = currentMeta.value;
                // 统一命令式：编辑 /update（id 在 body）、新增 /save
                const r = form.id
                    ? await apiFetch(`/api/edu/${meta.endpoint}/update`, { method: 'PUT', body: Object.assign({ id: form.id }, body) })
                    : await apiFetch(`/api/edu/${meta.endpoint}/save`, { method: 'POST', body });
                if (!r.ok) {
                    const err = await r.json();
                    await askAlert(err.message || '保存失败', { title: '保存失败' });
                    return;
                }
                form.open = false;
                await loadPage(page.value);
            } finally {
                form.saving = false;
            }
        }

        // ==================== 确认 / 提示弹窗（替代原生 confirm / alert，风格与页面一致） ====================
        const dialog = reactive({ open: false, mode: 'confirm', title: '', message: '', okText: '确定', danger: false });
        let dialogResolve = null;

        function openDialog(opts) {
            Object.assign(dialog, { open: true, mode: 'confirm', title: '提示', message: '', okText: '确定', danger: false }, opts);
            return new Promise(resolve => { dialogResolve = resolve; });
        }

        // ok=true 表示点了主按钮；点取消 / 遮罩 / 关闭都回 false
        function closeDialog(ok) {
            dialog.open = false;
            const resolve = dialogResolve;
            dialogResolve = null;
            if (resolve) resolve(!!ok);
        }

        function askConfirm(message, opts) {
            return openDialog(Object.assign({ mode: 'confirm', title: '确认操作', message }, opts || {}));
        }

        function askAlert(message, opts) {
            return openDialog(Object.assign({ mode: 'alert', title: '提示', message }, opts || {}));
        }

        // 记录的可读标识（取首列的可读值，如「陈静」），用于删除确认文案；取不到则回落主键
        function recordLabel(row) {
            const cols = currentColumns.value;
            const c = cols.length ? cols[0] : null;
            const v = c ? fmtCell(row[c.key], c) : '—';
            return v === '—' ? `ID=${row.id}` : v;
        }

        async function removeRow(row) {
            const ok = await askConfirm(`确认删除「${recordLabel(row)}」这条记录？此操作不可恢复。`,
                { title: '删除确认', okText: '删除', danger: true });
            if (!ok) return;
            const meta = currentMeta.value;
            const r = await apiFetch(`/api/edu/${meta.endpoint}/delete/${row.id}`, { method: 'DELETE' });
            if (!r.ok) {
                const err = await r.json();
                await askAlert(err.message || '删除失败', { title: '删除失败' });
                return;
            }
            await loadPage(page.value);
        }

        onMounted(() => {
            const tip = document.getElementById('boot-tip');
            if (tip) tip.remove();
            switchFromUrl();
        });

        return {
            menuGroups, queryColumnLabels,
            current, rows, page, pageSize, total, totalPages,
            queryRows, dash, form,
            currentLabel, currentColumns, editableColumns, queryColumns,
            fieldOptions, fmtCell, fmtQueryCell, rowNo,
            pageSizeOptions,
            openGroups, toggleGroup, isGroupOpen,
            loadPage, loadQuery, loadDashboard, goPage, jumpTo, doJump,
            searchForm, searchFields, searchOptions, doSearch, resetSearch,
            switchView, switchFromUrl, isActive, openCreate, openEdit, saveForm, removeRow,
            dialog, closeDialog,
        };
    }
}).component('ui-select', UiSelect).mount('#app');
