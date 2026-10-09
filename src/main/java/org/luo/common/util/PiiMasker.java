package org.luo.common.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII（个人身份信息）识别与脱敏：把手机号 / 身份证号 / 邮箱 / 银行卡号从文本里找出来并遮住中段。
 * <p>
 * <b>为什么需要</b>：对话内容会进多处持久化 —— {@code chat_message}（消息正文）、{@code agent_trace}
 * （可观测留档）、应用日志。用户随口一句「我的手机号是 138…」就会把敏感信息永久留在这些地方，
 * 而它们既不是业务数据、也没有任何访问控制以外的保护。数据侧安全的第一步就是<b>别把不该留的留下</b>。
 * <p>
 * <b>在哪生效</b>（三处，统一由 {@code agent.pii.enabled} 开关，见 {@code PiiProperties}）：
 * ① {@code chat_message} —— {@code ConversationService} 落库前（{@code saveMessages}、
 * {@code saveClarifyExchange}，含由输入自动生成的会话标题）；
 * ② {@code agent_trace} —— {@code TraceService} 落库前（原文类字段直接遮；{@code plan_json} 走
 * 「只替换字符串值」的结构化脱敏，因为整串替换会破坏 JSON 可解析性）；
 * ③ 应用日志 —— {@code logback-spring.xml} 用 {@code %pii(%m)} 包住消息与异常栈。
 * <b>三处同开同关</b>：{@code feedback.user_input}（取自 {@code chat_message}）要逐字匹配
 * {@code agent_trace.user_message}，分开开关会让该匹配<b>静默失效</b>（表现为转用例偶尔失败、只留一句 WARN）。
 * <p>
 * <b>为什么放在「持久化边界」而不是「收到输入的那一刻」</b>：工具调用、检索、本轮 prompt 都要用原文，
 * 提前遮会让模型这一轮就看不到号码。代价是 {@code chat_message} 遮过之后会被读回、注入<b>下一轮</b>
 * 上下文 —— 即模型此后只看到 {@code 138****8000}。<b>这是刻意的取舍，不是遗漏</b>。
 * <p>
 * <b>幂等</b>：遮过的文本再跑一次不会二次变化（{@code 138****8000} 里已无连续 10 位数字，不会被再次识别），
 * 因此可以对「不确定是否已脱敏」的字段无条件再过一遍 —— 追踪侧就是这么用的。
 * <p>
 * <b>未覆盖</b>（别以为它比实际更强）：知识库文档及其 Chroma 向量副本（{@code kb_chunk}）、上传的附件文件、
 * 裸调用留痕（{@code llm_usage}）、以及 {@code sys_user} 这类业务数据本身的字段。
 * 抽取类产物（{@code conversation_fact} 长期事实、{@code message_feedback}、评测用例）属于<b>间接</b>覆盖 ——
 * 它们的原料是已经遮过的消息正文。
 * <p>
 * <b>为什么保留首尾而不是整串抹掉</b>：运维和排查需要「这确实是个手机号」的判断依据（全是 {@code ***}
 * 看不出类型，也无法与工单里的号码对上），而首尾几位不足以还原完整信息。遮住中段是这两者之间的平衡点。
 * 替换<b>不改变文本长度</b>（中段用等长 {@code *} 填充），这样日志里的偏移量、截断长度都不受影响。
 * <p>
 * <b>不做的事</b>（避免误以为它比实际更强）：不识别姓名/地址/护照/车牌等弱模式信息（中文姓名与地址没有
 * 可靠正则，硬写会大面积误伤正常内容）；不做语义判断（「我的号是 138…」和「客服电话 138…」一视同仁）；
 * 不保证覆盖所有格式（带分隔符的号码、全角数字等需要各自的正则）。
 * <p>
 * 纯函数、无状态、不依赖 Spring：脱敏规则决定「什么会留下」，必须能被独立验证。
 */
public final class PiiMasker {

    /** 识别出的类型。 */
    public enum Kind {
        /** 中国大陆手机号（11 位，1[3-9] 开头）。 */
        PHONE,
        /** 二代身份证号（18 位，含末位校验位 X）。 */
        ID_CARD,
        /** 邮箱地址。 */
        EMAIL,
        /** 银联 / Visa / MasterCard 主卡号。 */
        BANK_CARD
    }

    /** 一处命中：类型 + 在原文中的半开区间 {@code [start, end)}。 */
    public record Hit(Kind kind, int start, int end) {
    }

    /**
     * 各类型的识别正则。
     * <p>
     * {@code (?<!\d)} / {@code (?!\d)} 是<b>必须的边界</b>：少了它们，一长串数字里的任意截断都会被当成
     * 一个「号码」（如订单号 1234567890123456789 会被切成手机号 + 银行卡两段），既误伤又漏报。
     * 银行卡刻意带发行方前缀（62 / 4 / 51-55）：纯 {@code \d{16,19}} 会大面积误伤订单号、流水号。
     */
    private static final Map<Kind, Pattern> PATTERNS = buildPatterns();

    private PiiMasker() {
    }

    private static Map<Kind, Pattern> buildPatterns() {
        Map<Kind, Pattern> m = new LinkedHashMap<>();   // 保序：识别顺序稳定，便于日志与测试对照
        m.put(Kind.PHONE, Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)"));
        m.put(Kind.ID_CARD, Pattern.compile(
                "(?<!\\d)[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?!\\d)"));
        m.put(Kind.EMAIL, Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));
        m.put(Kind.BANK_CARD, Pattern.compile("(?<!\\d)(?:62\\d{14,17}|4\\d{15}|5[1-5]\\d{14})(?!\\d)"));
        // 用 Collections.unmodifiableMap 而非 Map.copyOf：后者不保证迭代顺序，会让上面的「保序」失效
        return Collections.unmodifiableMap(m);
    }

    /** 文本里是否有 PII（调用方可据此跳过不必要的字符串处理）。 */
    public static boolean hasPii(String text) {
        return !detect(text).isEmpty();
    }

    /** PII 命中条数（日志与统计用，不含内容）。 */
    public static int count(String text) {
        return detect(text).size();
    }

    /**
     * 找出全部 PII 区间，<b>保证区间互不重叠且按起点升序</b>。
     * <p>
     * 去重规则：起点相同时取更长的那个（如 18 位数字串同时像身份证与银行卡时，覆盖更完整的胜出）；
     * 与已保留区间有交叠的一律丢弃 —— 交叠替换会让下标错位并产出「半遮半露」的碎片。
     */
    public static List<Hit> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<Hit> raw = new ArrayList<>(4);
        for (Map.Entry<Kind, Pattern> e : PATTERNS.entrySet()) {
            Matcher m = e.getValue().matcher(text);
            while (m.find()) {
                raw.add(new Hit(e.getKey(), m.start(), m.end()));
            }
        }
        if (raw.isEmpty()) {
            return List.of();
        }
        raw.sort((a, b) -> a.start() != b.start()
                ? Integer.compare(a.start(), b.start())
                : Integer.compare(b.end(), a.end()));   // 同起点：长的在前，先占位
        List<Hit> out = new ArrayList<>(raw.size());
        int lastEnd = -1;
        for (Hit h : raw) {
            if (h.start() < lastEnd) {
                continue;   // 与已保留区间交叠，丢弃
            }
            out.add(h);
            lastEnd = h.end();
        }
        return out;
    }

    /**
     * 把文本里的 PII 全部遮住中段；没有命中时<b>原样返回同一实例</b>（调用方可用 {@code ==} 判断是否改过）。
     * null / 空串原样返回。
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<Hit> hits = detect(text);
        if (hits.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        // 从后往前替换：前面的替换会改变后续下标，倒序处理才能让已算好的区间保持有效
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            sb.replace(h.start(), h.end(), maskValue(text.substring(h.start(), h.end()), h.kind()));
        }
        return sb.toString();
    }

    /** 按类型决定首尾保留位数，再加等长 {@code *} 填充中段。 */
    private static String maskValue(String value, Kind kind) {
        return switch (kind) {
            case PHONE -> keep(value, 3, 4);
            case ID_CARD -> keep(value, 6, 4);
            case BANK_CARD -> keep(value, 4, 4);
            case EMAIL -> maskEmail(value);
        };
    }

    /** 保留首 {@code head} 位与尾 {@code tail} 位，中段等长 {@code *}；总长不足时整串遮住（避免「保留」出了大半原文）。 */
    private static String keep(String v, int head, int tail) {
        if (v.length() <= head + tail) {
            return "*".repeat(v.length());
        }
        return v.substring(0, head) + "*".repeat(v.length() - head - tail) + v.substring(v.length() - tail);
    }

    /**
     * 邮箱只遮本地部分（{@code @} 之后原样保留）：域名本身不是个人信息，遮住反而让「是哪个公司的人」
     * 这类运维判断失去依据。本地部分短于 2 位（如 {@code a@x.com}）就整体遮掉 —— 保留 1 个字符等于没遮。
     */
    private static String maskEmail(String v) {
        int at = v.indexOf('@');
        if (at <= 0) {
            return keep(v, 2, 2);
        }
        String local = v.substring(0, at);
        String domain = v.substring(at);
        String masked = local.length() <= 2
                ? "*".repeat(local.length())
                : local.substring(0, 2) + "*".repeat(local.length() - 2);
        return masked + domain;
    }
}
