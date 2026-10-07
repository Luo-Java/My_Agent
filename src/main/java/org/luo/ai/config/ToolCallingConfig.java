package org.luo.ai.config;

import org.luo.ai.advisor.BoundedToolCallingAdvisor;
import org.luo.ai.properties.ToolCallProperties;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具循环控制装配：用自定义的「有界工具循环」替换 Spring AI 默认的无限循环。
 * <p>
 * Spring AI 自动配置（{@code ChatClientAutoConfiguration}）默认提供一个
 * {@code @ConditionalOnMissingBean ToolCallingAdvisor.Builder<?>}（{@code toolCallingAdvisorBuilder}），
 * 其 build() 产出默认的 {@link ToolCallingAdvisor}（无迭代上限）。这里提供<b>同类型</b> bean 覆盖它：
 * build() 产出 {@link BoundedToolCallingAdvisor}（轮数上限 + 连续重复检测 + 单轮 token 预算 + 软刹车），
 * {@code ChatClient.Builder} 经 {@code toolCallingAdvisorBuilder.getIfAvailable()} 拿到的是本 bean，
 * 于是普通对话与规划中间步骤两个 ChatClient 都会挂上有界循环（与默认行为一致的替换，无侵入）。
 * <p>
 * 依赖与默认 builder 一致：{@link ToolCallingManager}（自动配置产出，已用 LenientToolCallbackResolver）
 * 与可选的 {@link ToolExecutionEligibilityChecker}。
 */
@Configuration
public class ToolCallingConfig {

    @Bean
    public ToolCallingAdvisor.Builder<?> boundedToolCallingAdvisorBuilder(
            ToolCallingManager toolCallingManager,
            ObjectProvider<ToolExecutionEligibilityChecker> toolExecutionEligibilityChecker,
            ToolCallProperties toolCallProperties) {
        return new BoundedToolCallingAdvisorBuilder(toolCallingManager, toolExecutionEligibilityChecker,
                toolCallProperties);
    }

    /** 自定义 builder：build() 产出 {@link BoundedToolCallingAdvisor}，其余行为与父类一致。 */
    private static final class BoundedToolCallingAdvisorBuilder
            extends ToolCallingAdvisor.Builder<BoundedToolCallingAdvisorBuilder> {

        private final ToolCallProperties toolCallProperties;
        private final ObjectProvider<ToolExecutionEligibilityChecker> eligibilityChecker;

        BoundedToolCallingAdvisorBuilder(ToolCallingManager toolCallingManager,
                                         ObjectProvider<ToolExecutionEligibilityChecker> eligibilityChecker,
                                         ToolCallProperties toolCallProperties) {
            super();
            this.toolCallingManager(toolCallingManager);
            this.eligibilityChecker = eligibilityChecker;
            this.toolCallProperties = toolCallProperties;
            // 若应用提供了自定义检查器则采用；未提供沿用父类默认（hasToolCalls 判断）
            eligibilityChecker.ifAvailable(this::toolExecutionEligibilityChecker);
        }

        @Override
        protected BoundedToolCallingAdvisorBuilder self() {
            return this;
        }

        @Override
        protected ToolCallingAdvisor.Builder<?> newCopy() {
            BoundedToolCallingAdvisorBuilder copy = new BoundedToolCallingAdvisorBuilder(
                    getToolCallingManager(), eligibilityChecker, toolCallProperties);
            copy.toolExecutionEligibilityChecker(getToolExecutionEligibilityChecker());
            copy.advisorOrder(getAdvisorOrder());
            return copy;
        }

        @Override
        public ToolCallingAdvisor build() {
            return new BoundedToolCallingAdvisor(getToolCallingManager(), getToolExecutionEligibilityChecker(),
                    getAdvisorOrder(), toolCallProperties);
        }
    }
}
