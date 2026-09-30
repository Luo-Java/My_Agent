package org.luo.ai.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 提示词回归评测配置（{@code agent.eval.*}）。
 * <p>
 * 评测解决的是「改了 {@code prompts.yaml} 却不知道变好还是变差」——路由判定、动态规划、参数抽取这些
 * 决策类提示词的改动，此前全靠手工试几条对话看手感。本配置驱动「跑批」：把固定用例集（每条 = 一次
 * 决策调用的输入 + 期望输出）逐条跑一遍并断言，产出可对比的通过/失败清单。
 * <p>
 * 用例集是<b>数据不是配置</b>，故独立成 {@code eval-cases.yaml}、用 Hutool 单独解析，不并入 Spring
 * Environment：解析失败只影响评测本身，不会牵连应用启动；也不会被环境变量意外覆盖。
 *
 * @param casesFile      用例集位置（Spring {@code Resource} 语法，如 {@code classpath:eval-cases.yaml}、
 *                       {@code file:./eval-cases.yaml}）；默认 classpath
 * @param timeoutSeconds 单个用例的编排层超时（秒）：超时记为该用例失败而非拖垮整批。
 *                       被评测的调用（路由/规划）自身没有 per-request 超时，只靠全局模型超时兜底，
 *                       跑批时一条卡住就会拖住整批，故在此显式加一圈
 */
@ConfigurationProperties(prefix = "agent.eval")
public record EvalProperties(String casesFile, Integer timeoutSeconds) {

    /** 默认用例集位置（打进 jar 的 classpath 资源）。 */
    public static final String DEFAULT_CASES_FILE = "classpath:eval-cases.yaml";

    /** 默认单用例超时（秒）：留足余量覆盖一次完整的模型往返 + 重试。 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;

    public EvalProperties {
        if (casesFile == null || casesFile.isBlank()) casesFile = DEFAULT_CASES_FILE;
        if (timeoutSeconds == null || timeoutSeconds <= 0) timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    }

    /** 单用例超时的毫秒数。 */
    public long timeoutMillis() {
        return timeoutSeconds * 1000L;
    }
}
