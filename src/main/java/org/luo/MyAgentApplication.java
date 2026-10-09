package org.luo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

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
 *
 * <h2>为什么需要 {@code @EnableScheduling}</h2>
 * 定时任务（{@code ScheduledTaskService.tick}）与阈值告警（{@code AlertService}）都靠 {@code @Scheduled}
 * 驱动。没有这个注解，{@code @Scheduled} 方法会被<b>静默忽略</b> —— 不报错、不打日志，表现为「任务存下了
 * 却永远不跑」，是本项目另一类「编译通过、运行无痕」的坑，故显式开在此处并写明原因。
 *
 * <h2>为什么还要自己注册 {@code TaskScheduler}（不能只用默认的）</h2>
 * 三个 {@code @Scheduled}（告警自检 10 分钟 / 定时任务扫描 30 秒 / 周期报告 cron 09:00）默认共用
 * Spring Boot 内建的<b>单线程</b>调度池，而 {@code ScheduledTaskService.execute} 是在<b>调度线程上同步</b>
 * 跑真实模型对话（秒级到分钟级）。共用单线程会出三档后果：
 * <ul>
 *   <li>一个慢任务占满线程 ⇒ 其余两个 {@code @Scheduled} 整段不执行；</li>
 *   <li>cron 型任务（周期报告）错过触发点<b>当天永久丢失</b>（Spring 不补跑）；</li>
 *   <li>多实例部署时，各实例的扫描相位不同，{@code next_run_at} 的 CAS 只保证「同一时刻一个执行者」、
 *       保证不了「一个周期只执行一次」—— cron 周期短于单轮耗时时，同一周期会被执行两次，
 *       也就是<b>双倍真实模型花费</b>。</li>
 * </ul>
 * 给足线程数即可把三者隔离开；{@code waitForTasksToCompleteOnShutdown} 让停机时把在跑的任务收尾，
 * 而不是直接掐断（{@code execute} 里正在进行的模型调用被掐断会留下 {@code last_status=RUNNING} 的悬挂状态）。
 * <p>
 * <b>为什么不用 {@code spring.task.scheduling.pool-size}</b>：那是 Boot 自动配置的默认值，
 * 本项目调度器需要显式的关停策略与线程命名（排查定时问题时日志里能一眼看出是哪个池），写在代码里更直白。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@MapperScan({"org.luo.ai.mapper", "org.luo.edu.mapper", "org.luo.system.mapper"})
public class MyAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyAgentApplication.class, args);
    }

}
