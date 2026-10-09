package org.luo.ai.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索词项切分：把一句自然语言问题切成「可用于字面匹配的短词项」，供知识库的关键词召回使用。
 * <p>
 * <b>为什么在本地切而不调模型</b>：知识库检索每轮都要跑，多一次 LLM 调用等于给每轮对话加一次可观的
 * 延迟与成本；而这一路的定位只是「补向量召回漏掉的字面命中」，精度要求低于跨会话提词（那边用 LLM 是因为
 * 要跨时间跨度猜「用户当时会怎么写」）。本地切分的代价是词项更笨，但有精排兜底 —— 笨词项多召回几条
 * 无关内容，会在精排被滤掉；反过来若本地切漏了，向量路仍在。
 * <p>
 * <b>中文为何用 2-gram</b>：不引入分词库（Jieba 之类的词典依赖会让「部署一个 jar 就跑」变成「还要带词典」），
 * 而按标点切完整片段又太严 —— 「数学成绩怎么分布」作为一个词项，只有当文档里连续出现这 8 个字才命中。
 * 2-gram 滑窗（数学/学成/成绩/绩怎/怎么/么分/分布）把「连续」放宽成「相邻两字」，是零依赖方案里召回率
 * 最高的选择。代价是会出现「绩怎」这类跨词边界的噪声 gram：它们在真实文档里几乎不可能连续出现，
 * 因此不会带来误召回，只让 SQL 的 OR 链长一点（已有上限约束）。
 * <p>
 * 纯函数、无状态：切分规则决定召回面，必须能被独立验证。
 */
public final class TermExtractor {

    /** 单次切分产出的词项个数上限（限制 SQL 的 OR 链长度；超出即停，优先保留先出现的片段）。 */
    public static final int DEFAULT_MAX_TERMS = 16;

    /** 英文/数字词项的长度上限（异常长的「单词」几乎不可能命中，还白白拉长 SQL）。 */
    private static final int MAX_WORD_CHARS = 32;

    /**
     * 单字停用字：单字本身不构成词项，且<b>由停用字组成的 gram 一律丢弃</b>
     * （如「的了」「是在」这类纯虚词组合，命中它们毫无检索意义）。
     */
    private static final Set<String> STOP_CHARS = Set.of(
            "的", "了", "是", "在", "我", "你", "他", "她", "它", "们", "和", "与", "或", "及", "把", "被",
            "给", "对", "从", "到", "中", "上", "下", "里", "这", "那", "有", "没", "不", "就", "都", "也",
            "还", "再", "要", "会", "能", "得", "地", "过", "着", "吗", "啊", "吧", "呢", "嗯", "哦", "呀");

    /** 整词停用词（含虚词组合与低检索价值的口语词）：整词项命中即丢弃。 */
    private static final Set<String> STOP_TERMS = Set.of(
            "什么", "怎么", "怎样", "如何", "为什", "可以", "能否", "是否", "请问", "帮我", "一下", "哪些",
            "哪个", "的话", "了吗", "一个", "这个", "那个", "然后", "因为", "所以", "但是", "如果", "就是",
            "还是", "已经", "我们", "你们", "他们", "想要", "需要", "告诉", "麻烦");

    private TermExtractor() {
    }

    /** 按默认上限切分。 */
    public static List<String> extract(String query) {
        return extract(query, DEFAULT_MAX_TERMS);
    }

    /**
     * 切分检索词项。
     *
     * @param query    自然语言问题（null/空白 → 空列表）
     * @param maxTerms 词项个数上限（{@code <= 0} → 空列表）
     * @return 去重后的词项（保序：先出现的片段优先）；可能为空（如整句都是虚词）
     */
    public static List<String> extract(String query, int maxTerms) {
        if (query == null || query.isBlank() || maxTerms <= 0) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        StringBuilder buf = new StringBuilder();
        int kind = 0;   // 0=无 1=汉字片段 2=拉丁/数字片段
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            int k = isHan(c) ? 1 : (isWordChar(c) ? 2 : 0);
            if (k == 0 || (kind != 0 && k != kind)) {
                if (buf.length() > 0) {
                    collect(buf.toString(), kind, out, maxTerms);
                    buf.setLength(0);
                    if (out.size() >= maxTerms) {
                        return List.copyOf(out);
                    }
                }
                kind = 0;
            }
            if (k != 0) {
                buf.append(c);
                kind = k;
            }
        }
        if (buf.length() > 0) {
            collect(buf.toString(), kind, out, maxTerms);
        }
        return List.copyOf(out);
    }

    /** 把一个同类片段展开成词项并去重收口（汉字片段 → 2-gram；拉丁片段 → 小写整词）。 */
    private static void collect(String segment, int kind, Set<String> out, int maxTerms) {
        if (kind == 1) {
            if (segment.length() < 2) {
                return;   // 单字（「的」「分」）不作词项：信息量太低，会命中一大片无关内容
            }
            if (segment.length() == 2) {
                addTerm(segment, out, maxTerms);
                return;
            }
            for (int i = 0; i + 2 <= segment.length(); i++) {
                addTerm(segment.substring(i, i + 2), out, maxTerms);
                if (out.size() >= maxTerms) {
                    return;
                }
            }
            return;
        }
        if (kind == 2 && segment.length() >= 2) {
            String w = segment.toLowerCase();
            if (w.length() > MAX_WORD_CHARS) {
                w = w.substring(0, MAX_WORD_CHARS);
            }
            addTerm(w, out, maxTerms);
        }
    }

    /** 收一个词项：先过停用词表再过容量上限。 */
    private static void addTerm(String term, Set<String> out, int maxTerms) {
        if (out.size() >= maxTerms || isStop(term)) {
            return;
        }
        out.add(term);
    }

    /** 是否停用：整词在停用词表里，或汉字 gram 的每个字都是停用字。 */
    private static boolean isStop(String term) {
        if (STOP_TERMS.contains(term)) {
            return true;
        }
        if (term.length() != 2) {
            return false;
        }
        return STOP_CHARS.contains(term.substring(0, 1)) && STOP_CHARS.contains(term.substring(1, 2));
    }

    /** 是否汉字（用 Unicode script 判定，覆盖扩展区，比手写码点区间可靠）。 */
    private static boolean isHan(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    /** 是否可作为拉丁/数字词的一部分（非汉字的字母数字，含下划线与连字符）。 */
    private static boolean isWordChar(char c) {
        return (Character.isLetterOrDigit(c) && !isHan(c)) || c == '_' || c == '-';
    }
}
