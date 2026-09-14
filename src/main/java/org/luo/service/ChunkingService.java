package org.luo.service;

import org.luo.enums.ChunkStrategy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分片器：按 {@link ChunkStrategy} 把一篇文档切成不超过 {@value #DEFAULT_MAX_CHARS} 字的知识块，
 * 相邻块默认保留 {@value #DEFAULT_OVERLAP} 字重叠（上一块尾部拼到下一块开头），避免语义恰好落在块边界被硬切丢失。
 * <p>
 * 四种策略只在「优先在哪种语义边界断句」上不同：FIXED 固定窗口；PARAGRAPH 按空行分自然段；
 * RECURSIVE 段落间贪心合并（块更饱满、块数更少，参考 LangChain RecursiveCharacterTextSplitter）；
 * MARKDOWN 按 {@code #~######} 分节、子块自带标题前缀。语义聚合类（后三种）整块预算为
 * {@code maxChars - overlap}（为重叠留空间），超预算的单元按「段落 → 行 → 句子 → 字符」逐级下钻；
 * 切完统一缝合重叠，单块总长仍 ≤ maxChars。句子边界用零宽后行断言切分，标点随块保留。
 * <p>
 * 无状态纯函数组件，上传入库与「重新分片」共用，保证同策略同重叠下结果一致。
 */
@Service
public class ChunkingService {

    /** 单块最大字符数（中文约 600 字/块），超长向下钻，仍超长则硬切。 */
    public static final int DEFAULT_MAX_CHARS = 600;

    /** 相邻知识块默认重叠字符数。 */
    public static final int DEFAULT_OVERLAP = 60;

    /** 重叠字符数上限（防止重叠过大挤压有效内容）。 */
    public static final int MAX_OVERLAP = 200;

    /** 行级、句子级分隔符（超长段落下钻用；顺序：先按行、再按句）。 */
    private static final String[] LINE_AND_SENTENCE_SEPS = {"(?<=\n)", "(?<=[。！？!?；;])"};

    /** 按策略切块（默认重叠 {@value #DEFAULT_OVERLAP}）。 */
    public List<String> chunk(String text, ChunkStrategy strategy) {
        return chunk(text, strategy, DEFAULT_OVERLAP);
    }

    /**
     * 按策略与重叠字数切块。
     *
     * @param overlap 相邻块重叠字符数（&lt;0 按 0、&gt;{@link #MAX_OVERLAP} 截断）
     * @return 知识块列表（已去空白块；FIXED 由滑窗保证重叠，其余策略经缝合后亦有重叠）
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
        // FIXED 滑窗自带重叠；其余策略聚合时预留了空间，这里统一缝合补重叠
        if (s != ChunkStrategy.FIXED && ov > 0 && blocks.size() > 1) {
            blocks = ctx.stitch(blocks);
        }
        return blocks;
    }

    /**
     * 单次分片的上下文：携带重叠字数与聚合预算。
     * <p>
     * 关键约定：语义整块与多单元聚合共用同一预算 {@code limit = maxChars - overlap}
     * ——超预算的单元向下钻，为缝合重叠留空间；缝合后单块不超过 maxChars。
     */
    private static final class Ctx {

        /** 重叠字符数（已规范化到 [0, MAX_OVERLAP]）。 */
        final int ov;

        /** 语义整块 / 聚合预算 = maxChars - ov（至少 100）。 */
        final int limit;

        Ctx(int ov) {
            this.ov = ov;
            this.limit = Math.max(100, DEFAULT_MAX_CHARS - ov);
        }

        // ==================== 四种策略 ====================

        /** 固定长度：滑动窗口切分。 */
        List<String> fixed(String text) {
            return slidingSplit(text);
        }

        /** 按空行分自然段，整段超预算才按「行 → 句 → 字符」下钻。 */
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

        /** 递归字符：段落间贪心合并到预算，单段超预算交给 {@code splitBy} 逐级下钻。 */
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

        /** Markdown 标题感知：按 {@code #~######} 分节，超长节正文递归切分且每个子块拼标题前缀；无标题行则回退递归切分。 */
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

        /** 输出一个 md 节：无标题节按递归切；有标题节不超预算整块输出，超限则子块全部带标题前缀。 */
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
            // 标题占掉预算，正文子块都拼标题作为上下文，缝合后仍 ≤ maxChars
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
         * 按分隔符层级贪心切块：用当前级分隔符切子段并尽量合并到预算，合不下的超长子段递归下一级
         * （行 → 句 → 字符）。分隔符用零宽断言保留在子段末尾，避免丢标点/换行。
         */
        private void splitBy(String text, String[] seps, int idx, List<String> out) {
            if (text.length() <= limit) {
                out.add(text.strip());
                return;
            }
            if (idx >= seps.length) {
                out.addAll(slidingSplit(text));   // 无更细分隔符：滑窗兜底（窗口间共享 overlap）
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

        /** 缝合重叠：每块头部拼上「上一块尾部至多 ov 字符」，单块超 maxChars 时自动缩小重叠量。 */
        List<String> stitch(List<String> blocks) {
            if (ov <= 0 || blocks.size() < 2) {
                return blocks;
            }
            List<String> out = new ArrayList<>(blocks.size());
            String prev = blocks.get(0);
            out.add(prev);
            for (int i = 1; i < blocks.size(); i++) {
                String cur = blocks.get(i);
                // 重叠量 = min(ov, 上一块长度, 当前块剩余空间)；取自「原始上一块」尾部，结果可预测
                int take = Math.min(ov, prev.length());
                take = Math.min(take, Math.max(0, DEFAULT_MAX_CHARS - cur.length()));
                String head = take > 0 ? prev.substring(prev.length() - take) : "";
                String merged = head + cur;
                out.add(merged);
                prev = blocks.get(i);   // 下一轮重叠仍取自原始块（非缝合后），保证重叠内容来自正文
            }
            return out;
        }

        // ==================== 纯字符工具 ====================

        /** 滑动窗口切分：窗口 = maxChars、步长 = maxChars - overlap；ov=0 时退化为等分。 */
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

        /** 按固定长度等分（字符级兜底，块间无重叠）。 */
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

    /** 按空行切分为段落列表（段内行用换行连接，多余空行丢弃）。 */
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
