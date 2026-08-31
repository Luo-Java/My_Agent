package org.luo.config;

import lombok.extern.slf4j.Slf4j;
import org.luo.tool.ToolRegistry;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 宽容的工具回调解析器：模型调用不存在的工具时，不再让框架抛异常打崩整轮对话。
 * <p>
 * 背景：{@code DefaultToolCallingManager} 对 {@code toolCallbackResolver.resolve()} 返回
 * {@code null} 的工具名会直接 {@code throw new IllegalStateException("No ToolCallback found...")}，
 * 异常冲出框架的工具循环，导致整轮对话以「对话出错」告终，模型得不到任何反馈，反思纠错回路失效。
 * <p>
 * 本解析器对未命中的工具名返回一个<b>兜底 ToolCallback</b>：它的 {@code call()} 返回
 * 「该工具不存在 + 当前可用工具清单 + 近似名提示」作为工具结果喂回模型，让模型在 Spring AI
 * 自带的工具循环里自我纠正（正好接上 eduanalyst 提示词的 REFLECT 反思步骤）。
 * <p>
 * 通过 {@code @Bean/@Component} 提供，利用自动配置的 {@code @ConditionalOnMissingBean}
 * 替换默认解析器，并被注入 {@code ToolCallingManager}。
 */
@Slf4j
@Component
public class LenientToolCallbackResolver implements ToolCallbackResolver {

    private final Map<String, ToolCallback> byName = new HashMap<>();

    /** 可用工具清单文本（给模型看的纠正信息）。 */
    private final String toolListText;

    public LenientToolCallbackResolver(ToolRegistry toolRegistry) {
        for (ToolCallback cb : toolRegistry.getToolCallbacks()) {
            byName.put(cb.getToolDefinition().name(), cb);
        }
        toolListText = byName.keySet().stream().sorted().collect(Collectors.joining(", "));
        log.info("宽容工具解析器就绪：共 {} 个可用工具：{}", byName.size(), toolListText);
    }

    @Override
    public ToolCallback resolve(String toolName) {
        ToolCallback hit = byName.get(toolName);
        if (hit != null) {
            return hit;
        }
        // 未命中：返回兜底回调而非 null，避免 DefaultToolCallingManager 抛 IllegalStateException 打崩整轮
        log.warn("模型调用了不存在的工具「{}」，返回兜底提示让其在循环内自我纠正（可用工具：{}）",
                toolName, toolListText);
        return new UnknownToolCallback(toolName, toolListText, suggestNearest(toolName));
    }

    /** 近似名提示：包含关系 + 编辑距离 ≤ 3。 */
    private List<String> suggestNearest(String requested) {
        List<String> suggestions = new ArrayList<>();
        String lower = requested.toLowerCase();
        for (String name : byName.keySet()) {
            String nl = name.toLowerCase();
            if (nl.contains(lower) || lower.contains(nl)) {
                suggestions.add(name);
                continue;
            }
            if (levenshtein(lower, nl) <= 3) {
                suggestions.add(name);
            }
        }
        return suggestions.stream().distinct().sorted().toList();
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            System.arraycopy(cur, 0, prev, 0, cur.length);
        }
        return prev[b.length()];
    }

    /**
     * 兜底工具回调：工具定义用「请求的工具名」占位（保证 ToolResponse 记录的是模型调用的名字），
     * {@code call()} 返回给模型的纠正信息，让模型在框架的工具循环里自行选择正确工具重试。
     */
    private record UnknownToolCallback(String requestedName, String available, List<String> suggestions)
            implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(requestedName)
                    .description("占位工具：模型调用了不存在的工具，call() 会返回可用工具清单以纠正模型。")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().returnDirect(false).build();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            StringBuilder sb = new StringBuilder();
            sb.append("工具调用失败：不存在名为「").append(requestedName).append("」的工具，请勿编造工具名。\n");
            sb.append("当前可用工具：").append(available).append("\n");
            if (!suggestions.isEmpty()) {
                sb.append("与你的调用相近的工具可能是：").append(String.join(", ", suggestions)).append("\n");
            }
            sb.append("请从上述工具中选择正确的工具重新调用。若你的意图是执行 SQL：");
            sb.append("先用 describe_table/sample_rows 确认表结构与取值，再 validate_sql 预检，最后 query 执行。");
            return sb.toString();
        }
    }
}
