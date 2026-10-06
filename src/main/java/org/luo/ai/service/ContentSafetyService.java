package org.luo.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.luo.ai.properties.SafetyProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 内容安全护栏（对话链路输入/输出两侧的收口点）。
 * <p>
 * 只做一件事：拿配置里的正则规则扫文本，告诉调用方「放行 / 拦下」。<b>不做替换、不做改写、不改写请求</b>——
 * 具体怎么处置由调用方决定（输入侧拒绝、输出侧替换为提示文案），这样同一份判定逻辑不会被两种处置方式
 * 各写一遍，也不会出现「这里拦了、那里忘了拦」。
 * <p>
 * <b>规则编译一次</b>：构造期把配置里的正则编译好（{@link Pattern}），避免每轮对话每条规则重复
 * {@code Pattern.compile}。编译失败的规则<b>记录 WARN 并跳过</b>——不静默失效，日志里能看到是哪一条、
 * 错在哪，但也不让一条写错的正则把整个护栏拖垮（其余规则继续生效）。
 * <p>
 * <b>命中记录不落原文</b>：WARN 日志只写「哪条规则命中 + 哪一侧」，不写用户输入/模型输出的内容本身 ——
 * 把被拦内容二次留存进日志，等于绕过了「拦下来」这件事的初衷。
 */
@Slf4j
@Service
public class ContentSafetyService {

    /** 一条已编译的规则：{@code source} 用于日志定位（配置里那一行原文），{@code pattern} 用于匹配。 */
    private record Rule(String source, Pattern pattern) {
    }

    private final SafetyProperties props;

    /** 输入侧已编译规则（构造期一次编译，含跳过的无效规则已记日志）。 */
    private final List<Rule> inputRules;
    /** 输出侧已编译规则。 */
    private final List<Rule> outputRules;

    public ContentSafetyService(SafetyProperties props) {
        this.props = props;
        this.inputRules = compile(props.inputPatterns(), "input");
        this.outputRules = compile(props.outputPatterns(), "output");
        if (props.enabledOn()) {
            log.info("内容安全护栏已启用：输入规则 {} 条 / 输出规则 {} 条 / 单条输入上限 {}",
                    inputRules.size(), outputRules.size(),
                    props.maxInputCharsLimit() > 0 ? props.maxInputCharsLimit() + " 字" : "不限");
        }
    }

    /**
     * 输入侧检查（用户本轮提问）。
     *
     * @return {@code null} = 放行；非 null = 拒绝原因（可直接作为给用户看的文案）
     */
    public String checkInput(String text) {
        if (!props.enabledOn()) return null;
        int max = props.maxInputCharsLimit();
        if (max > 0 && text != null && text.length() > max) {
            // 长度超限与规则命中同一口径处理：都属于「这条输入不被接受」，不必单开一档错误语义
            log.warn("内容安全：输入超长被拦（{} > {} 字）", text.length(), max);
            return "输入内容过长（超过 " + max + " 字），请精简后重试";
        }
        return match(text, inputRules, "输入");
    }

    /**
     * 输出侧检查（模型产出的回复）。
     *
     * @return {@code null} = 放行；非 null = 该回复应被替换为 {@link SafetyProperties#blockedMessage()}
     */
    public String checkOutput(String text) {
        if (!props.enabledOn()) return null;
        return match(text, outputRules, "输出");
    }

    /** 护栏是否生效（调用方可据此完全跳过检查，省掉一次方法调用与分支）。 */
    public boolean enabled() {
        return props.enabledOn();
    }

    /** 命中时的统一提示文案（输入侧 422/error 事件与输出侧替换正文共用同一个来源，避免两处文案漂移）。 */
    public String blockedMessage() {
        return props.blockedMessage();
    }

    /** 逐条匹配，返回首个命中的拒绝原因；未命中返回 null。 */
    private String match(String text, List<Rule> rules, String side) {
        if (text == null || text.isEmpty() || rules.isEmpty()) return null;
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            if (r.pattern().matcher(text).find()) {
                log.warn("内容安全：{}侧命中规则#{}（{}）", side, i + 1, r.source());
                return props.blockedMessage();
            }
        }
        return null;
    }

    /** 编译规则；单条失败记 WARN 后跳过（不静默失效，也不让一条写错的正则拖垮整个护栏）。 */
    private static List<Rule> compile(List<String> patterns, String side) {
        List<Rule> rules = new ArrayList<>();
        if (patterns == null || patterns.isEmpty()) return rules;
        for (String p : patterns) {
            if (p == null || p.isBlank()) continue;
            try {
                rules.add(new Rule(p.strip(), Pattern.compile(p.strip())));
            } catch (PatternSyntaxException e) {
                log.warn("内容安全：{}侧规则正则非法，已跳过该条 —— 规则=「{}」，原因={}", side, p, e.getDescription());
            }
        }
        return rules;
    }
}
