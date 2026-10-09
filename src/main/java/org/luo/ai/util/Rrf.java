package org.luo.ai.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * RRF（Reciprocal Rank Fusion，倒数秩融合）：把<b>多路独立召回</b>的有序结果合成一路有序结果。
 * <p>
 * <b>为何用秩而不是分数</b>：混合检索的两路各有自己的尺度 —— 向量路是余弦（0~1 且分布挤在高位），
 * 关键词路是「命中词项数」（0~N 的整数）。想按分数加权就得先归一化，而归一化系数需要离线标定，
 * 换个 embedding 模型或换种切词方式就全部失效。RRF 只看<b>名次</b>（{@code 1/(k+rank)}），
 * 天然免疫尺度差异，也不需要调参 —— 这是它在混合检索里成为默认做法的原因。
 * <p>
 * <b>k 的作用</b>：{@code k} 越大，「名次差」带来的分差越小（k=60 时第 1 名与第 2 名的差别约 1.6%）。
 * 取原论文经验值 60：让「多路都排中游」的候选能压过「只有一路排第一」的候选 —— 这正是融合想要的
 * 「共识优先」语义（两路都命中，比一路押宝更可信）。
 * <p>
 * 纯函数、无状态、不依赖 Spring：融合规则是检索质量的核心，必须能被独立验证。
 */
public final class Rrf {

    /** 缺省 k（原论文经验值）。 */
    public static final int DEFAULT_K = 60;

    private Rrf() {
    }

    /**
     * 融合多路召回。
     *
     * @param routes 每路的<b>有序</b>命中（各自按本路相关度降序）；null 或空路直接跳过
     * @param keyFn  取唯一键（两路对同一条数据的判定依据，通常是主键）；返回 null 的条目丢弃
     * @param k      RRF 常数（{@code <= 0} 时取 {@link #DEFAULT_K}）
     * @return 融合结果，按 RRF 分降序；<b>同分时「被更多路命中」的优先</b>，再同分保持先出现的路
     */
    public static <T> List<Fused<T>> fuse(List<List<T>> routes, Function<T, String> keyFn, int k) {
        int kk = k <= 0 ? DEFAULT_K : k;
        Map<String, Accumulator<T>> acc = new LinkedHashMap<>();
        if (routes != null) {
            for (List<T> route : routes) {
                if (route == null || route.isEmpty()) {
                    continue;
                }
                for (int rank = 0; rank < route.size(); rank++) {
                    T item = route.get(rank);
                    if (item == null) {
                        continue;
                    }
                    String key = keyFn.apply(item);
                    if (key == null) {
                        continue;
                    }
                    Accumulator<T> a = acc.computeIfAbsent(key, x -> new Accumulator<>(item));
                    a.score += 1.0 / (kk + rank + 1);
                    a.routes++;
                }
            }
        }
        List<Fused<T>> out = new ArrayList<>(acc.size());
        for (Accumulator<T> a : acc.values()) {
            out.add(new Fused<>(a.item, a.score, a.routes));
        }
        // 稳定排序：同分时靠 routes 数分先后（多路共识优先），再同分保持插入序（先出现的路在前）
        out.sort(Comparator.comparingDouble(Fused<T>::score).reversed()
                .thenComparing(Comparator.comparingInt(Fused<T>::routes).reversed()));
        return out;
    }

    /** 融合结果：命中的原始条目 + 融合分 + 被几路命中。 */
    public record Fused<T>(T item, double score, int routes) {
    }

    /** 累加器（融合过程中的中间态；item 取首次出现的那个实例）。 */
    private static final class Accumulator<T> {
        private final T item;
        private double score;
        private int routes;

        private Accumulator(T item) {
            this.item = item;
        }
    }
}
