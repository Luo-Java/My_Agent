package org.luo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 应用入口。
 *
 * <h2>为什么配置类注册用「按包扫描」而不是逐类罗列</h2>
 * 本项目全部配置类都是 <b>record / 普通类 + {@code @ConfigurationProperties}</b>，靠构造器注入使用 ——
 * 它们既没有 {@code @Component}，也没开扫描开关时就是「没有 bean」，注入点直接让容器起不来。
 * <p>
 * 改造前用 {@code @EnableConfigurationProperties({...})} <b>逐类罗列</b> 14 个配置类，代价是：
 * 新增配置类忘加进清单 ⇒ 编译通过、启动必炸（现象是
 * {@code UnsatisfiedDependencyException: Consider defining a bean of type 'org.luo.ai.properties.XxxProperties'}）。
 * 2026-10-08 自评功能新增 {@code SelfEvalProperties} 时就这么漏过一次，服务直接起不来。
 * <p>
 * 改为 {@link ConfigurationPropertiesScan}（无参 = 扫描<b>本类所在包 {@code org.luo} 及其全部子包</b>）后，
 * 新增 {@code @ConfigurationProperties} 类<b>零改动</b>即被注册，不再有「清单」这个需要人工同步的第二处真相。
 *
 * <h2>两条扫描边界（务必知道）</h2>
 * <ul>
 *   <li><b>包之外扫不到</b>：放在 {@code org.luo} 以外的配置类不会被注册 —— 那种情况需显式
 *       {@code @EnableConfigurationProperties(XxxProperties.class)} 补上；</li>
 *   <li><b>嵌套（内部）类扫不到</b>：扫描器只取顶层类，写在别的类里的 {@code @ConfigurationProperties} 同样需显式登记。</li>
 * </ul>
 * 这两条都由 {@code .workbuddy/tools/check_properties_registered.py} 自动盯着（扫到不符合的类即报错）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan({"org.luo.ai.mapper", "org.luo.edu.mapper", "org.luo.system.mapper"})
public class MyAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyAgentApplication.class, args);
    }

}
