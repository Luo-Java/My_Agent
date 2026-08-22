package org.luo.tool;

/**
 * 标记接口：实现此接口的 Spring Bean 会被 {@link ToolRegistry} 自动收集。
 * <p>
 * 新增工具 = 写一个类，加 {@code @Component}，方法标 {@code @Tool}，实现本接口即可。
 */
public interface ToolProvider {
}
