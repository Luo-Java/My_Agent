package org.luo.tool;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具注册表：Spring 自动注入所有 {@link ToolProvider} 实现类，
 * 启动时预解析为 {@link ToolCallback}[]，后续每次会话直接复用，无需重复反射。
 * <p>
 * 新增工具 = 写一个类，加 {@code @Component}，方法标 {@code @Tool}，实现 {@link ToolProvider}。
 * 本注册表与业务代码均无需改动。
 */
@Getter
@Slf4j
@Component
public class ToolRegistry {

    /** 预解析好的工具回调，每次会话直接复用。 */
    private final ToolCallback[] toolCallbacks;

    public ToolRegistry(List<ToolProvider> toolProviders) {
        this.toolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(toolProviders.toArray())
                .build()
                .getToolCallbacks();
        log.info("自动发现 {} 个工具 Bean，预解析为 {} 个 ToolCallback",
                toolProviders.size(), this.toolCallbacks.length);
    }
}
