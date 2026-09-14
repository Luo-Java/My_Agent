package org.luo.config;

import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.template.ValidationMode;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 提示词集中配置（外置）。所有静态 / 含动态变量的提示词模板统一收编到
 * {@code src/main/resources/prompts.yaml}（经 {@code spring.config.import} 导入），本类以
 * {@code @ConfigurationProperties(prefix = "agent.prompt")} 绑定；改 yaml 即可，无需重新编译。
 * <p>
 * 多行提示词在 yaml 里用字面量块标量 {@code |} 书写，绑定为 {@link List}&lt;String&gt;（每元素一行），
 * 本类用 getter 把列表拼回带 {@code \n} 的完整文本。含动态变量的提示词用 {@code {占位符}} 标记，
 * 由 {@link #render(String, Map)}（Spring AI StTemplateRenderer / ST4）统一渲染；需输出字面大括号时用
 * {@code \{ \}} 转义。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "agent.prompt")
public class PromptProperties {

    /** 通用助手默认提示词（未绑定智能体时使用）。 */
    private List<String> defaultSystem = new ArrayList<>();

    /** 提示词生成指令（generateAgentPrompt 的元提示词）。 */
    private List<String> promptGenerator = new ArrayList<>();

    /** 智能路由决策提示词模板（含 {continuationHint}/{contextBlock}/{agentList} 占位符）。 */
    private List<String> routerSystem = new ArrayList<>();

    /** 追问上下文提示块模板（含 {pendingQuestion} 占位符）。 */
    private List<String> routerContinuationHint = new ArrayList<>();

    /** 对话上下文块模板（含 {recentContext} 占位符）。 */
    private List<String> routerContextBlock = new ArrayList<>();

    /** 动态规划器提示词模板（含 {agentList} 占位符）。 */
    private List<String> plannerSystem = new ArrayList<>();

    /** 参数抽取器提示词模板（含 {schemaText} 占位符）。 */
    private List<String> paramExtractorSystem = new ArrayList<>();

    /** 记忆合并提示词（纯静态）。 */
    private List<String> memoryMergeSystem = new ArrayList<>();

    /** 数据实时性强制规则（追加到声明「数据实时性」原则的智能体提示词之后）。 */
    private List<String> realtimeRule = new ArrayList<>();

    /** 知识库资料块模板（RAG 注入系统提示词；含 {items} 占位符，编号即引用序号）。 */
    private List<String> kbContext = new ArrayList<>();

    /** 检索查询改写提示词（RAG 多轮指代消解；纯静态，输入由调用方拼在 user 消息里）。 */
    private List<String> queryRewriteSystem = new ArrayList<>();

    // ------------------------------------------------------------------
    // 便捷读取器：把 List<String> 拼接为带 \n 的完整文本
    // ------------------------------------------------------------------

    public String defaultSystem() {
        return join(defaultSystem);
    }

    public String promptGenerator() {
        return join(promptGenerator);
    }

    public String routerSystem() {
        return join(routerSystem);
    }

    public String routerContinuationHint() {
        return join(routerContinuationHint);
    }

    public String routerContextBlock() {
        return join(routerContextBlock);
    }

    public String plannerSystem() {
        return join(plannerSystem);
    }

    public String paramExtractorSystem() {
        return join(paramExtractorSystem);
    }

    public String memoryMergeSystem() {
        return join(memoryMergeSystem);
    }

    public String realtimeRule() {
        return join(realtimeRule);
    }

    public String kbContext() {
        return join(kbContext);
    }

    public String queryRewriteSystem() {
        return join(queryRewriteSystem);
    }

    /** 把行列表拼接为带换行的完整文本；空列表返回空串。 */
    private static String join(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines);
    }

    // ------------------------------------------------------------------
    // 模板渲染：用 Spring AI 官方 StTemplateRenderer（ST/StringTemplate 引擎）
    // ------------------------------------------------------------------

    /** 全局唯一的 ST 渲染器：默认 {@code {}} 分隔符，缺失变量不抛异常（原样保留占位符）。 */
    private static final StTemplateRenderer RENDERER = StTemplateRenderer.builder()
            .validationMode(ValidationMode.NONE)
            .build();

    private static final Logger log = LoggerFactory.getLogger(PromptProperties.class);

    /**
     * 用 {@code {占位符}} 语法渲染提示词模板，填充 {@code vars} 中的变量。
     * <p>
     * 注意：模板中的字面大括号（如 JSON 示例 {@code {"route":true}}）必须写成 {@code \{ \}} 转义，
     * 否则会被 ST 当占位符解析。
     * <p>
     * <b>容错</b>：渲染失败（遗漏转义大括号、模板语法错误）时回退返回原模板并记 WARN，
     * 保证「提示词配置错误不阻断对话」——路由/规划/参数抽取全走本方法，一次误改 yaml 不应打崩整轮对话。
     *
     * @param template 含 {@code {占位符}} 的模板文本
     * @param vars     占位符名 → 值的映射；缺失的占位符保持原样不报错
     * @return 渲染后的文本；模板为空或渲染失败时返回原模板
     */
    public static String render(String template, Map<String, ?> vars) {
        if (template == null || template.isBlank()) {
            return template;
        }
        try {
            return RENDERER.apply(template, vars);
        } catch (Exception e) {
            log.warn("提示词模板渲染失败，回退原模板（请检查 prompts.yaml 中是否遗漏转义大括号 \\{ \\}）：{}",
                    e.getMessage());
            return template;
        }
    }
}
