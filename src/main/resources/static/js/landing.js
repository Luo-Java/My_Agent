/* 首页交互：粒子网络背景、副标题打字机、Enter 快捷进入。
   全部原生实现，不引入任何外部依赖（chat.html 才需要 Vue / ECharts）。 */
(function () {
    'use strict';

    var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    /* ---------- 1) 粒子网络：节点漂移 + 近邻连线 + 鼠标跟随 ---------- */
    var canvas = document.getElementById('net');
    if (canvas && canvas.getContext) {
        var ctx = canvas.getContext('2d');
        var dpr = Math.min(window.devicePixelRatio || 1, 2);
        var W = 0, H = 0;
        var nodes = [];
        var raf = null;
        var mouse = { x: -9999, y: -9999 };
        var LINK = 132;   // 节点互相连线的距离阈值
        var PULL = 186;   // 鼠标连线的距离阈值

        function build() {
            var count = Math.round(Math.min(92, Math.max(26, (W * H) / 17000)));
            nodes = [];
            for (var i = 0; i < count; i++) {
                nodes.push({
                    x: Math.random() * W,
                    y: Math.random() * H,
                    vx: (Math.random() - .5) * .28,
                    vy: (Math.random() - .5) * .28,
                    r: Math.random() * 1.3 + .9
                });
            }
        }

        function resize() {
            W = canvas.clientWidth;
            H = canvas.clientHeight;
            canvas.width = Math.floor(W * dpr);
            canvas.height = Math.floor(H * dpr);
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
            build();
            if (reduce) { draw(true); }
        }

        function draw(staticFrame) {
            ctx.clearRect(0, 0, W, H);
            var i, j, a, b, dx, dy, dist;

            for (i = 0; i < nodes.length; i++) {
                a = nodes[i];
                if (!staticFrame) {
                    a.x += a.vx;
                    a.y += a.vy;
                    if (a.x < -20) a.x = W + 20;
                    if (a.x > W + 20) a.x = -20;
                    if (a.y < -20) a.y = H + 20;
                    if (a.y > H + 20) a.y = -20;
                }
            }

            /* 节点之间的连线：越近越亮 */
            for (i = 0; i < nodes.length; i++) {
                a = nodes[i];
                for (j = i + 1; j < nodes.length; j++) {
                    b = nodes[j];
                    dx = a.x - b.x;
                    dy = a.y - b.y;
                    dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist < LINK) {
                        ctx.strokeStyle = 'rgba(120,190,255,' + ((1 - dist / LINK) * .22).toFixed(3) + ')';
                        ctx.lineWidth = .8;
                        ctx.beginPath();
                        ctx.moveTo(a.x, a.y);
                        ctx.lineTo(b.x, b.y);
                        ctx.stroke();
                    }
                }
            }

            /* 鼠标附近的节点与鼠标连线，形成“被牵引”的手感 */
            for (i = 0; i < nodes.length; i++) {
                a = nodes[i];
                dx = a.x - mouse.x;
                dy = a.y - mouse.y;
                dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < PULL) {
                    ctx.strokeStyle = 'rgba(110,230,250,' + ((1 - dist / PULL) * .32).toFixed(3) + ')';
                    ctx.lineWidth = .9;
                    ctx.beginPath();
                    ctx.moveTo(a.x, a.y);
                    ctx.lineTo(mouse.x, mouse.y);
                    ctx.stroke();
                }
            }

            /* 节点本体 */
            for (i = 0; i < nodes.length; i++) {
                a = nodes[i];
                ctx.fillStyle = 'rgba(160,215,255,.62)';
                ctx.beginPath();
                ctx.arc(a.x, a.y, a.r, 0, Math.PI * 2);
                ctx.fill();
            }
        }

        function loop() {
            draw(false);
            raf = window.requestAnimationFrame(loop);
        }

        function start() {
            if (raf === null && !reduce) { loop(); }
        }

        function stop() {
            if (raf !== null) { window.cancelAnimationFrame(raf); raf = null; }
        }

        window.addEventListener('resize', resize);
        window.addEventListener('mousemove', function (e) {
            mouse.x = e.clientX;
            mouse.y = e.clientY;
        });
        window.addEventListener('mouseout', function () {
            mouse.x = -9999;
            mouse.y = -9999;
        });
        /* 标签页不可见时停掉动画，避免后台空转耗电 */
        document.addEventListener('visibilitychange', function () {
            if (document.hidden) { stop(); } else { start(); }
        });

        resize();
        if (reduce) { draw(true); } else { start(); }
    }

    /* ---------- 2) 副标题打字机：逐字打出 → 停顿 → 逐字回删 → 换下一句 ---------- */
    var typed = document.getElementById('typed');
    if (typed) {
        var lines = [
            '规划 · 路由 · 澄清 · 记忆 · 检索，一次对话跑通全链路',
            'RAG 混合召回加精排，答案带引用可溯源',
            '自然语言生成 SQL，直接在数据上提问',
            'MCP 协议接入外部工具，能力可插拔'
        ];

        if (reduce) {
            typed.textContent = lines[0];
        } else {
            var li = 0, ci = 0, deleting = false;
            var TYPE_MS = 62, DEL_MS = 26, HOLD_MS = 1900, GAP_MS = 320;

            (function tick() {
                var text = lines[li];
                if (!deleting) {
                    ci++;
                    typed.textContent = text.slice(0, ci);
                    if (ci === text.length) {
                        deleting = true;
                        window.setTimeout(tick, HOLD_MS);
                        return;
                    }
                    window.setTimeout(tick, TYPE_MS);
                } else {
                    ci--;
                    typed.textContent = text.slice(0, ci);
                    if (ci === 0) {
                        deleting = false;
                        li = (li + 1) % lines.length;
                        window.setTimeout(tick, GAP_MS);
                        return;
                    }
                    window.setTimeout(tick, DEL_MS);
                }
            })();
        }
    }

    /* ---------- 3) Enter 直接进入控制台（输入框在外时也能用） ---------- */
    document.addEventListener('keydown', function (e) {
        var tag = (e.target && e.target.tagName) || '';
        if (tag === 'INPUT' || tag === 'TEXTAREA' || e.isComposing) { return; }
        if (e.key === 'Enter') {
            window.location.href = '/chat.html'; // chat.html 不自动打开最近会话，落在空白欢迎页
        }
    });
})();
