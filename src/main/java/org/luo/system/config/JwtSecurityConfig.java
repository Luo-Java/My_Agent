package org.luo.system.config;

import org.luo.system.security.JwtAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把 {@link JwtAuthInterceptor} 注册到 {@code /api/**} —— 全项目唯一的接口鉴权闸门。
 * <p>
 * 静态页面与 {@code /files/**} 不在 {@code /api/**} 内，不受登录校验影响（{@code /files/**}
 * 是附件直连地址，图片 &lt;img&gt; 无法携带请求头，这是既有设计）。
 */
@Configuration
public class JwtSecurityConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;

    public JwtSecurityConfig(JwtAuthInterceptor jwtAuthInterceptor) {
        this.jwtAuthInterceptor = jwtAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor).addPathPatterns("/api/**");
    }
}
