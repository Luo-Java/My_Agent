package org.luo.system.config;

import org.luo.system.security.JwtAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把 {@link JwtAuthInterceptor} 注册到 {@code /api/**}。
 * <p>
 * 与 {@code ApiSecurityConfig}（服务级密钥 X-Api-Key）各自独立注册、互不影响：两者都是
 * 「先到先拦」，顺序不影响最终结果 —— 任一不通过都会得到 401。
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
