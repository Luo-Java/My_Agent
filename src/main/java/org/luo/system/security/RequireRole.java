package org.luo.system.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明接口所需的角色，由 {@link JwtAuthInterceptor} 在放行前校验（命中在类上则整个控制器生效）。
 * <p>
 * 用注解而不是在拦截器里硬编码路径前缀，是为了让「哪个接口要什么角色」紧贴接口本身，
 * 新增控制器时不会漏配、也不会出现一份与代码脱节的路径清单。
 * <p>
 * 未标注的接口只要求「已登录」。校验不通过返回 403。
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 所需角色编码（与 sys_role.code 一致），如 ADMIN。 */
    String[] value();

    /** true=需同时具备全部角色；false（默认）=具备任一即可。 */
    boolean requireAll() default false;
}
