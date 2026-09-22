package org.luo.ai.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 工具来源：把官方 starter（spring-ai-starter-mcp-client）装配出的 {@link ToolCallbackProvider}
 * 里的远端工具收进 {@link ToolRegistry}，使其与注解式工具一样能被 {@code agent.tools_json} 装配。
 * <p>
 * 为什么不用 {@link ToolProvider}：MCP 工具在运行时由对端 {@code tools/list} 给出（名字与入参 schema
 * 都在对端），没有 {@code @Tool} 方法可反射，只能由 {@link ToolCallbackSource} 直接产出回调。
 * <p>
 * <b>吞异常是刻意的</b>：本方法在 {@link ToolRegistry} <b>构造期</b>被调用，此处抛出等于应用启动失败；
 * 故单个 server 不可达（进程起不来 / 握手超时）只降级为「少几个工具」并告警。
 * <p>
 * 配置见 application.yaml 的 {@code spring.ai.mcp.client.*}。stdio 的 server 配置等价于「在本机拉起
 * 任意进程」，只允许来自本地配置文件（application-local.yaml），不得由接口或前端在运行时新增。
 */
@Slf4j
@Component
public class McpToolSource implements ToolCallbackSource {

    /** starter 自动装配的 Provider：每个 MCP server 一个，sync / async 实现同一接口。 */
    private final ObjectProvider<ToolCallbackProvider> providers;

    public McpToolSource(ObjectProvider<ToolCallbackProvider> providers) {
        this.providers = providers;
    }

    /** 前端「工具装配」里的分组名，统一显示为 MCP。 */
    @Override
    public String groupName() {
        return "MCP";
    }

    @Override
    public List<ToolCallback> toolCallbacks() {
        List<ToolCallbackProvider> list = providers.orderedStream().toList();
        if (list.isEmpty()) {
            log.info("MCP 未接入：spring.ai.mcp.client 下未配置任何 server");
            return List.of();
        }
        List<ToolCallback> collected = new ArrayList<>();
        for (ToolCallbackProvider provider : list) {
            try {
                ToolCallback[] callbacks = provider.getToolCallbacks();
                int before = collected.size();
                if (callbacks != null) {
                    for (ToolCallback callback : callbacks) {
                        if (callback != null) collected.add(callback);
                    }
                }
                log.info("MCP 工具加载：{} 提供 {} 个",
                        provider.getClass().getSimpleName(), collected.size() - before);
            } catch (Exception e) {
                // 单个 server 不可达只丢它的工具，不影响应用启动，也不影响其余 server
                log.warn("MCP 工具加载失败，已跳过该来源：{}", provider.getClass().getSimpleName(), e);
            }
        }
        return collected;
    }
}
