package org.luo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 安全注册配置：把 {@link ApiKeyInterceptor} 挂到 /api/**。
 * 静态页面（/、/index.html、/js、/css）不在拦截范围，前端访问不受影响；
 * 与 {@link CorsConfig} 同为 WebMvcConfigurer，各自独立注册、互不影响。
 */
@Configuration
public class ApiSecurityConfig implements WebMvcConfigurer {

    private final ApiKeyInterceptor apiKeyInterceptor;

    public ApiSecurityConfig(ApiKeyInterceptor apiKeyInterceptor) {
        this.apiKeyInterceptor = apiKeyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyInterceptor).addPathPatterns("/api/**");
    }
}
