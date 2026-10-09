package org.luo.ai.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.properties.PromptProperties;
import org.luo.ai.properties.RagProperties;
import org.luo.ai.dto.KbCitation;
import org.luo.ai.entity.Agent;
import org.luo.ai.entity.KnowledgeBase;
import org.luo.ai.entity.KnowledgeChunk;
import org.luo.ai.mapper.KnowledgeBaseMapper;
import org.luo.ai.mapper.KnowledgeChunkMapper;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.luo.ai.infrastructure.chroma.ChromaClient.ChromaHit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import org.luo.ai.chat.ChatComposer;
import org.luo.ai.infrastructure.chroma.ChromaVectorStoreService;
import org.luo.ai.infrastructure.rerank.RerankService;
import org.luo.ai.util.Rrf;
import org.luo.ai.util.TermExtractor;

/**
 * 知识库检索服务（RAG 检索收敛点，只读不写；写入/管理侧见 {@link KbService}）。
 * <p>
 * 把「会话级 RAG 开关 + 路由 agent」翻译成注入系统提示词的「[知识库资料]」文本 + 引用来源
 * （{@link KbCitation}）。开启后自动多库检索：全局「通用知识库」（{@code kb.agent_id IS NULL}）
 * + 本轮路由/绑定智能体的专属库。
 * <p>
 * <b>三段式检索（第一段为混合召回）</b>：① 粗排召回 —— <b>向量 + 关键词两路</b>（见 {@link #searchAll}，
 * 用 {@link Rrf} 融合）→ ② 精排（{@link RerankService}，不可用则降级为向量分 + 严格 {@code min-score} 截断）
 * → ③ 编号注入（套 {@code agent.prompt.kb-context} 模板，同趟产出与编号一一对应的引用）。
 * 注入点：{@link ChatComposer#buildKbContext} 透传。
 * 任何一步失败都只降级、绝不阻断对话——RAG 是增强，不是依赖。
 */
@Slf4j
@Service
public class KbSearchService {

    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final ChromaVectorStoreService chromaStore;
    private final ObjectProvider<EmbeddingModel> embeddingProvider;
    private final RerankService rerankService;
    private final RagProperties props;
    private final PromptProperties promptProperties;

    public KbSearchService(KnowledgeBaseMapper kbMapper,
                           KnowledgeChunkMapper chunkMapper,
                           ChromaVectorStoreService chromaStore,
                           ObjectProvider<EmbeddingModel> embeddingProvider,
                           RerankService rerankService,
                           RagProperties props,
                           PromptProperties promptProperties) {
        this.kbMapper = kbMapper;
        this.chunkMapper = chunkMapper;
        this.chromaStore = chromaStore;
        this.embeddingProvider = embeddingProvider;
        this.rerankService = rerankService;
        this.props = props;
        this.promptProperties = promptProperties;
    }

    /**
     * 组装本轮知识库资料（核心 RAG 入口，供 ChatComposer 注入系统提示词）。
     * 无可用库 / 检索失败 / 无命中 / embedding 未配置一律返回 {@link KbContext#EMPTY}，知识库故障不阻断对话。
     *
     * @param ragEnabled 会话级 RAG 开关（null/false = 不使用）
     * @param agent      本轮路由/绑定的智能体（null = 只查全局库）
     * @param query      用户当前问题
     */
    public KbContext buildKbContext(Boolean ragEnabled, Agent agent, String query) {
        if (!Boolean.TRUE.equals(ragEnabled) || query == null || query.isBlank()) return KbContext.EMPTY;
        try {
            List<KnowledgeBase> targets = ragTargets(agent);
            if (targets.isEmpty()) {
                log.debug("知识库检索：RAG 已开启，但无可用知识库（无全局库 / 无该智能体专属库），本轮不带资料");
                return KbContext.EMPTY;
            }
            List<Hit> recalled = searchAll(targets, query);   // ① 混合粗排召回（向量+关键词，RRF 融合，收敛到 recallK）
            if (recalled.isEmpty()) return KbContext.EMPTY;
            List<Hit> picked = refine(recalled, query);       // ② 精排（不可用则降级为向量分截断）
            if (picked.isEmpty()) return KbContext.EMPTY;
            return render(picked);                            // ③ 编号注入 + 产出引用来源
        } catch (Exception e) {
            log.warn("知识库检索失败，本轮不带资料（不影响对话）：{}", e.getMessage());
            return KbContext.EMPTY;
        }
    }

    // ==================== ① 粗排召回 ====================

    /** 解析目标库（存在才加入）：全局「通用知识库」+ 当前智能体专属库。 */
    private List<KnowledgeBase> ragTargets(Agent agent) {
        List<KnowledgeBase> targets = new ArrayList<>(2);
        KnowledgeBase global = globalKb();   // 只查不创建，避免检索触发建库
        if (global != null) {
            targets.add(global);
        }
        if (agent != null && agent.getId() != null) {
            KnowledgeBase own = kbMapper.selectOne(new QueryWrapper<KnowledgeBase>()
                    .eq("agent_id", agent.getId()).last("LIMIT 1"));
            if (own != null) {
                targets.add(own);
            }
        }
        return targets;
    }

    /** 全局「通用知识库」（agent_id IS NULL，不存在返回 null）；自含查询避免与 KbService 循环依赖。 */
    private KnowledgeBase globalKb() {
        return kbMapper.selectOne(new QueryWrapper<KnowledgeBase>()
                .isNull("agent_id").last("LIMIT 1"));
    }

    /**
     * 检索命中的一条：知识块 + 所属库 + 两个分数 + 被几路命中。
     * <p>
     * <b>为何两个分数</b>：{@code score} 是本条<b>在向量路的相关度</b>（真实余弦或精排分；只被关键词打中的
     * 候选没有余弦，记 0），{@code rrf} 是融合分（未融合＝0）。两者不可互相换算，也不混排 ——
     * {@link #rank()} 只在「融合过」时用 rrf，否则用 score。保留 {@code score} 的意义在于<b>降级可回退</b>：
     * 精排恰好调用失败时，兜底分支靠它筛出「向量本来就认」的候选，行为与改造前完全一致。
     */
    private record Hit(KnowledgeBase kb, KnowledgeChunk chunk, double score, double rrf, int routes,
                       boolean kwHit) {

        /** 向量路命中（余弦分）。 */
        static Hit vector(KnowledgeBase kb, KnowledgeChunk chunk, double score) {
            return new Hit(kb, chunk, score, 0, 1, false);
        }

        /** 关键词路命中：这一路不产生相似度，只比名次，故向量分记 0。 */
        static Hit keyword(KnowledgeBase kb, KnowledgeChunk chunk) {
            return new Hit(kb, chunk, 0, 0, 1, true);
        }

        /** 用精排分换出同源命中（精排分覆盖 score；元数据与融合信息保持不变）。 */
        Hit withScore(double newScore) {
            return new Hit(kb, chunk, newScore, rrf, routes, kwHit);
        }

        /**
         * 打上融合分与命中路数（score 不变：融合不改变「本条在向量路有多大相关度」这个事实）。
         * <p>
         * {@code routeCount > 1} 说明两路都命中了，此时<b>必须把 kwHit 补上</b> —— 融合保留的是
         * 「先出现的那一路」的实例（向量路在前），若不补，两路都命中的条目会被误报成「只被向量命中」。
         */
        Hit withFused(double fusedScore, int routeCount) {
            return new Hit(kb, chunk, score, fusedScore, routeCount, kwHit || routeCount > 1);
        }

        /** 排序键：融合过用 RRF 分，单路用本路相关度分。两类不会混排（融合时全部 rrf > 0）。 */
        double rank() {
            return rrf > 0 ? rrf : score;
        }

        /** 召回方式（引用列表展示用）：{@code both}=两路都命中，{@code keyword}=只被字面命中。 */
        String matchedBy() {
            if (kwHit && routes > 1) {
                return "both";
            }
            return kwHit ? "keyword" : "vector";
        }
    }

    /**
     * 多库统一粗排召回（<b>混合检索</b>）：向量一路 + 关键词一路，两路融合后收敛到 {@code recall-k} 条。
     * <p>
     * 两路各治一种病：向量路抓「说法不同但意思相近」，关键词路抓「术语/专有名词/数字等字面精确的东西」——
     * 「Q3 营收是多少」这类问题向量常常召回不准，字面反而一击即中。融合用 {@link Rrf}（只看名次，
     * 免疫两路分数尺度差异）。
     * <p>
     * <b>关键词一路只在精排可用时参与</b>：融合后必须靠精排收敛，因为 RRF 分不是相似度，无法直接和余弦阈值
     * 比。若精排不可用，兜底分支只有余弦可分（关键词专有候选的 score 是 0），等于白融合一场；与其如此，
     * 不如干脆退回纯向量 —— 降级路径要的是「行为可预期」，不是「多一路不确定性」。
     */
    private List<Hit> searchAll(List<KnowledgeBase> targets, String query) {
        Map<Long, KnowledgeBase> byId = new LinkedHashMap<>();
        for (KnowledgeBase kb : targets) {
            byId.put(kb.getId(), kb);
        }
        List<Long> kbIds = new ArrayList<>(byId.keySet());

        List<Hit> vectorHits = vectorRecall(kbIds, byId, query);
        if (!props.keywordRecallOn() || !rerankService.available()) {
            return vectorHits;
        }
        List<Hit> keywordHits = keywordRecall(kbIds, byId, query);
        if (keywordHits.isEmpty()) {
            return vectorHits;
        }
        if (vectorHits.isEmpty()) {
            return keywordHits;
        }
        return fuse(vectorHits, keywordHits);
    }

    /**
     * 关键词召回：切词 → 有界 {@code LIKE} 查询（按命中词项数降序）→ 收敛到 {@code keyword-recall-k} 条。
     * 任何失败都只返回空表（本轮退化为纯向量一路），绝不阻断对话。
     */
    private List<Hit> keywordRecall(List<Long> kbIds, Map<Long, KnowledgeBase> byId, String query) {
        List<String> terms = TermExtractor.extract(query, TermExtractor.DEFAULT_MAX_TERMS);
        if (terms.isEmpty()) {
            return List.of();
        }
        int limit = props.keywordRecallScanCap();
        List<KnowledgeChunk> rows;
        try {
            rows = chunkMapper.searchByKeyword(kbIds, terms, limit);
        } catch (Exception e) {
            log.warn("知识库关键词召回失败（本轮只用向量一路）：词项={}，原因={}", terms, e.getMessage());
            return List.of();
        }
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        if (rows.size() >= limit) {
            // 截断会影响召回完整性，必须留痕（与 MySQL 向量回退同一口径：降级不静默）
            Long total = chunkMapper.selectCount(new QueryWrapper<KnowledgeChunk>().in("kb_id", kbIds));
            log.warn("关键词召回已按上限截断：本次仅取 {} 块（上限 {}），目标库实际共 {} 块，命中可能不完整。"
                            + "如需提升可调大 agent.rag.keyword-recall-scan-cap",
                    rows.size(), limit, total);
        }
        int k = props.keywordRecallK();
        List<Hit> hits = new ArrayList<>(Math.min(k, rows.size()));
        for (KnowledgeChunk c : rows) {
            KnowledgeBase kb = byId.get(c.getKbId());
            if (kb == null) {
                continue;   // 归属库不在目标内，防御跳过
            }
            hits.add(Hit.keyword(kb, c));
            if (hits.size() >= k) {
                break;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("RAG 关键词召回：词项={}，候选 {} 块 → 取 {} 块", terms, rows.size(), hits.size());
        }
        return hits;
    }

    /**
     * RRF 融合两路召回并按 {@code recall-k} 收敛。收敛是必要的：融合后候选是两路之和，
     * 而精排<b>按文档条数计费</b>，无界放大等于让检索成本翻倍；{@code recall-k} 本就是「交给精排的候选量」。
     */
    private List<Hit> fuse(List<Hit> vectorHits, List<Hit> keywordHits) {
        List<Rrf.Fused<Hit>> fused = Rrf.fuse(List.of(vectorHits, keywordHits),
                h -> String.valueOf(h.chunk().getId()), props.rrfK());
        int cap = props.recallK();
        List<Hit> out = new ArrayList<>(Math.min(cap, fused.size()));
        for (Rrf.Fused<Hit> f : fused) {
            out.add(f.item().withFused(f.score(), f.routes()));
            if (out.size() >= cap) {
                break;
            }
        }
        if (log.isDebugEnabled()) {
            long both = out.stream().filter(h -> h.routes() > 1).count();
            log.debug("RAG 混合召回：向量 {} 块 + 关键词 {} 块 → 融合取 {} 块（其中 {} 块两路都命中）",
                    vectorHits.size(), keywordHits.size(), out.size(), both);
        }
        return out;
    }

    /**
     * 向量一路（<b>改造前的全部召回逻辑</b>，逐字保留）：一次跨全部目标库召回 {@code recall-k} 条候选。
     * Chroma 优先（kb_id 过滤 + 余弦 TopK），失败/无命中回退 MySQL 余弦（<b>有界扫描</b>，
     * 上限 {@code agent.rag.fallback-max-chunks}，避免把整库向量文本拉进堆）。
     * 下限用宽松的 {@code recall-min-score}：这一阶段目标是「别漏」，判相关性交给精排。
     */
    private List<Hit> vectorRecall(List<Long> kbIds, Map<Long, KnowledgeBase> byId, String query) {
        int recall = props.recallK();
        double floor = props.recallMinScore();

        // Chroma 优先：跨库一次查，候选量 = recallK；Java 侧统一排序
        List<ChromaHit> chromaHits = chromaStore.search(kbIds, query, recall, floor);
        if (!chromaHits.isEmpty()) {
            List<Hit> hits = new ArrayList<>(chromaHits.size());
            for (ChromaHit h : chromaHits) {
                KnowledgeBase kb = byId.get(h.kbId());
                if (kb == null) {
                    continue;   // 归属库不在目标内（理论不可能），防御跳过
                }
                KnowledgeChunk c = new KnowledgeChunk();
                c.setId(h.chunkId());
                c.setKbId(kb.getId());
                c.setContent(h.content());
                c.setSource(h.source());
                hits.add(Hit.vector(kb, c, h.score()));
            }
            if (log.isDebugEnabled()) {
                log.debug("RAG 粗排：Chroma 跨 {} 库召回 {} 块（下限 {}，目标 {}），库={}",
                        kbIds.size(), hits.size(), floor, recall, kbNames(new ArrayList<>(byId.values())));
            }
            return hits;
        }

        // 回退 MySQL：一次 IN 查询（有界）。kb_chunk.embedding 是 JSON 文本存的 1024 维向量，
        // 无界读取会把整库向量拉进堆（OOM）；被截断时打 warn 带真实总数，降级不静默。
        int scanCap = props.fallbackMaxChunks();
        List<KnowledgeChunk> all = chunkMapper.selectList(new QueryWrapper<KnowledgeChunk>()
                .select("id", "kb_id", "content", "source", "embedding")
                .in("kb_id", kbIds)
                .orderByAsc("id")
                .last("LIMIT " + scanCap));
        if (all.isEmpty()) return List.of();
        if (all.size() >= scanCap) {
            Long total = chunkMapper.selectCount(new QueryWrapper<KnowledgeChunk>().in("kb_id", kbIds));
            log.warn("RAG 兜底检索已按上限截断：本次仅扫描 {} 块，目标库实际共 {} 块，命中可能不完整。"
                            + "建议恢复 Chroma 服务，或调大 agent.rag.fallback-max-chunks",
                    all.size(), total);
        }
        float[] queryVec = embedOne(query);
        if (queryVec == null) return List.of();
        // top-K 小顶堆：峰值内存与「扫描块数」而非「库总量」成正比，向量文本评分后可被 GC 回收
        PriorityQueue<Hit> heap = new PriorityQueue<>(Math.min(recall, all.size()) + 1,
                Comparator.comparingDouble(Hit::score));
        double best = -1;
        for (KnowledgeChunk c : all) {
            double[] vec = parseVector(c.getEmbedding());
            if (vec == null) {
                continue;   // 无向量 / 向量损坏，跳过
            }
            double s = cosine(queryVec, vec);
            if (s > best) {
                best = s;   // 仅日志：整轮最高分（不受下限影响）
            }
            if (s < floor) {
                continue;   // 低于宽松下限
            }
            KnowledgeBase kb = byId.get(c.getKbId());
            if (kb == null) {
                continue;
            }
            heap.offer(Hit.vector(kb, c, s));
            if (heap.size() > recall) {
                heap.poll();   // 弹出最小，堆内恒为 top-recall
            }
        }
        List<Hit> hits = new ArrayList<>(heap);
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));   // 降序，与 Chroma 分支一致
        if (log.isDebugEnabled()) {
            log.debug("RAG 粗排：回退 MySQL（{} 库扫描 {} 块）召回 {} 块，最高分={}",
                    kbIds.size(), all.size(), hits.size(),
                    best < 0 ? "-" : String.format("%.3f", best));
        }
        return hits;
    }

    // ==================== ② 精排 ====================

    /**
     * 精排并按 {@code top-k} 收敛：可用 → 交叉编码打分，丢弃低于 {@code rerank-min-score} 的候选，取前 top-k
     * （被全滤掉即「本轮无相关资料」，<b>不退回向量分</b>）；不可用/失败 → 降级为向量分降序 +
     * 严格 {@code min-score} 截断（精排上线前的原始行为）。
     */
    private List<Hit> refine(List<Hit> recalled, String query) {
        List<Hit> sorted = new ArrayList<>(recalled);
        // 兜底顺序 = 「本路相关度」降序：融合结果按 RRF 名次分，纯向量结果按余弦（与改造前逐字一致）
        sorted.sort((a, b) -> Double.compare(b.rank(), a.rank()));
        int topK = props.topK();

        // 不按候选条数走不同阈值：早期写 size() > 1，导致「仅 1 条候选」落到兜底分支、
        // 阈值从 rerank-min-score(0.20) 静默变 min-score(0.25)。精排是 query↔单条文档独立打分，与候选数无关。
        if (!sorted.isEmpty() && rerankService.available()) {
            List<String> docs = new ArrayList<>(sorted.size());
            for (Hit h : sorted) {
                docs.add(h.chunk().getContent());
            }
            List<RerankService.Ranked> ranked = rerankService.rerank(query, docs, topK);
            if (ranked != null) {
                List<Hit> picked = new ArrayList<>(Math.min(ranked.size(), topK));
                for (RerankService.Ranked r : ranked) {
                    if (r.index() < 0 || r.index() >= sorted.size()) {
                        continue;   // 响应越界，防御跳过
                    }
                    if (r.score() < props.rerankMinScore()) {
                        continue;   // 相关度不足
                    }
                    picked.add(sorted.get(r.index()).withScore(r.score()));
                    if (picked.size() >= topK) break;
                }
                if (picked.isEmpty()) {
                    log.debug("RAG 精排：召回 {} 块全部低于阈值 {}，本轮不带资料",
                            sorted.size(), props.rerankMinScore());
                } else {
                    log.debug("RAG 精排：召回 {} 块 → 保留 {} 块（模型={}）",
                            sorted.size(), picked.size(), props.rerankModel());
                }
                return picked;
            }
            log.debug("RAG 精排不可用或调用失败，降级为向量分截断（{} 块）", sorted.size());
        }

        // 兜底：向量分降序 + 严格阈值，取 topK（精排上线前的原始行为）。
        // 这里**重排一次**而非沿用入参顺序：入参可能来自 RRF 融合（顺序按名次，与余弦无关），而本分支
        // 只有余弦可判相关 —— 重排后「只被关键词打中」的候选（余弦 0）会被阈值自然滤掉，于是
        // 「精排调用失败」精确退化为改造前的纯向量行为，降级路径不因新增一路而变得更宽松。
        List<Hit> byVector = new ArrayList<>(recalled);
        byVector.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<Hit> picked = new ArrayList<>(topK);
        for (Hit h : byVector) {
            if (h.score() < props.minScore()) break;   // 已降序，首个不达标即全不达标
            picked.add(h);
            if (picked.size() >= topK) break;
        }
        return picked;
    }

    // ==================== ③ 编号注入 + 引用来源 ====================

    /**
     * 渲染带编号的资料块（套 {@code agent.prompt.kb-context} 模板），同趟产出与编号一一对应的引用列表。
     * 模板缺失时回退空串 → 调用方视为无资料。资料正文作为模板变量值注入，不会被 ST 二次解析。
     */
    private KbContext render(List<Hit> hits) {
        StringBuilder items = new StringBuilder(512);
        List<KbCitation> citations = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            int no = i + 1;
            String source = (h.chunk().getSource() == null || h.chunk().getSource().isBlank())
                    ? null : h.chunk().getSource();
            items.append("[").append(no).append("] [").append(h.kb().getName());
            if (source != null) {
                items.append("|").append(source);
            }
            items.append("] ").append(h.chunk().getContent()).append("\n");
            citations.add(new KbCitation(no, h.chunk().getId(), h.kb().getId(), h.kb().getName(), source, h.score(),
                    h.matchedBy()));
        }
        String block = PromptProperties.render(promptProperties.kbContext(),
                Map.of("items", items.toString().strip()));
        if (block == null || block.isBlank()) {
            log.warn("知识库资料块模板为空（请检查 prompts.yaml 的 agent.prompt.kb-context），本轮不带资料");
            return KbContext.EMPTY;
        }
        return new KbContext("\n\n" + block.strip(), citations);
    }

    /** 目标库名拼接（debug 日志用，如「通用知识库 + 教育数据分析」）。 */
    private String kbNames(List<KnowledgeBase> targets) {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeBase kb : targets) {
            if (!sb.isEmpty()) {
                sb.append(" + ");
            }
            sb.append(kb.getName());
        }
        return sb.toString();
    }

    // ==================== 检索侧向量化 ====================

    /** Embedding 模型可用性（null = 未配置，检索降级为空）。 */
    private EmbeddingModel embedding() {
        return embeddingProvider.getIfAvailable();
    }

    /** 单文本向量化；失败返回 null（供检索侧降级，不抛错）。 */
    private float[] embedOne(String text) {
        try {
            EmbeddingModel model = embedding();
            return model == null ? null : model.embed(text);
        } catch (Exception e) {
            log.warn("问题向量化失败（本轮不做知识库检索）：{}", e.getMessage());
            return null;
        }
    }

    /** 把存库的向量 JSON 解析回 double[]；解析失败返回 null（该块跳过）。 */
    private double[] parseVector(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JSONArray arr = JSONUtil.parseArray(json);
            double[] v = new double[arr.size()];
            for (int i = 0; i < arr.size(); i++) v[i] = arr.getDouble(i);
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    /** 余弦相似度（两向量需同维度；维度不匹配按 0 处理，避免脏数据影响检索）。 */
    private static double cosine(float[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 资料组装结果：注入 system 的文本 + 一一对应的引用来源。两者必须同趟产出，否则编号与来源漂移。 */
    public record KbContext(String text, List<KbCitation> citations) {
        /** 无资料（RAG 关闭 / 无库 / 无命中 / 失败）：文本为空串、引用为空表。 */
        public static final KbContext EMPTY = new KbContext("", List.of());

        /** 是否无资料（调用方可据此跳过注入与引用回传）。 */
        public boolean isEmpty() {
            return text == null || text.isBlank();
        }
    }
}
