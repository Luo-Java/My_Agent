package org.luo.tool;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 动态工具来源：实现此接口的 Bean 会被 {@link ToolRegistry} 自动收集，用于注册<b>非注解式</b>工具。
 * <p>
 * 与 {@link ToolProvider} 的分工：{@code @Tool} 方法在编译期就固定，适合项目内自研工具；而<b>远端工具</b>
 * （如 MCP server 的 {@code tools/list} 结果）运行时才知道有哪些、schema 也由对方给出，反射不出 {@code @Tool}
 * 方法，只能由本接口直接产出 {@link ToolCallback}。
 * <p>
 * {@link #toolCallbacks()} 在 {@link ToolRegistry} 构造期即被调用：远端不可达时必须返回空列表并告警，
 * <b>绝不能抛异常</b>，否则整个应用启动失败。
 */
public interface ToolCallbackSource {

    /** 本来源提供的工具回调；不允许返回 null。 */
    List<ToolCallback> toolCallbacks();

    /** 分组名，前端「工具装配」界面按此归类；默认取实现类简名。 */
    default String groupName() {
        return getClass().getSimpleName();
    }
}
