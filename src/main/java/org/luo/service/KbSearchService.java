package org.luo.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.properties.PromptProperties;
import org.luo.properties.RagProperties;
import org.luo.dto.KbCitation;
import org.luo.entity.Agent;
import org.luo.entity.KnowledgeBase;
import org.luo.entity.KnowledgeChunk;
import org.luo.mapper.KnowledgeBaseMapper;
import org.luo.mapper.KnowledgeChunkMapper;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.luo.infrastructure.chroma.ChromaClient.ChromaHit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import org.luo.chat.ChatComposer;
import org.luo.infrastructure.chroma.ChromaVectorStoreService;
import org.luo.infrastructure.rerank.RerankService;

/**
 * 知识库检索服务（RAG 检索收敛点，只读不写；写入/管理侧见 {@link KbService}）。
 * <p>
 * 把「会话级 RAG 开关 + 路由 agent」翻译成注入系统提示词的「[知识库资料]」文本 + 引用来源
 * （{@link KbCitation}）。开启后自动多库检索：全局「通用知识库」（{@code kb.agent_id IS NULL}）
 * + 本轮路由/绑定智能体的专属库。
 * <p>
 * <b>三段式检索</b>：① 粗排召回（多库合并一次检索，宽松下限）→ ② 精排（{@link RerankService}，
 * 不可用则降级为向量分 + 严格 {@code min-score} 截断）→ ③ 编号注入（套 {@code agent.prompt.kb-context}
 * 模板，同趟产出与编号一一对应的引用）。注入点：{@link ChatComposer#buildKbContext} 透传。
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
            List<Hit> recalled = searchAll(targets, query);   // ① 粗排召回（recallK 条，宽松下限）
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

    /** 检索命中的一条：知识块 + 所属库 + 分数（精排前为真实余弦，精排后被替换为 relevance_score）。 */
    private record Hit(KnowledgeBase kb, KnowledgeChunk chunk, double score) {
        /** 用新分数换出一个同源命中（精排分覆盖向量分，元数据保持不变）。 */
        Hit withScore(double newScore) {
            return new Hit(kb, chunk, newScore);
        }
    }

    /**
     * 多库统一粗排召回：一次跨全部目标库，召回 {@code recall-k} 条候选。
     * Chroma 优先（kb_id 过滤 + 余弦 TopK），失败/无命中回退 MySQL 余弦（<b>有界扫描</b>，
     * 上限 {@code agent.rag.fallback-max-chunks}，避免把整库向量文本拉进堆）。
     * 下限用宽松的 {@code recall-min-score}：这一阶段目标是「别漏」，判相关性交给精排。
     */
    private List<Hit> searchAll(List<KnowledgeBase> targets, String query) {
        Map<Long, KnowledgeBase> byId = new LinkedHashMap<>();
        for (KnowledgeBase kb : targets) {
            byId.put(kb.getId(), kb);
        }
        List<Long> kbIds = new ArrayList<>(byId.keySet());
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
                hits.add(new Hit(kb, c, h.score()));
            }
            if (log.isDebugEnabled()) {
                log.debug("RAG 粗排：Chroma 跨 {} 库召回 {} 块（下限 {}，目标 {}），库={}",
                        kbIds.size(), hits.size(), floor, recall, kbNames(targets));
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
            heap.offer(new Hit(kb, c, s));
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
        sorted.sort((a, b) -> Double.compare(b.score(), a.score()));   // 兜底顺序 = 向量分降序
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

        // 兜底：向量分降序 + 严格阈值，取 topK（精排上线前的原始行为）
        List<Hit> picked = new ArrayList<>(topK);
        for (Hit h : sorted) {
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
            citations.add(new KbCitation(no, h.chunk().getId(), h.kb().getId(), h.kb().getName(), source, h.score()));
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
