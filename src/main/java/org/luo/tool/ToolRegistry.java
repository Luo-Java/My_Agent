package org.luo.tool;

import cn.hutool.json.JSONUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：启动时把两类工具统一预解析为 {@link ToolCallback}[]，并建「工具名 → 回调」索引，
 * 供按智能体装配时按名过滤（见 {@link #resolve(String)}）。
 * <ul>
 *   <li><b>注解式</b>：{@link ToolProvider} 实现类，方法标 {@code @Tool}，反射解析。</li>
 *   <li><b>动态式</b>：{@link ToolCallbackSource} 实现类，直接产出回调（远端工具运行时才知道有哪些）。</li>
 * </ul>
 * 两类都只需 {@code @Component} + 实现接口，本类与业务代码均无需改动。
 * <p>
 * <b>工具名</b>取 {@code ToolDefinition.name()}（{@code @Tool} 未指定 name 时即方法名），是 {@code agent.tools_json}
 * 白名单的匹配依据；同名工具保留先注册者（注解式优先）并告警，改动 {@code @Tool} 方法名会使既有白名单失配。
 */
@Getter
@Slf4j
@Component
public class ToolRegistry {

    /** 全量工具回调，顺序稳定（注解式在前、动态式在后，各自按发现顺序）。 */
    private final ToolCallback[] toolCallbacks;

    /** 工具名 → 回调 的索引，用于按智能体白名单过滤。 */
    private final Map<String, ToolCallback> byName;

    /** 工具清单（名/描述/分组），供前端「工具装配」选择界面展示。 */
    private final List<ToolInfo> availableTools;

    /** 空工具集：智能体显式声明「不使用任何工具」（tools_json = "[]"）时返回。 */
    private static final ToolCallback[] EMPTY = new ToolCallback[0];

    /** 工具元信息；{@code group} 为来源（ToolProvider 类名或 ToolCallbackSource 分组名），前端按此分组展示。 */
    public record ToolInfo(String name, String description, String group) {}

    public ToolRegistry(List<ToolProvider> toolProviders, ObjectProvider<ToolCallbackSource> toolSources) {
        List<ToolCallback> all = new ArrayList<>();
        Map<String, ToolCallback> index = new LinkedHashMap<>();
        List<ToolInfo> infos = new ArrayList<>();
        // 逐个 Provider 解析（而非一次性传入全部对象）：为了记录每个工具的分组来源，供前端分组展示
        for (ToolProvider provider : toolProviders) {
            register(provider.getClass().getSimpleName(), MethodToolCallbackProvider.builder()
                    .toolObjects(provider)
                    .build()
                    .getToolCallbacks(), all, index, infos);
        }
        // 动态来源用 ObjectProvider：零实现时静默跳过，不给应用启动加约束
        List<ToolCallbackSource> sources = toolSources.orderedStream().toList();
        for (ToolCallbackSource src : sources) {
            register(src.groupName(), src.toolCallbacks().toArray(ToolCallback[]::new), all, index, infos);
        }
        this.toolCallbacks = all.toArray(ToolCallback[]::new);
        this.byName = index;
        this.availableTools = List.copyOf(infos);
        log.info("工具注册完成：{} 个注解式 Bean + {} 个动态来源，共 {} 个 ToolCallback：{}",
                toolProviders.size(), sources.size(), this.toolCallbacks.length, index.keySet());
    }

    /** 登记一批回调：同名时保留先注册者并告警（注解式优先于动态式）。 */
    private void register(String group, ToolCallback[] callbacks,
                          List<ToolCallback> all, Map<String, ToolCallback> index, List<ToolInfo> infos) {
        for (ToolCallback cb : callbacks) {
            String name = cb.getToolDefinition().name();
            if (index.putIfAbsent(name, cb) != null) {
                log.warn("工具名重复：{}（来自 {}），后发现的同名工具已忽略", name, group);
                continue;
            }
            all.add(cb);
            infos.add(new ToolInfo(name, cb.getToolDefinition().description(), group));
        }
    }

    /**
     * 按智能体的工具装配声明解析本轮应挂载的工具集：{@code null}/空白 → 全量（向后兼容）；
     * {@code "[]"} → 空数组；{@code ["a","b"]} → 白名单（未知名忽略并告警）。配置非法（JSON 解析失败）
     * 时回退全量并告警，绝不因一条配置错误中断对话。
     */
    public ToolCallback[] resolve(String toolsJson) {
        if (toolsJson == null || toolsJson.isBlank()) return toolCallbacks;
        List<String> names = new ArrayList<>();
        try {
            for (Object o : JSONUtil.parseArray(toolsJson)) {
                if (o != null) names.add(String.valueOf(o).trim());
            }
        } catch (Exception e) {
            log.warn("工具装配解析失败，回退全量工具：{}", toolsJson, e);
            return toolCallbacks;
        }
        if (names.isEmpty()) return EMPTY;
        List<ToolCallback> picked = new ArrayList<>(names.size());
        for (String n : names) {
            ToolCallback cb = byName.get(n);
            if (cb != null) {
                picked.add(cb);
            } else {
                log.warn("工具装配：未知工具名 {}（已忽略）；可选工具={}", n, byName.keySet());
            }
        }
        return picked.toArray(ToolCallback[]::new);
    }
}
