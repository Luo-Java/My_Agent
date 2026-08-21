package org.luo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局 CORS 跨域配置。
 * <p>
 * 统一在此配置跨域规则（替代各 Controller 上的 @CrossOrigin 注解），
 * 覆盖所有 /api/** 接口，无需在单个 Controller 重复声明。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                // 允许任意来源；用 allowedOriginPatterns 而非 allowedOrigins("*")，
                // 以便将来开启 allowCredentials(true)（携带 Cookie）时不冲突
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
