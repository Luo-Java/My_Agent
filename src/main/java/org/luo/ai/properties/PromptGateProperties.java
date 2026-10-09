package org.luo.ai.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 提示词改动门禁配置（{@code agent.prompt-gate.*}）：把「改了 {@code prompts.yaml} 到底变好还是变差」
 * 从「靠手感」变成「有结论」，并让它<b>在改动发生的那一刻</b>就被看见。
 * <p>
 * 与 {@code agent.eval.*} 的分工：那套是「怎么跑批」（用例集位置、超时），本套是「<b>什么时候跑、跑完怎么判</b>」。
 * 跑批能力早就有了，缺的只是一个触发点 —— 本配置补的就是这个。
 * <p>
 * <b>默认值取舍</b>：{@code enabled} 默认 <b>true</b>（只做指纹比对，零模型调用、零成本，没有理由关）；
 * {@code autoRun} 默认 <b>false</b>（自动跑批 = 启动时真实模型调用，成本与耗时都不可忽略，必须显式开启）。
 * 两者配合的效果是：升级后默认你就知道「提示词跟上次验证过的那版不一样了」，想让它自动验就开 autoRun。
 *
 * @param enabled     总开关（默认 true）：关掉则既不比对指纹、也不记录快照，行为与不配这一节一致
 * @param autoRun     检测到变更时是否自动跑批（默认 false）。开启后启动阶段会发起一次真实模型调用
 * @param failOnBroken 出现 broken（上批过、本批败）是否判为 FAIL（默认 true）。置 false 则只记录、不当门禁
 */
@ConfigurationProperties(prefix = "agent.prompt-gate")
public record PromptGateProperties(Boolean enabled, Boolean autoRun, Boolean failOnBroken) {

    public PromptGateProperties {
        if (enabled == null) enabled = true;
        if (autoRun == null) autoRun = false;
        if (failOnBroken == null) failOnBroken = true;
    }

    /** 门禁是否生效（显式开启）。 */
    public boolean enabledOn() {
        return Boolean.TRUE.equals(enabled);
    }

    /** 是否允许自动跑批。 */
    public boolean autoRunOn() {
        return Boolean.TRUE.equals(autoRun);
    }

    /** 出现 broken 是否判为不通过。 */
    public boolean failOnBrokenOn() {
        return Boolean.TRUE.equals(failOnBroken);
    }
}
