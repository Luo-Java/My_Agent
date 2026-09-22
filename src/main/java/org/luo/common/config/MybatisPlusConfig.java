package org.luo.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 分页插件注册：教务系统（/api/edu/**）需要分页查询大量演示数据
 * （student 2160 条、score 5 万条）。未注册前 {@code Page} 分页不生效（返回全量）。
 * <p>
 * {@code PaginationInnerInterceptor} 在 MyBatis-Plus 3.5.9+ 从 extension 拆分到
 * {@code mybatis-plus-jsqlparser} 模块，需在 pom 显式引入该依赖（本项目已加）。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
