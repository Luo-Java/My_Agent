package org.luo.common.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 接口访问控制（可选）：配置 {@code app.api-key} 后，所有 /api/** 请求必须携带
 * {@code X-Api-Key} 头（兼容 {@code Authorization: Bearer <key>}）且与配置一致才放行，
 * 否则返回 401 {@code {code:401, message:...}}。
 * <p>
 * 用途：把服务暴露到不可信网络（局域网 / 公网）时，防止他人直接调用 SQL 查询工具、
 * 白嫖 LLM API Key、读写知识库等。静态页面（前端）与 OPTIONS 预检请求天然不受影响。
 * <p>
 * 默认 {@code app.api-key} 为空（本地开发），此时不校验——行为与未加本拦截器前完全一致；
 * 部署时通过环境变量 {@code APP_API_KEY} 注入即全局生效，重启后无需改代码。
 */
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    /** 请求头名称：X-Api-Key。 */
    public static final String HEADER_API_KEY = "X-Api-Key";

    /** 期望的 API Key；空 / 未配置 = 不启用校验（本地开发默认）。 */
    @Value("${app.api-key:}")
    private String apiKey;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 未配置 key：保持原有开放行为（本地开发默认），不拦截
        if (apiKey == null || apiKey.isBlank()) {
            return true;
        }
        // CORS 预检请求不带业务头，直接放行（其后的真实请求仍会被校验）
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String provided = resolveKey(request);
        if (provided != null && constantTimeEquals(apiKey, provided)) {
            return true;
        }
        // 与其余接口保持同一种响应头形状：setCharacterEncoding 会让 Tomcat 追加 ;charset=UTF-8，
        // 而 Jackson 输出的都是 application/json —— 同一 API 两种形状会误导排查。
        // JSON 按规范即 UTF-8，直接写字节，编码由本方法保证。
        //
        // reason 必须与 JwtAuthInterceptor 的 TokenStatus 区分开：两处闸门都回 401，前端凭 reason
        // 判断「该不该清掉本地登录态」——本闸门拒绝的是服务级密钥，与用户登录态无关，
        // 若混为一谈就会把「密钥没配」变成「所有人都被强制重新登录」（见 auth.js 的 reason 白名单）。
        String body = "{\"code\":401,\"message\":\"未授权：缺少或错误的 X-Api-Key\","
                + "\"reason\":\"API_KEY_REQUIRED\"}";
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        return false;
    }

    /** 从请求取 key：优先 X-Api-Key 头，兼容 Authorization: Bearer 形式。 */
    private String resolveKey(HttpServletRequest request) {
        String provided = request.getHeader(HEADER_API_KEY);
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String bearer = auth.substring(7).trim();
            if (!bearer.isEmpty()) {
                return bearer;
            }
        }
        return null;
    }

    /** 常量时间比较，避免通过响应时间差逐位枚举 key。 */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
