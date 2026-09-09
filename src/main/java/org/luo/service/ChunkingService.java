package org.luo.service;

import org.luo.enums.ChunkStrategy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分片器：按 {@link ChunkStrategy} 将一篇文档切成一列不超过 {@code maxChars}（默认 600）的知识块，
 * 相邻两块之间默认保留 {@value #DEFAULT_OVERLAP} 字符的<b>重叠</b>（上一块尾部原文拼到下一块开头），
 * 避免检索时语义恰好落在块边界被硬切丢失——同一处内容在相邻两块都出现，向量命中更稳。
 * <p>
 * 四种策略在「优先在哪种语义边界断句」上取舍：
 * <ul>
 *   <li><b>FIXED</b>：无视语义，按固定窗口切（窗口 = maxChars，步长 = maxChars - overlap，天然重叠）；</li>
 *   <li><b>PARAGRAPH</b>：以空行为界保留自然段落（段落内部行用换行连接）；整段超长时按「行 → 句子 → 字符」下钻；</li>
 *   <li><b>RECURSIVE</b>：段落之间尽量合并填充到「maxChars - overlap」预算（块更饱满、块数更少、为重叠留空间），
 *       超长段落再按行/句子下钻；参考 LangChain RecursiveCharacterTextSplitter 的分隔符逐级思想；</li>
 *   <li><b>MARKDOWN</b>：按 {@code #~######} 标题分节，每个子块自带标题前缀（检索时上下文不丢）；
 * </ul>
 * 句子边界使用零宽后行断言 {@code (?<=[。！？!?；;])} 切分，句号等标点随块保留，不丢失信息。
 * 语义聚合类策略（PARAGRAPH/RECURSIVE/MARKDOWN）的<b>整块语义上限为 maxChars - overlap</b>（为重叠预留空间）：
 * 语义单元（段落 / 句子）超过该预算即向下钻拆分；切完后统一执行「缝合」——给每块头部拼上
 * 上一块尾部至多 overlap 字符，单块总长仍不超过 maxChars。FIXED 与超长兜底用滑动窗口
 * （窗口 maxChars、步长 maxChars - overlap）切分，相邻窗口天然共享 overlap 字符。
 * 唯一会缩小重叠量的情形：上一块不足 overlap 字符（如文本尾部的短块），此时重叠=上一块全长。
 * <p>
 * 本类为纯函数组件（无状态），由 {@link KbService} 注入使用；上传入库与「重新分片」共用同一套切分逻辑，
 * 保证同策略同重叠下结果一致。
 */
@Service
public class ChunkingService {

    /** 单块最大字符数：超过上限的文本会向更细的分隔符下钻，仍超长则按该字符数硬切（中文约 600 字/块）。 */
    public static final int DEFAULT_MAX_CHARS = 600;

    /** 相邻知识块之间的默认重叠字符数（上一块尾部拼到下一块开头，检索跨边界命中更稳）。 */
    public static final int DEFAULT_OVERLAP = 60;

    /** 重叠字符数上限（防止重叠过大挤压有效内容）。 */
    public static final int MAX_OVERLAP = 200;

    /** 行级、句子级分隔符（用于超长段落的下钻切分；顺序：先按行、再按句）。 */
    private static final String[] LINE_AND_SENTENCE_SEPS = {"(?<=\n)", "(?<=[。！？!?；;])"};

    /**
     * 按指定策略将全文切成若干知识块（使用默认重叠 {@value #DEFAULT_OVERLAP}）。
     *
     * @param text     原始全文（解析出的文本）；null / 空白返回空列表
     * @param strategy 分片策略；null 回退 {@link ChunkStrategy#defaultStrategy()}
     * @return 知识块列表（已去除空白块）
     */
    public List<String> chunk(String text, ChunkStrategy strategy) {
        return chunk(text, strategy, DEFAULT_OVERLAP);
    }

    /**
     * 按指定策略与重叠字数将全文切成若干知识块。
     *
     * @param text     原始全文（解析出的文本）；null / 空白返回空列表
     * @param strategy 分片策略；null 回退 {@link ChunkStrategy#defaultStrategy()}
     * @param overlap  相邻块之间的重叠字符数（0 = 不重叠；小于 0 按 0、大于 {@link #MAX_OVERLAP} 截断）
     * @return 知识块列表（已去除空白块；FIXED 由滑窗保证重叠，其余策略经缝合后块间亦有重叠）
     */
    public List<String> chunk(String text, ChunkStrategy strategy, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        ChunkStrategy s = strategy == null ? ChunkStrategy.defaultStrategy() : strategy;
        int ov = Math.max(0, Math.min(overlap, MAX_OVERLAP));
        String t = text.strip();
        Ctx ctx = new Ctx(ov);
        List<String> blocks = switch (s) {
            case FIXED -> ctx.fixed(t);
            case PARAGRAPH -> ctx.paragraph(t);
            case RECURSIVE -> ctx.recursive(t);
            case MARKDOWN -> ctx.markdown(t);
        };
        // FIXED 用滑动窗口自身已带重叠；其余策略聚合时预留了空间，这里统一缝合补重叠
        if (s != ChunkStrategy.FIXED && ov > 0 && blocks.size() > 1) {
            blocks = ctx.stitch(blocks);
        }
        return blocks;
    }

    /**
     * 单次分片的上下文：携带本次调用的重叠字数与聚合预算。
     * <p>
     * 关键约定：<b>语义整块（段落 / 句子单元）与多单元聚合共用同一预算 limit = maxChars - overlap</b>
     * ——超过预算的单元向下钻拆分，为缝合重叠预留空间；缝合后单块总长不超过 maxChars。
     * 超长无分隔符的兜底用滑动窗口切（窗口 maxChars、步长 maxChars - overlap）。
     */
    private static final class Ctx {

        /** 重叠字符数（已规范化到 [0, MAX_OVERLAP]）。 */
        final int ov;

        /** 语义整块 / 聚合预算 = maxChars - ov（至少 100），缝合重叠后总长仍 ≤ maxChars。 */
        final int limit;

        Ctx(int ov) {
            this.ov = ov;
            this.limit = Math.max(100, DEFAULT_MAX_CHARS - ov);
        }

        // ==================== 四种策略 ====================

        /** 固定长度：按滑动窗口切，窗口 = maxChars、步长 = maxChars - overlap → 相邻窗口共享 overlap 字符。 */
        List<String> fixed(String text) {
            return slidingSplit(text);
        }

        /**
         * 按段落：空行分隔的自然段落各自成块（段落内部行保持换行连接，语义完整）；
         * 整段超过预算（maxChars - overlap）才按「行 → 句子 → 字符」下钻切分，为缝合重叠预留空间。
         */
        List<String> paragraph(String text) {
            List<String> out = new ArrayList<>();
            for (String para : splitParagraphs(text)) {
                if (para.isBlank()) {
                    continue;
                }
                if (para.length() <= limit) {
                    out.add(para.strip());
                } else {
                    splitBy(para, LINE_AND_SENTENCE_SEPS, 0, out);
                }
            }
            return out;
        }

        /**
         * 递归字符：段落间贪心合并（相邻短段拼进同一块，块更饱满、总数更少），聚合与整块预算均为
         * maxChars - overlap；单段超过预算时交给 {@code splitBy} 逐级下钻（行 → 句 → 字符）。
         */
        List<String> recursive(String text) {
            List<String> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (String para : splitParagraphs(text)) {
                if (para.isBlank()) {
                    continue;
                }
                if (cur.isEmpty()) {
                    if (para.length() <= limit) {
                        cur.append(para);
                    } else {
                        splitBy(para, LINE_AND_SENTENCE_SEPS, 0, out);
                    }
                } else if (cur.length() + 1 + para.length() <= limit) {
                    cur.append('\n').append(para);
                } else {
                    out.add(cur.toString().strip());
                    cur.setLength(0);
                    if (para.length() <= limit) {
                        cur.append(para);
                    } else {
                        splitBy(para, LINE_AND_SENTENCE_SEPS, 0, out);
                    }
                }
            }
            if (!cur.isEmpty()) {
                out.add(cur.toString().strip());
            }
            return out;
        }

        /**
         * Markdown 标题感知：按 {@code # ~ ######} 行分节，节首标题行保留；
         * 整节不超限则标题+正文成一块；超限则正文按递归切分，每个子块都拼上标题前缀
         * （检索命中的子块自带章节上下文）。文档中无标题行时整体回退为递归切分。
         */
        List<String> markdown(String text) {
            String[] lines = text.split("\r?\n", -1);
            boolean anyHeading = false;
            for (String raw : lines) {
                if (isHeading(raw.strip())) {
                    anyHeading = true;
                    break;
                }
            }
            if (!anyHeading) {
                return recursive(text);
            }
            List<String> out = new ArrayList<>();
            StringBuilder body = new StringBuilder();
            String curTitle = null;
            for (String raw : lines) {
                String t = raw.strip();
                if (t.isEmpty()) {
                    continue;
                }
                if (isHeading(t)) {
                    flushMdSection(curTitle, body, out);
                    curTitle = t;
                    body.setLength(0);
                } else {
                    body.append(t).append('\n');
                }
            }
            flushMdSection(curTitle, body, out);
            return out;
        }

        /** 输出一个 md 节：无标题节按普通递归切；有标题节整块不超过预算直接输出，超限则子块全部带标题前缀。 */
        private void flushMdSection(String title, StringBuilder body, List<String> out) {
            String content = body.toString().strip();
            if (title == null || title.isEmpty()) {
                if (!content.isEmpty()) {
                    if (content.length() <= limit) {
                        out.add(content);
                    } else {
                        splitBy(content, LINE_AND_SENTENCE_SEPS, 0, out);
                    }
                }
                return;
            }
            if (content.isEmpty()) {
                out.add(title);
                return;
            }
            String full = title + "\n" + content;
            if (full.length() <= limit) {
                out.add(full);
                return;
            }
            // 标题占掉预算（预算按 limit 预留重叠空间），正文子块都拼标题作为上下文，缝合后仍 ≤ maxChars
            int budget = Math.max(100, limit - title.length() - 1);
            List<String> sub = new ArrayList<>();
            splitBy(content, LINE_AND_SENTENCE_SEPS, 0, sub);
            StringBuilder sb = new StringBuilder();
            for (String part : sub) {
                if (part.isEmpty()) {
                    continue;
                }
                if (sb.isEmpty()) {
                    if (part.length() <= budget) {
                        sb.append(part);
                    } else {
                        for (String piece : hardSplit(part, budget)) {
                            out.add(title + "\n" + piece);
                        }
                    }
                } else if (sb.length() + 1 + part.length() <= budget) {
                    sb.append('\n').append(part);
                } else {
                    out.add(title + "\n" + sb);
                    sb.setLength(0);
                    if (part.length() <= budget) {
                        sb.append(part);
                    } else {
                        for (String piece : hardSplit(part, budget)) {
                            out.add(title + "\n" + piece);
                        }
                    }
                }
            }
            if (!sb.isEmpty()) {
                out.add(title + "\n" + sb);
            }
        }

        // ==================== 下钻切分工具 ====================

        /**
         * 把一段超过上限的文本按分隔符层级贪心切块：先试当前级分隔符切出子段并尽量合并到聚合预算
         * （maxChars - overlap）；合并不下的子段若仍超长，则递归使用下一级分隔符（行 → 句 → 字符）继续下钻。
         * 分隔符用零宽断言保留在子段末尾，避免切分丢标点/换行。
         */
        private void splitBy(String text, String[] seps, int idx, List<String> out) {
            if (text.length() <= limit) {
                out.add(text.strip());
                return;
            }
            if (idx >= seps.length) {
                out.addAll(slidingSplit(text));   // 无更细分隔符：滑动窗口兜底（窗口间共享 overlap）
                return;
            }
            String[] parts = text.split(seps[idx], -1);
            // 文本不含该级分隔符（只有一个非空子段）→ 直接下钻一级
            int nonBlank = 0;
            for (String p : parts) {
                if (!p.isBlank()) {
                    nonBlank++;
                }
            }
            if (nonBlank <= 1) {
                splitBy(text, seps, idx + 1, out);
                return;
            }
            StringBuilder cur = new StringBuilder();
            for (String p : parts) {
                if (p.isBlank()) {
                    continue;
                }
                if (cur.isEmpty()) {
                    if (p.length() <= limit) {
                        cur.append(p);
                    } else {
                        splitBy(p, seps, idx + 1, out);   // 单个子段自身超长 → 下级继续切
                    }
                } else if (cur.length() + p.length() <= limit) {
                    cur.append(p);
                } else {
                    out.add(cur.toString().strip());
                    cur.setLength(0);
                    if (p.length() <= limit) {
                        cur.append(p);
                    } else {
                        splitBy(p, seps, idx + 1, out);
                    }
                }
            }
            if (!cur.isEmpty()) {
                out.add(cur.toString().strip());
            }
        }

        /** 缝合重叠：给每块头部拼上「上一块的尾部至多 ov 字符」，单块不超 maxChars（超限时自动缩小重叠量）。 */
        List<String> stitch(List<String> blocks) {
            if (ov <= 0 || blocks.size() < 2) {
                return blocks;
            }
            List<String> out = new ArrayList<>(blocks.size());
            String prev = blocks.get(0);
            out.add(prev);
            for (int i = 1; i < blocks.size(); i++) {
                String cur = blocks.get(i);
                // 重叠量 = min(ov, 上一块长度, 当前块剩余空间)；重叠取「原始上一块」尾部，结果可预测
                int take = Math.min(ov, prev.length());
                take = Math.min(take, Math.max(0, DEFAULT_MAX_CHARS - cur.length()));
                String head = take > 0 ? prev.substring(prev.length() - take) : "";
                String merged = head + cur;
                out.add(merged);
                prev = blocks.get(i);   // 下一轮的重叠仍取自原始块（非缝合后），保证重叠内容来自正文
            }
            return out;
        }

        // ==================== 纯字符工具 ====================

        /** 滑动窗口切分：窗口 = maxChars、步长 = maxChars - overlap；ov=0 时退化为固定等分（与旧 hardSplit 一致）。 */
        private List<String> slidingSplit(String text) {
            int max = DEFAULT_MAX_CHARS;
            int step = ov > 0 ? Math.max(1, max - ov) : max;
            List<String> out = new ArrayList<>();
            for (int i = 0; i < text.length(); i += step) {
                String seg = text.substring(i, Math.min(text.length(), i + max)).strip();
                if (!seg.isEmpty()) {
                    out.add(seg);
                }
                if (i + max >= text.length()) {
                    break;   // 窗口已覆盖到文末
                }
            }
            return out;
        }

        /** 按固定长度等分（字符级兜底，与滑窗不同：块与块之间无重叠，供需要精确等分的场景）。 */
        private static List<String> hardSplit(String text, int maxChars) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < text.length(); i += maxChars) {
                String seg = text.substring(i, Math.min(text.length(), i + maxChars)).strip();
                if (!seg.isEmpty()) {
                    out.add(seg);
                }
            }
            return out;
        }
    }

    /** 按空行切分文本为段落列表（段落内部行用换行连接，段落之间多余空行丢弃）。 */
    private static List<String> splitParagraphs(String text) {
        List<String> paras = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("\r?\n", -1)) {
            String t = line.strip();
            if (t.isEmpty()) {
                if (!cur.isEmpty()) {
                    paras.add(cur.toString());
                    cur.setLength(0);
                }
            } else if (cur.isEmpty()) {
                cur.append(t);
            } else {
                cur.append('\n').append(t);
            }
        }
        if (!cur.isEmpty()) {
            paras.add(cur.toString());
        }
        return paras;
    }

    /** 是否 Markdown 标题行（1~6 个 # 后跟空白与内容）。 */
    private static boolean isHeading(String line) {
        return line.matches("^#{1,6}\\s+.*");
    }
}
