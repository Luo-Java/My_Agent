package org.luo.ai.tool;

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
import java.util.Set;

/**
 * 工具注册表：启动时把两类工具统一预解析为 {@link ToolCallback}[]，并建「工具名 → 回调」索引，供按智能体装配时
 * 按名过滤（见 {@link #resolve(String)}）。两类都只需 {@code @Component} + 实现接口，本类与业务代码均无需改动：
 * <ul>
 *   <li><b>注解式</b>：{@link ToolProvider} 实现类，方法标 {@code @Tool}，反射解析。</li>
 *   <li><b>动态式</b>：{@link ToolCallbackSource} 实现类，直接产出回调（远端工具运行时才知道有哪些）。</li>
 * </ul>
 * <p>
 * 另有<b>第三类「动态工具」</b>：实例依赖调用方上下文、无法在构造期注册（如 {@link SubAgentTool} 的候选清单随库
 * 变化），本类只为它登记元信息供前端勾选（{@link #DYNAMIC_TOOL_NAMES}），实例由调用方每轮现构，且需在白名单里
 * 显式声明才挂载（见 {@link #dynamicToolRequested}）。
 * <p>
 * 红线：工具名取 {@code ToolDefinition.name()}（{@code @Tool} 未指定 name 时即方法名），是 {@code agent.tools_json}
 * 白名单的匹配依据；<b>同名工具保留先注册者（注解式优先）并告警</b>，改动 {@code @Tool} 方法名会使既有白名单失配。
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

    /**
     * 动态工具名：实例依赖<b>调用方</b>上下文（如「可转交给谁」随库变化），无法在构造期注册实例，
     * 只能登记元信息（{@link #availableTools}）供前端勾选，实例由调用方每轮现场构造。
     * <p>
     * 出现在 {@code tools_json} 白名单里时，{@link #resolve} 静默跳过而不告警——它不是「配错了的名字」。
     */
    private static final Set<String> DYNAMIC_TOOL_NAMES = Set.of(SubAgentTool.TOOL_NAME);

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
        // 动态工具只登记「元信息」供前端勾选，不登记实例：它的候选清单依赖调用方智能体，只能每轮现构。
        if (!index.containsKey(SubAgentTool.TOOL_NAME)) {
            infos.add(SubAgentTool.toolInfo());
        }
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
            } else if (!DYNAMIC_TOOL_NAMES.contains(n)) {
                log.warn("工具装配：未知工具名 {}（已忽略）；可选工具={}", n, byName.keySet());
            }
        }
        return picked.toArray(ToolCallback[]::new);
    }

    /**
     * 该装配声明是否<b>显式</b>要求某个动态工具（如 {@code call_agent}）。
     * <p>
     * <b>只认白名单</b>：{@code null}/空白（=挂全量）一律返回 false。动态工具是策略性能力，不该随「全量」
     * 默认下发给所有智能体——详见 {@link SubAgentTool} 类注释。配置非法时同样返回 false（宁可少挂，不误挂）。
     */
    public boolean dynamicToolRequested(String toolsJson, String toolName) {
        if (toolsJson == null || toolsJson.isBlank() || toolName == null) return false;
        try {
            for (Object o : JSONUtil.parseArray(toolsJson)) {
                if (o != null && toolName.equals(String.valueOf(o).trim())) return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }
}
