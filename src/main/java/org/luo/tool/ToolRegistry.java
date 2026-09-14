package org.luo.tool;

import cn.hutool.json.JSONUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：Spring 自动注入所有 {@link ToolProvider} 实现类，启动时预解析为 {@link ToolCallback}[]，
 * 并建「工具名 → 回调」索引，供按智能体装配时按名过滤（见 {@link #resolve(String)}）。
 * <p>
 * 新增工具 = 写一个类，加 {@code @Component}，方法标 {@code @Tool}，实现 {@link ToolProvider}，注册表与业务代码均无需改动。
 * <p>
 * <b>工具名</b>取 {@code ToolDefinition.name()}（{@code @Tool} 未指定 name 时即方法名），是 {@code agent.tools_json}
 * 白名单的匹配依据；改动 {@code @Tool} 方法名会使既有白名单失配，此时该工具被忽略并告警（不影响对话）。
 */
@Getter
@Slf4j
@Component
public class ToolRegistry {

    /** 全量工具回调，顺序稳定（按 ToolProvider 的发现顺序）。 */
    private final ToolCallback[] toolCallbacks;

    /** 工具名 → 回调 的索引，用于按智能体白名单过滤。 */
    private final Map<String, ToolCallback> byName;

    /** 工具清单（名/描述/分组），供前端「工具装配」选择界面展示。 */
    private final List<ToolInfo> availableTools;

    /** 空工具集：智能体显式声明「不使用任何工具」（tools_json = "[]"）时返回。 */
    private static final ToolCallback[] EMPTY = new ToolCallback[0];

    /** 工具元信息；{@code group} 为所属 ToolProvider 类名，前端按此分组展示。 */
    public record ToolInfo(String name, String description, String group) {}

    public ToolRegistry(List<ToolProvider> toolProviders) {
        List<ToolCallback> all = new ArrayList<>();
        Map<String, ToolCallback> index = new LinkedHashMap<>();
        List<ToolInfo> infos = new ArrayList<>();
        // 逐个 Provider 解析（而非一次性传入全部对象）：为了记录每个工具的分组来源，供前端分组展示
        for (ToolProvider provider : toolProviders) {
            String group = provider.getClass().getSimpleName();
            ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(provider)
                    .build()
                    .getToolCallbacks();
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
        this.toolCallbacks = all.toArray(ToolCallback[]::new);
        this.byName = index;
        this.availableTools = List.copyOf(infos);
        log.info("自动发现 {} 个工具 Bean，预解析为 {} 个 ToolCallback：{}",
                toolProviders.size(), this.toolCallbacks.length, index.keySet());
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
