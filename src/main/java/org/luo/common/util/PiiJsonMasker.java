package org.luo.common.util;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.ArrayList;

/**
 * <b>结构化</b> JSON 脱敏：解析后<b>只替换字符串值</b>，再序列化回去。
 *
 * <h2>为什么不直接对整串调 {@link PiiMasker#mask}</h2>
 * 整串替换有两个致命问题：
 * <ol>
 *   <li><b>产出非法 JSON</b>：PII 恰好落在<b>数字值</b>位置时（如 {@code {"code":13800138000}}），
 *       替换后会变成无引号的 {@code {"code":138****8000}} —— 任何 {@code JSON.parse} 的消费方直接失败。
 *       本项目的 {@code agent_trace.plan_json} 由前端解析，表现为追踪面板白屏；</li>
 *   <li><b>破坏跨表逐字匹配</b>：{@code conversation.clarify_state} 与消息正文同为用户输入，
 *       整串遮蔽后的长度与内容都与原串不可比。</li>
 * </ol>
 * 只替换字符串值就没有这两个问题：数字、布尔、null 全部原样，字符串仍然是合法字符串。
 *
 * <h2>为什么必须保 null（原实现踩过的坑）</h2>
 * {@code JSONUtil.parse} 会<b>静默丢弃</b>所有 JSON null：{@code {"a":null,"b":1}} 变 {@code {"b":1}}，
 * 数组 {@code [1,null,2]} 变 {@code [1,2]}（下标整体前移）。落库结构就与原始输出不一致了，
 * 消费方按下标渲染会错位一格。因此这里：
 * <ul>
 *   <li>对象用 {@link JSONUtil#parseObj}（保留 null 字段）；</li>
 *   <li>顶层非对象（数组 / 标量）回落 {@link JSONUtil#parse} —— 因为 {@code parseObj} 遇到顶层数组会抛；</li>
 *   <li>{@link #maskNode} 里 {@code JSONNull} 单独一条分支原样返回。这是<b>必须</b>的一步：
 *       Hutool 里 JSON null 读出来是 {@code JSONNull} 实例而非 Java {@code null}，
 *       且它 {@code toString()} 就是字符串 {@code "null"} —— 不拦的话会被当成普通文本送去脱敏，
 *       存回去变成 {@code "null"}（文本），把 null 变成了字符串。</li>
 * </ul>
 *
 * <h2>失败时怎么办</h2>
 * 解析失败（本来就不是合法 JSON）时<b>退回整串脱敏</b>：它的可解析性已经没了，但内容安全仍要守住。
 * 返回 {@code null}/{@code 空白} 原样返回，不做任何处理。
 */
public final class PiiJsonMasker {

    private PiiJsonMasker() {
    }

    /**
     * 结构化脱敏一个 JSON 串。
     *
     * @param json 原始 JSON 串，可为 null / 空白 / 非法 JSON
     * @return 脱敏后的串；入参为 null 或空白时原样返回；解析失败时返回整串脱敏结果
     */
    public static String mask(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            return JSONUtil.toJsonStr(maskNode(parseLoose(json)));
        } catch (Exception e) {
            return PiiMasker.mask(json);
        }
    }

    /**
     * 能解析成对象就用 {@code parseObj}（保 null 字段），否则回落 {@code parse}。
     * <p>
     * 为什么要判首字符而不是直接 catch 重试：{@code parseObj} 对顶层数组抛的是
     * {@code JSONException}，而「解析失败」与「顶层不是对象」两种情况要走的兜底策略不同
     * （前者整串脱敏，后者仍应结构化处理），不能用同一个 catch 混掉。
     * <p>
     * <b>顶层数组有个必须知道的缺口</b>：{@code JSONUtil.parse} 对<b>数组里的 null 元素</b>同样会丢
     * （{@code [1,null,2]} → {@code [1,2]}，下标前移）。{@code parseObj} 能保对象字段但拒绝顶层数组，
     * 而 Hutool 没有「保 null 的数组解析」入口 —— 故数组里若真的含 null，其结构在这里无法完整保留。
     * 本项目的两处 JSON 列（{@code plan_json} 恒为对象、{@code clarify_state} 恒为对象）都不受影响，
     * 这里如实记录边界而不是假装覆盖了。
     */
    private static Object parseLoose(String json) {
        String trimmed = json.trim();
        if (trimmed.startsWith("{")) {
            return JSONUtil.parseObj(trimmed);
        }
        return JSONUtil.parse(trimmed);
    }

    /**
     * 递归遍历：字符串值脱敏，容器原地下钻。
     * <p>
     * 键序不变：Hutool 的 {@code JSONObject}/{@code JSONArray} 保序（{@code PiiMasker} 内部也因此
     * 用 {@code Collections.unmodifiableMap} 而非 {@code Map.copyOf}）。
     */
    private static Object maskNode(Object node) {
        if (node == null || node instanceof JSONNull) {
            return JSONNull.NULL;
        }
        if (node instanceof String s) {
            return PiiMasker.mask(s);
        }
        if (node instanceof JSONObject o) {
            for (String k : new ArrayList<>(o.keySet())) {
                o.set(k, maskNode(o.get(k)));
            }
            return o;
        }
        if (node instanceof JSONArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, maskNode(arr.get(i)));
            }
            return arr;
        }
        return node;
    }
}
