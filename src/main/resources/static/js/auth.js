/**
 * 登录态与接口鉴权（全站唯一一份，所有页面共用）。
 *
 * 用法：
 *   1) 页面 <head> 最前引入本文件，紧接着调用 Auth.requireLogin()
 *      —— 先锁住页面、向服务端确认登录态，未登录/已失效时弹整页登录框（不跳页），
 *         登录成功后自动重载当前页；确认有效才放行显示页面内容；
 *   2) 所有 /api/** 请求统一走 Auth.fetch()（自动附 Authorization；401 按服务端给的 reason
 *      区分「登录态真的失效」与「与登录态无关的 401」，只有前者才清本地登录态，两者都就地弹框提示）；
 *   3) 右上角鉴权区：给任意元素加 data-auth-nav 属性即自动渲染
 *      —— 未登录显示「登录」按钮，已登录显示用户名 + 用户管理（仅 ADMIN）+ 退出；
 *   4) 需要登录才能点的链接加 data-auth-required：未登录时就地弹框，登录后自动前往；
 *      已登录也先向服务端确认 token 仍有效再跳 —— 避免「先进去才发现要重新登录」；
 *   5) /login.html 只是个空壳，调 Auth.mountLoginPage() 以整页模式打开同一个弹框；
 *   6) 排查登录问题：控制台执行 Auth.diagnose() —— 打印本地凭证的声明与剩余有效期（不含 token
 *      本身），并说明服务端认不认它，reason 一眼可分 NO_TOKEN / BAD_SIGNATURE / EXPIRED。
 *
 * 登录 UI 只有一份：结构在 js/auth.js，样式在 css/auth.css，登录页与弹框共用。
 * token 存 localStorage（键 my_agent_token），与既有 X-Api-Key 的存法一致，不进页面源码。
 * 与 ApiKey 的关系：两者是独立闸门，服务端配置 APP_API_KEY 后，请求需同时带密钥与 token。
 */
(function () {
    'use strict';

    var TOKEN_KEY = 'my_agent_token';
    var USER_KEY = 'my_agent_user';
    var LOGIN_PAGE = '/login.html';
    var DEFAULT_TARGET = '/chat.html';
    var CSS_HREF = '/css/auth.css';
    /** 登录相关接口：它们的 401 由调用方自己展示，不触发弹框（否则登录失败会原地打转） */
    var AUTH_API_PREFIX = '/api/auth/';
    /** 拿不到服务端原因时的兜底提示（网关错误页等） */
    var EXPIRED_MESSAGE = '登录状态已失效，请重新登录';
    /**
     * 连不上服务端（fetch 本身失败）时的提示。
     * <p>
     * 必须与「登录已失效」分开：前者服务端根本没表态，本地 token 只是「可能有效」；
     * 后者才是服务端明确拒绝了这份凭证。混成一句话的代价是把一次网络抖动（服务端重启中）
     * 升级成一次强制重新登录 —— 正是「刚登录成功、一进页面又让登录」的典型来源。
     */
    var OFFLINE_MESSAGE = '无法连接服务端，请稍后重试';

    /**
     * 页面锁的样式，由 lockPage() 同步注入到 head（不等 auth.css 网络加载，否则会先闪页面）。
     *
     * 为什么是 body{visibility:hidden} 而不是「盖一层不透明遮罩」：遮罩只能盖住视觉，
     * 页面里的脚本照样跑、空壳内容照样进文档流；visibility 是让整页在确认登录态前就不参与渲染。
     * .auth-veil 必须显式放行 —— 登录框就挂在 body 里，visibility 会继承，
     * 不覆盖的话连框一起藏掉（visibility 与 display 不同，子元素可以覆盖继承值）。
     */
    var LOCK_CSS = [
        'html.auth-locked body{visibility:hidden!important;overflow:hidden!important}',
        'html.auth-locked .auth-veil{visibility:visible!important}',
        '.auth-lock{position:fixed;inset:0;z-index:1900;display:flex;align-items:center;justify-content:center;',
        'background:#05080f;color:#93a6c8;font-size:14px;line-height:1.6;',
        'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei UI","Microsoft YaHei",sans-serif}'
    ].join('');

    /** 当前打开的登录框（单例）与其参数 */
    var veil = null;
    var veilOptions = null;
    /** 页面级登录态校验的提示层（存在即表示 requireLogin 的校验还没出结果） */
    var gate = null;
    /** 本页是否声明过「必须登录才能用」（调过 requireLogin）——决定登录框被关掉后是留在本页还是回首页 */
    var pageGuarded = false;
    /** 受限跳转的校验是否进行中：校验是异步的，拦住重复点击 */
    var navigating = false;

    function read(key) {
        try { return localStorage.getItem(key) || ''; } catch (e) { return ''; }
    }

    function write(key, value) {
        try { localStorage.setItem(key, value); } catch (e) { /* 隐私模式下写不进：本次会话内存态照常工作 */ }
    }

    function remove(key) {
        try { localStorage.removeItem(key); } catch (e) { /* 同上 */ }
    }

    /** 只接受站内路径：协议相对地址（//evil.com）与外部 URL 一律拒绝，避免被构造成开放重定向。 */
    function safeTarget(target, fallback) {
        var value = typeof target === 'string' ? target : '';
        if (value.charAt(0) !== '/' || value.indexOf('//') === 0 || value.indexOf(LOGIN_PAGE) === 0) {
            return fallback || '';
        }
        return value;
    }

    /** /login.html?redirect= 的取值，缺省与控制台一致。 */
    function targetFromQuery() {
        var matched = /[?&]redirect=([^&]*)/.exec(window.location.search);
        return safeTarget(matched ? decodeURIComponent(matched[1]) : '', DEFAULT_TARGET);
    }

    /** 兜底引入样式（正常由页面显式 <link>，这里只在漏引时补上）。 */
    function ensureCss() {
        if (document.querySelector('link[data-auth-css]')) { return; }
        var link = document.createElement('link');
        link.rel = 'stylesheet';
        link.href = CSS_HREF;
        link.setAttribute('data-auth-css', '');
        document.head.appendChild(link);
    }

    function readJson(resp) {
        return resp.json().then(function (body) {
            return body || {};
        }, function () {
            // 非 JSON（如网关错误页）时给空对象，避免二次异常盖掉真实状态码
            return {};
        });
    }

    /**
     * 401 的 reason 里，**意味着这份登录凭证被服务端拒绝**的那些。
     * <ul>
     *   <li>{@code NO_TOKEN} 没带凭证 / {@code MALFORMED} 结构畸形 / {@code BAD_SIGNATURE} 签名不符；</li>
     *   <li>{@code MISSING_EXPIRY} 缺有效期 / {@code EXPIRED} 已过期 / {@code NO_SUBJECT} 缺主体；</li>
     *   <li>{@code USER_UNAVAILABLE} 账号被删或停用 —— 凭证本身没问题，但这个会话已不可用。</li>
     * </ul>
     * 与下面那个闸门的 reason 是互斥的：只有这里列的，才允许动本地登录态。
     */
    var TOKEN_FAILURE_REASONS = {
        NO_TOKEN: 1,
        MALFORMED: 1,
        BAD_SIGNATURE: 1,
        MISSING_EXPIRY: 1,
        EXPIRED: 1,
        NO_SUBJECT: 1,
        USER_UNAVAILABLE: 1
    };

    /**
     * 服务端「没带/带错 X-Api-Key」的 401：这是**服务级密钥**那一闸门拒绝的，与登录态无关。
     * 早期两处闸门都只回状态码，前端只能把这种 401 也当成「登录失效」—— 于是清掉刚登录拿到的
     * token、再弹一次登录框：表现就是「登录成功后随便点一下就要求重新登录」，怎么登都不对。
     */
    var API_KEY_FAILURE_REASON = 'API_KEY_REQUIRED';

    /** 非 401 的失败：服务端没否认登录态，只是这次没答上来（5xx、网关等）。 */
    var SERVER_ERROR_MESSAGE = '暂时无法确认登录状态（服务端异常），请稍后重试';

    /**
     * 读服务端给出的拒绝详情（401 响应体里的 reason + message），读不到就回落到兜底文案。
     *
     * 为什么不直接 resp.json()：响应体只能读一次，读掉之后调用方自己的解析会炸 —— 这里用 clone()。
     * 服务端把「未登录 / 已过期 / 凭证无效 / 账号停用 / 缺访问密钥」分开写清楚了（reason 字段），
     * 前端据此决定该不该动登录态、该说什么话；自己编一句笼统的话，只会让排查从「看一眼」变成「猜半天」。
     *
     * @return Promise<{reason: string, message: string}>（reason 可能为空串）
     */
    function readFailure(resp, fallback) {
        var failure = { reason: '', message: fallback };
        if (!resp || typeof resp.clone !== 'function') { return Promise.resolve(failure); }
        return resp.clone().json().then(function (body) {
            if (body && body.reason) { failure.reason = String(body.reason); }
            if (body && body.message) { failure.message = String(body.message); }
            return failure;
        }, function () {
            // 非 JSON（网关错误页等）：没有原因可用，交给调用方兜底
            return failure;
        });
    }

    /**
     * 该 401 是否意味着「本地登录态已失效」—— **只有服务端明确说这份凭证不行时才算**。
     *
     * reason 缺失时一律按「与登录态无关」处理：401 也可能来自 X-Api-Key 闸门或中间的反向代理，
     * 而清登录态是不可逆的（用户得重新登录）。宁可让用户多点一次登录框，也不能把一次
     * 「密钥配错 / 代理拦截」升级成「所有人都要重新登录」。
     */
    function isSessionInvalid(failure) {
        if (!failure) { return false; }
        // 服务级密钥那一闸门（X-Api-Key）的 401 与登录态无关：显式排除，别让它落进下面的白名单
        if (failure.reason === API_KEY_FAILURE_REASON) { return false; }
        return !!TOKEN_FAILURE_REASONS[failure.reason];
    }

    /** 登录态复核的进行中请求（多个 401 并发时共用一个，避免复核风暴） */
    var sessionCheck = null;

    /**
     * 复核登录态：请求一次 /api/auth/me，回答「这份凭证现在到底还认不认」。
     * <p>
     * 为什么要复核：单个接口的一次 401 不足以判定「整个登录态失效」。触发原因可能是一次瞬时
     * 竞态、一次服务端抽风、或中间层的拦截 —— 而清登录态是<b>不可逆</b>的，代价是用户重登一次。
     * 只有 {@code /api/auth/me} 也用同样的理由拒绝，才判定失效。
     *
     * @return Promise<boolean> true = 确认登录态已失效
     */
    function confirmSession() {
        if (sessionCheck) { return sessionCheck; }
        sessionCheck = Auth.fetch('/api/auth/me').then(function (resp) {
            // 非 401（200 / 5xx / 网络异常）：服务端没有否认这份凭证
            if (resp.status !== 401) { return false; }
            return readFailure(resp, EXPIRED_MESSAGE).then(function (failure) {
                return isSessionInvalid(failure);
            });
        }, function () {
            return false;   // 请求本身失败：服务端没表态，视为「未确认失效」
        }).then(function (dead) {
            sessionCheck = null;
            return dead;
        });
        return sessionCheck;
    }

    /** 解 JWT 段（base64url → 字符串），只用于自检打印，失败返回空串。 */
    function decodeSegment(segment) {
        var s = String(segment || '').replace(/-/g, '+').replace(/_/g, '/');
        while (s.length % 4 !== 0) { s += '='; }
        try {
            return decodeURIComponent(escape(window.atob(s)));
        } catch (e) {
            return '';
        }
    }

    // ==================== 登录框 ====================

    var VEIL_HTML = [
        '<div class="auth-modal" role="dialog" aria-modal="true" aria-labelledby="auth-title">',
        '  <button type="button" class="auth-close" aria-label="关闭">&times;</button>',
        '  <div class="auth-brand">',
        '    <span class="auth-mark"><svg viewBox="0 0 24 24" aria-hidden="true">',
        '      <path d="M13 2L4.5 13.5H11L10 22l8.5-11.5H12z" fill="#04101f" /></svg></span>',
        '    <span>My_Agent</span>',
        '  </div>',
        '  <h1 class="auth-title" id="auth-title">登录控制台</h1>',
        '  <p class="auth-sub">多智能体协作 · 教务数据 · 知识库</p>',
        '  <form class="auth-form" novalidate>',
        '    <label class="auth-field">',
        '      <span class="auth-label">登录名</span>',
        '      <input class="auth-input" name="username" type="text" autocomplete="username"',
        '             placeholder="请输入登录名" maxlength="64" />',
        '    </label>',
        '    <label class="auth-field">',
        '      <span class="auth-label">口令</span>',
        '      <input class="auth-input" name="password" type="password" autocomplete="current-password"',
        '             placeholder="请输入口令" maxlength="64" />',
        '    </label>',
        '    <p class="auth-error" role="alert"></p>',
        '    <button class="auth-submit" type="submit">登 录</button>',
        '  </form>',
        '</div>'
    ].join('\n');

    /**
     * 打开登录框（已在打开状态则只更新提示语，避免并发 401 叠出多个框）。
     *
     * @param options.closable   是否允许关闭（/login.html 的整页模式为 false）
     * @param options.page       true = 整页模式（不透明底，登录页与受限页用），false = 覆盖弹框
     * @param options.redirect   登录成功后的去向（站内路径）
     * @param options.onSuccess  登录成功后的回调（优先于 redirect，由调用方接管）
     * @param options.onMounted  框已插入 DOM 后的回调（受限页用它解除页面锁，避免中间露一帧页面内容）
     * @param options.onClose    被主动关闭（× / Esc / 点遮罩）时的回调；登录成功不算，不会触发
     * @param options.message    打开时预设的提示语（如「登录状态已失效」）
     */
    function openLogin(options) {
        var opts = options || {};
        if (veil) {
            // 已有框：只补充新提示，不用空消息把已有提示擦掉（受限页的框可能正提示「登录状态已失效」）
            if (opts.message) { showError(opts.message); }
            return;
        }
        ensureCss();
        // head 阶段（requireLogin 同步调用）body 尚未就绪：等 DOM 解析完再插框，
        // 否则 appendChild 到不存在的 body 上会静默失败
        if (!document.body) {
            document.addEventListener('DOMContentLoaded', function () { openLogin(opts); });
            return;
        }
        veilOptions = opts;

        veil = document.createElement('div');
        veil.className = 'auth-veil' + (opts.page ? ' is-page' : '');
        veil.innerHTML = VEIL_HTML;

        var closeBtn = veil.querySelector('.auth-close');
        var form = veil.querySelector('.auth-form');
        var usernameEl = veil.querySelector('input[name="username"]');
        var passwordEl = veil.querySelector('input[name="password"]');
        var submitEl = veil.querySelector('.auth-submit');

        if (!opts.closable) {
            closeBtn.remove();
        } else {
            closeBtn.addEventListener('click', function () { closeLogin(true); });
            // 点遮罩空白处关闭；卡片内部点击不冒泡到判断
            veil.addEventListener('click', function (event) {
                if (event.target === veil) { closeLogin(true); }
            });
            document.addEventListener('keydown', onEscape);
        }

        form.addEventListener('submit', function (event) {
            event.preventDefault();
            doLogin(usernameEl, passwordEl, submitEl);
        });

        document.body.appendChild(veil);
        // 打开即聚焦：用户名已填过就落到口令上，省一次 Tab
        if (usernameEl.value) { passwordEl.focus(); } else { usernameEl.focus(); }
        showError(opts.message || '');
        // 框已就位才交还视觉控制权（受限页的清锁时机）
        if (typeof opts.onMounted === 'function') { opts.onMounted(); }
    }

    function onEscape(event) {
        if (event.key === 'Escape') { closeLogin(true); }
    }

    /**
     * 关闭登录框。
     *
     * @param dismissed true = 用户主动关掉（× / Esc / 点遮罩）；登录成功走的是 closeLogin()，
     *                  不算 dismissed —— 否则「登录成功」会被 onClose 当成「放弃登录」处理。
     */
    function closeLogin(dismissed) {
        if (!veil) { return; }
        var opts = veilOptions || {};
        document.removeEventListener('keydown', onEscape);
        veil.remove();
        veil = null;
        veilOptions = null;
        if (dismissed && typeof opts.onClose === 'function') { opts.onClose(); }
    }

    function showError(message) {
        var errorEl = veil && veil.querySelector('.auth-error');
        if (!errorEl) { return; }
        errorEl.textContent = message || '';
        errorEl.classList.toggle('is-visible', !!message);
    }

    function doLogin(usernameEl, passwordEl, submitEl) {
        var username = usernameEl.value.trim();
        var password = passwordEl.value;
        if (!username || !password) {
            showError('请填写登录名与口令');
            // 令牌口令不回显，也不进任何日志
            return;
        }
        submitEl.disabled = true;
        submitEl.textContent = '登录中…';
        showError('');

        Auth.fetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username: username, password: password })
        }).then(function (resp) {
            return readJson(resp).then(function (body) {
                if (!resp.ok) {
                    // 仅提示服务端给出的原因
                    throw new Error(body.message || ('登录失败（HTTP ' + resp.status + '）'));
                }
                if (!body.data || !body.data.token) {
                    throw new Error('登录响应缺少 token，请联系管理员检查服务端日志');
                }
                return body.data;
            });
        }).then(function (data) {
            Auth.setSession(data.token, data.user);
            var opts = veilOptions || {};
            closeLogin();
            mountAllNavs();
            if (typeof opts.onSuccess === 'function') {
                opts.onSuccess(data.user);
            } else if (opts.redirect) {
                window.location.href = opts.redirect;
            }
        }).catch(function (err) {
            submitEl.disabled = false;
            submitEl.textContent = '登 录';
            showError(err && err.message ? err.message : '网络异常，请稍后重试');
        });
    }

    // ==================== 右上角鉴权区 ====================

    function renderNav(host) {
        host.innerHTML = '';
        host.classList.add('auth-nav');
        var user = Auth.getToken() ? Auth.getUser() : null;

        if (!user) {
            var loginBtn = document.createElement('button');
            loginBtn.type = 'button';
            loginBtn.className = 'auth-nav-btn';
            loginBtn.textContent = '登录';
            loginBtn.title = '登录后可使用控制台与教务系统';
            loginBtn.addEventListener('click', function () { openLogin({ closable: true }); });
            host.appendChild(loginBtn);
            return;
        }

        var nameEl = document.createElement('span');
        nameEl.className = 'auth-nav-user';
        nameEl.textContent = Auth.displayName();
        nameEl.title = '当前登录：' + Auth.displayName();
        host.appendChild(nameEl);

        if (Auth.hasRole('ADMIN')) {
            var adminLink = document.createElement('a');
            adminLink.className = 'auth-nav-link';
            adminLink.href = '/user.html';
            adminLink.textContent = '用户管理';
            adminLink.title = '用户与角色管理（服务端另有 @RequireRole 兜底）';
            host.appendChild(adminLink);
        }

        var logoutBtn = document.createElement('button');
        logoutBtn.type = 'button';
        logoutBtn.className = 'auth-nav-link';
        logoutBtn.textContent = '退出';
        logoutBtn.title = '退出登录（清除本浏览器的登录态）';
        logoutBtn.addEventListener('click', function () { Auth.logout(); });
        host.appendChild(logoutBtn);
    }

    function mountAllNavs() {
        var hosts = document.querySelectorAll('[data-auth-nav]');
        for (var i = 0; i < hosts.length; i++) {
            renderNav(hosts[i]);
        }
    }

    // ==================== 页面级闸门 ====================

    /**
     * 锁住页面：登录态确认完成前，页面内容一律不渲染。
     *
     * 这是「直接打开受限页 URL」唯一的正确姿势 —— 只看本地 token（或先渲染页面、等接口 401 再弹框）
     * 都会先闪一眼未登录的空壳页。所以 requireLogin() 在 <head> 里**同步**上锁，
     * 由 unlockPage() 在校验出结果后解除。
     *
     * head 阶段 body 还没有，因此：样式同步注入生效；提示层挂在 <html> 上（fixed 定位，与 body 无关。
     * 挂在 head 里不会渲染 —— head 是不渲染的容器）。
     */
    function lockPage() {
        if (gate || !document.documentElement) { return; }
        var style = document.createElement('style');
        style.setAttribute('data-auth-lock', '');
        style.textContent = LOCK_CSS;
        (document.head || document.documentElement).appendChild(style);
        document.documentElement.classList.add('auth-locked');

        gate = document.createElement('div');
        gate.className = 'auth-lock';
        gate.textContent = '正在校验登录状态…';
        document.documentElement.appendChild(gate);
    }

    /** 解除页面锁（校验出结果或登录框已接管视觉后调用）。 */
    function unlockPage() {
        if (!gate) { return; }
        gate.remove();
        gate = null;
        document.documentElement.classList.remove('auth-locked');
        var style = document.querySelector('style[data-auth-lock]');
        if (style) { style.remove(); }
    }

    /**
     * 受限页的登录框：整页观感。
     *
     * page:true 复用登录页那套不透明底 —— 受限页在未登录时背后的数据一个都没有，
     * 半透明遮罩会把空壳页透出来，正是要避免的观感。框在 DOM 里挂好之后才解除页面锁
     * （onMounted），保证「锁 → 框」之间不露出一帧页面内容。
     */
    function openLoginForPage(message) {
        // 已有框时 openLogin 不会挂新框、也就不会再触发 onMounted，这里直接解锁，
        // 否则页面会永久停在「正在校验登录状态…」的锁上
        if (veil) { unlockPage(); }
        openLogin({
            closable: true,
            page: true,
            message: message,
            onMounted: unlockPage,
            onSuccess: function () { window.location.reload(); },
            onClose: leaveGuardedPage
        });
    }

    /** 登录框被主动关掉后的去向：受限页在未登录状态下没有任何可用数据，回首页比停在空页面上合理。 */
    function leaveGuardedPage() {
        if (pageGuarded) { window.location.href = '/'; }
    }

    /**
     * 受限跳转：**先确认登录态再放行**。
     *
     * 只看「本地有没有 token」不够 —— token 会被服务端单方面作废（过期、账号被停用、
     * 换密钥重启），此时放行过去只会变成「先跳进去、再被踢出来重新登录」。所以有 token
     * 也先向 /api/auth/me 确认一次，确认通过才跳，失败就地弹框（重新登录后仍去目标页）。
     */
    function guardNavigation(target) {
        if (navigating) { return; }
        if (!Auth.getToken()) {
            openLogin({ closable: true, redirect: target });
            return;
        }
        navigating = true;
        Auth.fetch('/api/auth/me').then(function (resp) {
            if (resp.ok) {
                window.location.href = target;
                return null;
            }
            // 非 401（5xx / 网关）：服务端并没有否认登录态，只提示、不动本地 token
            if (resp.status !== 401) {
                openLogin({ closable: true, redirect: target, message: SERVER_ERROR_MESSAGE });
                return null;
            }
            // 401：把服务端的原因原样展示（未登录 / 已过期 / 凭证无效 / 缺访问密钥）
            return readFailure(resp, EXPIRED_MESSAGE).then(function (failure) {
                if (isSessionInvalid(failure)) {
                    Auth.clear();
                    mountAllNavs();
                }
                openLogin({ closable: true, redirect: target, message: failure.message });
            });
        }).catch(function () {
            // 请求本身没发出去（网络中断、服务端重启中）：服务端没有表态，所以**不动本地 token** ——
            // 它是「可能有效」，不是「已失效」。清掉它等于把一次网络抖动变成一次强制重新登录。
            openLogin({ closable: true, redirect: target, message: OFFLINE_MESSAGE });
        }).then(function () {
            navigating = false;   // 跳转页面即将卸载，这里只是让「校验失败留在本页」时能再点
        });
    }

    /** 拦截「需要登录才能走」的链接：先校验登录态，未登录/已失效就地弹框。委托绑定，动态插入的链接同样生效。 */
    function bindGuardedLinks() {
        document.addEventListener('click', function (event) {
            var node = event.target;
            var link = node && node.closest ? node.closest('a[data-auth-required]') : null;
            if (!link) { return; }
            // 修饰键点击（新标签/新窗口打开）交给浏览器，别拦成当前页跳转
            if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) { return; }
            // 一律拦下：已登录也要等校验结果，而校验是异步的
            event.preventDefault();
            guardNavigation(safeTarget(link.getAttribute('href'), DEFAULT_TARGET));
        });
    }

    function onReady(fn) {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', fn);
        } else {
            fn();
        }
    }

    var Auth = {
        /** 当前 token，未登录为空串。 */
        getToken: function () {
            return read(TOKEN_KEY);
        },

        /** 登录成功后写入会话（token + 用户信息）。 */
        setSession: function (token, user) {
            write(TOKEN_KEY, token || '');
            write(USER_KEY, JSON.stringify(user || {}));
        },

        /** 当前登录用户，未登录或数据损坏返回 null。 */
        getUser: function () {
            var raw = read(USER_KEY);
            if (!raw) { return null; }
            try {
                return JSON.parse(raw);
            } catch (e) {
                return null;
            }
        },

        /** 展示名：昵称为空回落登录名。 */
        displayName: function () {
            var user = Auth.getUser();
            if (!user) { return ''; }
            return user.nickname || user.username || '';
        },

        /** 是否具备指定角色（授权判定的前端侧，真正的闸门在服务端 @RequireRole）。 */
        hasRole: function (code) {
            var user = Auth.getUser();
            return !!user && Array.isArray(user.roleCodes) && user.roleCodes.indexOf(code) >= 0;
        },

        /** 清空本地登录态。 */
        clear: function () {
            remove(TOKEN_KEY);
            remove(USER_KEY);
        },

        /** 打开登录框（全站唯一入口，登录页与弹框共用同一份 UI）。 */
        openLogin: openLogin,

        /**
         * 受限跳转：先确认登录态再放行（未登录就地弹框；有 token 也先向服务端确认有效）。
         * 链接守卫与首页 Enter 快捷键共用同一份逻辑，避免两条路径行为不一致。
         */
        guardNavigation: guardNavigation,

        /** 关闭登录框。 */
        closeLogin: closeLogin,

        /** 重新渲染所有 data-auth-nav 挂载点（登录态变化后调用）。 */
        mountNavs: mountAllNavs,

        /** 整页登录（/login.html 调用）：已登录直接进目标页，否则以不可关闭的整页模式打开登录框。 */
        mountLoginPage: function () {
            if (Auth.getToken()) {
                window.location.replace(targetFromQuery());
                return;
            }
            openLogin({ closable: false, page: true, redirect: targetFromQuery() });
        },

        /** 兜底：整页跳转到登录页（弹框不可用时才走这里），带回跳地址。 */
        redirectToLogin: function () {
            Auth.clear();
            var here = window.location.pathname + window.location.search;
            if (here.indexOf(LOGIN_PAGE) === 0) { return; }
            window.location.href = LOGIN_PAGE + '?redirect=' + encodeURIComponent(here);
        },

        /**
         * 页面级守卫：先确认登录态，再决定要不要显示本页（不跳页，登录成功后重载当前页）。
         *
         * 两个都做：① 校验前先把页面锁住；② 有 token 也向 /api/auth/me 确认一次。
         * 少了① → 直接打开 /chat.html 会先闪一眼空壳页再弹框；
         * 少了② → token 会被服务端单方面作废（过期 / 账号停用 / 换密钥重启），
         *   本地有 token 却已失效，页面照样渲染，等首个接口 401 才弹框 —— 观感同样是「先进去再被踢出来」。
         */
        requireLogin: function () {
            // 声明本页离了登录态就不可用：登录框被关掉时把用户送回首页，而不是留在一个空壳页面上
            pageGuarded = true;
            lockPage();

            if (!Auth.getToken()) {
                openLoginForPage();
                return;
            }
            Auth.fetch('/api/auth/me').then(function (resp) {
                if (resp.ok) {
                    unlockPage();   // token 有效：放行，页面内容这才开始渲染
                    return null;
                }
                // 非 401：服务端没否认登录态（5xx / 网关），别清 token，也别说是凭证问题
                if (resp.status !== 401) {
                    openLoginForPage(SERVER_ERROR_MESSAGE);
                    return null;
                }
                return readFailure(resp, EXPIRED_MESSAGE).then(function (failure) {
                    if (isSessionInvalid(failure)) {
                        Auth.clear();
                        mountAllNavs();
                    }
                    openLoginForPage(failure.message);
                });
            }).catch(function () {
                // 同 guardNavigation：连不上服务端 ≠ 凭证失效。token 保留、页面仍锁着，
                // 提示语也换成「连不上」，用户可以在框里重试（服务端回来后登录即恢复）。
                openLoginForPage(OFFLINE_MESSAGE);
            });
        },

        /** 退出登录：JWT 无状态，服务端没有会话可销毁，清掉本地 token 并回到未登录态。 */
        logout: function () {
            Auth.clear();
            mountAllNavs();
            // 受限页退出后本页已无数据可用：走整页观感，别让已加载的页面内容透在半透明遮罩背后
            if (pageGuarded) {
                openLoginForPage();
                return;
            }
            openLogin({ closable: true });
        },

        /**
         * 登录态自检（排查用）：控制台执行 {@code Auth.diagnose()}。
         * <p>
         * 回答一个靠猜永远猜不准的问题 ——「服务端为什么不认这份凭证」。打印本地 token 的声明与
         * 剩余有效期（<b>不打印 token 本身</b>：它在有效期内等价于口令），再请求一次
         * {@code /api/auth/me} 把服务端给出的 reason 打出来。「压根没带」（{@code NO_TOKEN}）、
         * 「签名对不上」（{@code BAD_SIGNATURE}，密钥换过或并发验签污染）、「过期」
         * （{@code EXPIRED}）一眼可分 —— 这三种的修法完全不同。
         */
        diagnose: function () {
            var token = Auth.getToken();
            if (!token) {
                console.warn('[auth] 本地没有 token（localStorage.' + TOKEN_KEY + ' 为空）——需要登录');
                return Promise.resolve(null);
            }
            var parts = token.split('.');
            var payload = null;
            if (parts.length === 3) {
                try { payload = JSON.parse(decodeSegment(parts[1]) || 'null'); } catch (e) { payload = null; }
            }
            if (payload) {
                var ttl = payload.expMs ? Math.round((payload.expMs - Date.now()) / 1000) : null;
                console.info('[auth] 本地 token：长度=' + token.length + '，段数=' + parts.length
                    + '，iss=' + payload.iss + '，sub=' + payload.sub + '，username=' + payload.username
                    + '，剩余有效期=' + (ttl === null ? '（缺少 expMs 声明）' : ttl + ' 秒'));
                if (ttl !== null && ttl <= 0) {
                    console.warn('[auth] 该 token 已过期（剩余 ' + ttl + ' 秒），服务端会以 EXPIRED 拒绝');
                }
            } else {
                console.warn('[auth] 本地 token 不是可解析的 JWT（段数=' + parts.length + '），服务端会以 MALFORMED 拒绝');
            }
            return Auth.fetch('/api/auth/me').then(function (resp) {
                if (resp.ok) {
                    console.info('[auth] 服务端认这份 token（GET /api/auth/me → 200）');
                    return resp;
                }
                return readFailure(resp, '(服务端未提供 message)').then(function (failure) {
                    console.warn('[auth] 服务端拒绝：HTTP ' + resp.status
                        + '，reason=' + (failure.reason || '(未提供)') + '，message=' + failure.message);
                    if (failure.reason === 'BAD_SIGNATURE') {
                        console.warn('[auth] BAD_SIGNATURE：服务端算出的签名对不上。两种来源 —— '
                            + '① 这份 token 是用另一把密钥签的（点右上「退出」重新登录即可）；'
                            + '② 服务端并发验签不可靠：Hutool JWTSigner 内含非线程安全的 Mac，'
                            + '若被当单例共享会互相污染，同一份合法 token 会随机被判 BAD_SIGNATURE（已修复，'
                            + '见 README 3.1 与 .workbuddy/tools/probe_token_concurrency.py）。'
                            + '先比对启动日志里的「签名密钥 N 字节 / 指纹 xxxxxxxx」：指纹没变 ⇒ 不是密钥问题，'
                            + '排查并发那条（跑 Auth.diagnose() 看服务端给出的 reason）。');
                    }
                    return resp;
                });
            });
        },

        /**
         * 带鉴权的请求：自动附加 Authorization；401 时按服务端给出的 reason 决定处理方式
         * （只有凭证/账号类的 reason 才清本地登录态，见 {@link isSessionInvalid}），
         * 并就地弹登录框；非 401 一律原样返回，交给调用方处理。
         *
         * 注意返回的仍是原生 Response，调用方按原有方式解析（edu.js 的 opts.body 自动 JSON 化不受影响）。
         */
        fetch: function (url, options) {
            var opts = Object.assign({}, options || {});
            var headers = Object.assign({}, opts.headers || {});
            var token = Auth.getToken();
            if (token) {
                headers['Authorization'] = 'Bearer ' + token;
            }
            opts.headers = headers;
            return window.fetch(url, opts).then(function (resp) {
                if (resp.status !== 401 || String(url).indexOf(AUTH_API_PREFIX) === 0) { return resp; }
                // 这个 401 属于「已经被换掉」的旧凭证（例如用户在别处刚重新登录成功）：
                // 当前会话已经不是它了，不能拿它清掉刚拿到的 token。
                if (token && token !== Auth.getToken()) { return resp; }
                return readFailure(resp, EXPIRED_MESSAGE).then(function (failure) {
                    // 与登录态无关的 401（访问密钥闸门、代理等）：保留本地 token，只把原因说清楚
                    if (!isSessionInvalid(failure)) {
                        console.warn('[auth] 401 ' + url + '：reason=' + (failure.reason || '(服务端未提供)')
                            + ' → 与登录态无关，保留本地登录态');
                        if (!gate && !veil) {
                            openLogin({ closable: true, message: failure.message });
                        }
                        return resp;
                    }
                    // 可能是凭证失效，但**先复核**：单次 401 不足以判定整个登录态失效，
                    // 而清登录态不可逆 —— 只有 /api/auth/me 也不认，才真的清。
                    return confirmSession().then(function (dead) {
                        console.warn('[auth] 401 ' + url + '：reason=' + failure.reason
                            + (dead ? ' → /api/auth/me 复核同样被拒，清除本地登录态'
                                    : ' → /api/auth/me 复核通过，保留本地登录态'));
                        if (!dead) {
                            // 凭证经复核仍然有效：这个 401 是单次/单接口的异常，不是登录态问题。
                            // 此时弹登录框是在说假话（用户并没有掉线），交给调用方按失败处理即可。
                            return resp;
                        }
                        Auth.clear();
                        mountAllNavs();
                        // 两种情况不开框：① 页面级校验还没出结果 —— 交给 requireLogin 统一收尾，
                        // 否则这里先开一个半透明弹框，会与「整页登录框」的观感打架；
                        // ② 框已经开着（多半就是登录框，用户正在填）—— 再开只会把提示语冲掉
                        if (!gate && !veil) {
                            openLogin({
                                closable: true,
                                message: failure.message,
                                onClose: leaveGuardedPage
                            });
                        }
                        return resp;
                    });
                });
            });
        }
    };

    window.Auth = Auth;

    // 页面就绪后自动挂载右上角鉴权区与受限链接拦截
    onReady(function () {
        ensureCss();
        mountAllNavs();
        bindGuardedLinks();
    });
})();
