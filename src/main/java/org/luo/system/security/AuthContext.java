package org.luo.system.security;

import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;

/**
 * 当前请求的登录用户上下文（ThreadLocal）。
 * <p>
 * 写入由 {@link JwtAuthInterceptor#preHandle} 统一完成、{@code afterCompletion} 清理，
 * 业务代码只读取。异步派发（SSE 等）后不在请求线程的执行体里读不到值 —— 如需在异步链路里
 * 使用，请在进入异步前把 {@link LoginUser} 取出当参数传递，不要指望 ThreadLocal 跟着线程走。
 */
public final class AuthContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    /** 存入当前请求的登录用户；由拦截器调用。 */
    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /** 取当前登录用户，未登录返回 null。 */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 取当前登录用户，未登录直接抛 401（业务接口在拦截器放行后调用，正常不会触发）。 */
    public static LoginUser require() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw new AiBusinessException(AiErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    /** 清理，防止线程池复用线程时把身份泄漏给下一个请求。 */
    public static void clear() {
        HOLDER.remove();
    }
}
