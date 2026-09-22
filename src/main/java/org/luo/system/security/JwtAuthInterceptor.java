package org.luo.system.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.luo.system.service.SysUserService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 登录鉴权拦截器：挂在 {@code /api/**}，解析 {@code Authorization: Bearer <token>} 并在放行前
 * 把身份写入 {@link AuthContext}，同时按 {@link RequireRole} 做角色校验。
 * <p>
 * <b>每次请求都回库复核</b>（{@code loadLoginUser}）：token 只证明「这个人登录过」，不证明
 * 「他现在还有权限」。停用账号、摘掉角色这类操作因此立即生效，不必等 token 过期 ——
 * 代价是每请求多两次主键级查询，对本地 MySQL 可忽略。
 * <p>
 * 关闭 {@code app.jwt.enabled} 时本拦截器直接放行，行为与引入用户系统前一致。
 */
@Slf4j
@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    /**
     * 免登录路径（精确匹配请求 URI）。
     * <p>
     * 只有登录接口本身 —— 其余 {@code /api/**} 一律要求 token。静态页面（{@code /}、{@code /js/**}）
     * 不在 {@code /api/**} 范围内，不在本拦截器的管辖内。
     */
    private static final Set<String> ANONYMOUS_PATHS = Set.of("/api/auth/login");

    private final JwtProperties props;

    private final JwtTokenService tokenService;

    private final SysUserService userService;

    public JwtAuthInterceptor(JwtProperties props, JwtTokenService tokenService, SysUserService userService) {
        this.props = props;
        this.tokenService = tokenService;
        this.userService = userService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!props.isEnabled()) {
            return true;
        }
        // CORS 预检不带业务头，放行；其后的真实请求仍会被校验
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        if (ANONYMOUS_PATHS.contains(request.getRequestURI())) {
            return true;
        }
        TokenCheck check = tokenService.verify(resolveToken(request));
        if (!check.valid()) {
            // 具体原因进日志与响应体：客户端那侧只能看到 status，笼统提示会把「没带凭证」和
            // 「密钥换过导致签名不符」混成同一句话，排查只能靠猜
            log.warn("拒绝 {} {}：{}", request.getMethod(), request.getRequestURI(), check.status());
            return reject(response, HttpServletResponse.SC_UNAUTHORIZED, messageFor(check.status()),
                    check.status().name());
        }
        LoginUser user = userService.loadLoginUser(check.userId());
        if (user == null) {
            log.warn("拒绝 {} {}：账号不存在或已停用，userId={}", request.getMethod(), request.getRequestURI(),
                    check.userId());
            return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "账号不存在或已停用，请重新登录",
                    "USER_UNAVAILABLE");
        }
        AuthContext.set(user);

        RequireRole required = resolveRequiredRole(handler);
        if (required != null && !satisfies(user, required)) {
            log.warn("越权访问被拒：用户={}，角色={}，需要={}", user.username(), user.roles(),
                    String.join(",", required.value()));
            return reject(response, HttpServletResponse.SC_FORBIDDEN,
                    "无权访问：需要 " + String.join("、", required.value()) + " 角色", "ROLE_DENIED");
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        // 必须清理：线程池复用线程时，残留的身份会泄漏给下一个请求
        AuthContext.clear();
    }

    /** 取 Bearer token；缺失或格式不符返回 null。 */
    private static String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    /** 取接口声明的角色要求：方法上的优先于类上的。 */
    private static RequireRole resolveRequiredRole(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return null;
        }
        RequireRole onMethod = method.getMethodAnnotation(RequireRole.class);
        return onMethod != null ? onMethod : method.getBeanType().getAnnotation(RequireRole.class);
    }

    /** 角色判定：requireAll 为 true 时需全部命中，否则命中任一即可。 */
    private static boolean satisfies(LoginUser user, RequireRole required) {
        String[] codes = required.value();
        if (codes.length == 0) {
            return true;
        }
        if (required.requireAll()) {
            for (String code : codes) {
                if (!user.hasRole(code)) {
                    return false;
                }
            }
            return true;
        }
        for (String code : codes) {
            if (user.hasRole(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取该结论对应的用户可见提示。区分「没带凭证 / 过期 / 不认这个凭证」，
     * 前端把服务端提示原样展示即可，不必自己编一句更模糊的话。
     */
    private static String messageFor(TokenCheck.TokenStatus status) {
        return switch (status) {
            case NO_TOKEN -> "未登录，请先登录";
            case EXPIRED -> "登录已过期，请重新登录";
            default -> "登录凭证无效，请重新登录";
        };
    }

    /**
     * 拒绝并写出与全局异常处理同形状的 {@code {code, message, reason}}，额外带上机器可读的
     * {@code reason}（{@link TokenCheck.TokenStatus} 或 {@code USER_UNAVAILABLE}），供前端提示与排查直接引用。
     * 提示语与 reason 均为本类常量，无注入风险。
     * <p>
     * <b>刻意不用 {@code setCharacterEncoding} / {@code getWriter}</b>：那会让 Tomcat 把响应头写成
     * {@code application/json;charset=UTF-8}，而其余接口（Jackson 输出）都是 {@code application/json} ——
     * 同一个 API 两种响应头形状，排查时会被误读成「带 charset 的请求才 401」。JSON 按
     * RFC 8259 就是 UTF-8，直接写字节，编码由本方法保证。
     */
    private static boolean reject(HttpServletResponse response, int status, String message, String reason)
            throws IOException {
        String body = "{\"code\":" + status + ",\"message\":\"" + message
                + "\",\"reason\":\"" + reason + "\"}";
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        return false;
    }
}
