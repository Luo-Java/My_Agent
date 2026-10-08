package org.luo.ai.util;

/**
 * 从 LLM 自由文本里截出 JSON 片段的小工具。
 * <p>
 * <b>为什么需要它</b>：项目里所有「要求模型输出严格 JSON」的提示词，模型仍可能用 ``` 代码块包裹、
 * 或在前后加一句「好的，这是我的判断：」。直接把整段丢给 {@code JSONUtil.parseObj} 会抛异常，
 * 于是每个调用点都得写一遍「找第一个 { 和最后一个 }」。
 * <p>
 * 抽在这里而不是各写一份：这类截取逻辑写第二遍时几乎必然出现细微差异（有人用 lastIndexOf('}')、
 * 有人用 indexOf('}')、有人不判 null），而差异的表现是「某个功能偶尔解析失败」——
 * 归因成本远高于这个函数本身。
 * <p>
 * <b>只做截取，不做解析</b>：解析一律由调用方用 Hutool {@code JSONUtil} 完成（项目约定：LLM 的 JSON
 * 只用 Hutool，禁用 Jackson），因为「解析失败之后怎么办」每个调用点各不相同（有的回落旧值、
 * 有的整轮跳过）。
 */
public final class LlmJson {

    private LlmJson() {
    }

    /**
     * 截出文本中<b>第一个</b> JSON 对象（从第一个 {@code &#123;} 到最后一个 {@code &#125;}）。
     * <p>
     * 刻意用 {@code lastIndexOf('}')} 而不是第一个 {@code &#125;}}：截出来的候选要交给 JSON 解析器，
     * 由它来判断是否成对；提前在第一个闭括号处切断，遇到嵌套对象（如 {@code {"a":{"b":1}}}）必然截断。
     *
     * @param text 模型返回的原始文本；null / 空 / 不含大括号时返回 {@code null}
     */
    public static String extractObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }
}
