package org.luo.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.luo.agent.handler.PlannerRoundHandler;
import org.luo.chat.ChatComposer;
import org.luo.infrastructure.chroma.ChromaClient;
import org.luo.infrastructure.chroma.ChromaVectorStoreService;

/**
 * 知识库检索服务（RAG 检索收敛点，从 KbService 拆出）。
 * <p>
 * 只做一件事：把「会话级 RAG 开关 + 路由 agent」翻译成本轮要注入系统提示词的「[知识库资料]」文本。
 * 检索与否由会话级开关（conversation.rag_enabled）决定；开启后自动多库检索：
 * <ul>
 *   <li>「通用知识库」（全局，{@code kb.agent_id IS NULL}）——存在即查，任何对话都可命中；</li>
 *   <li>本轮路由/绑定到的智能体的专属库（agent 非空且其库存在）——查得到即查。</li>
 * </ul>
 * 目标库合并后<b>一次检索完成</b>（Chroma 单 collection 按 kb_id OR 过滤一次查完，query 只向量化一次；
 * Chroma 不可用 / 无命中整体回退 MySQL 全量余弦），命中按相似度降序收敛到 {@link #TOP_K_RAG}。
 * <p>
 * 注入点：{@link ChatComposer#buildKbContext} 透传（普通对话绑定 agent、兜底 null），
 * 规划模式每步经 ChatComposer 传当步步骤 agent。检索/向量化任何一步失败都只降级（返回空串）
 * 绝不阻断对话——RAG 是增强，不是依赖。
 * <p>
 * 对应写入/管理侧见 {@link KbService}（建库、文件上传分块、Chroma 双写与 sync），本服务只读不写。
 */
@Slf4j
@Service
public class KbSearchService {

    /** 单轮检索返回的知识块数（多库合并后总量上限）。 */
    private static final int TOP_K_RAG = 3;

    /**
     * 余弦相似度最低阈值：低于该值的检索结果视为不相关，不注入提示词。
     * 该值依赖 embedding 模型的相似度分布，可按实际效果调整（调低=更宽容，调高=更严格）。
     */
    private static final double MIN_SCORE = 0.25;

    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final ChromaVectorStoreService chromaStore;
    private final ObjectProvider<EmbeddingModel> embeddingProvider;

    public KbSearchService(KnowledgeBaseMapper kbMapper,
                           KnowledgeChunkMapper chunkMapper,
                           ChromaVectorStoreService chromaStore,
                           ObjectProvider<EmbeddingModel> embeddingProvider) {
        this.kbMapper = kbMapper;
        this.chunkMapper = chunkMapper;
        this.chromaStore = chromaStore;
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * 组装本轮请求的知识库资料文本（核心 RAG 入口，供 ChatComposer / PlannerRoundHandler 注入系统提示词）。
     * <p>
     * 检索与否由<b>会话级 RAG 开关</b>（conversation.rag_enabled）决定；开启后<b>自动多库检索</b>，
     * 无需手动选库，目标库为：
     * <ul>
     *   <li>「通用知识库」（全局，agent_id IS NULL）——存在即查，任何对话都可命中；</li>
     *   <li>本轮路由/绑定到的智能体的专属库（agent 非空且其库存在）——查得到即查。</li>
     * </ul>
     * 多库命中合并后按相似度降序、总量仍收敛在 {@code TOP_K_RAG}（3）内注入，避免资料过长稀释回答。
     * 目标库不存在 / 为空等场景一律返回空串（查不到库自动降级，不报错）。
     * 命中文本以「[知识库资料]」块返回；检索失败 / 无命中 / embedding 未配置一律返回空串，
     * 由调用方原样继续——知识库故障绝不阻断对话（RAG 是增强，不是依赖）。
     *
     * @param ragEnabled 会话级 RAG 开关（null/false = 不使用 RAG）
     * @param agent      本轮路由/绑定的智能体（null = 普通对话，只查全局库）；用于叠加检索其专属库
     * @param query      用户当前问题（检索语义由它驱动）
     */
    public String buildKbContext(Boolean ragEnabled, Agent agent, String query) {
        if (!Boolean.TRUE.equals(ragEnabled) || query == null || query.isBlank()) return "";
        try {
            List<KnowledgeBase> targets = ragTargets(agent);
            if (targets.isEmpty()) {
                log.debug("知识库检索：RAG 已开启，但无可用知识库（无全局库 / 无该智能体专属库），本轮不带资料");
                return "";
            }
            // 多库合并检索：一次请求跨全部目标库（query 只向量化一次），命中按相似度降序收敛到 TOP_K_RAG
            List<Hit> hits = searchAll(targets, query);
            if (hits.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("\n\n[知识库资料] 以下是与当前问题相关的知识库检索结果（按相关度降序）。")
                    .append("请优先参考这些资料回答问题；资料未覆盖的内容按你的知识作答，不要编造；")
                    .append("资料中的信息与实时数据 / 工具查询结果冲突时，以实时查询结果为准。资料格式：[库名|来源] 内容：\n");
            for (Hit h : hits) {
                sb.append("- [").append(h.kb().getName());
                if (h.chunk().getSource() != null && !h.chunk().getSource().isBlank()) {
                    sb.append("|").append(h.chunk().getSource());
                }
                sb.append("] ").append(h.chunk().getContent()).append("\n");
            }
            return sb.toString().strip();
        } catch (Exception e) {
            log.warn("知识库检索失败，本轮不带资料（不影响对话）：{}", e.getMessage());
            return "";
        }
    }

    /**
     * 解析 RAG 检索目标库（存在才加入，避免查空库）：全局「通用知识库」 + 当前智能体的专属库。
     *
     * @param agent 本轮路由/绑定的智能体；null = 只查全局库
     */
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

    /** 全局「通用知识库」（agent_id IS NULL，不存在返回 null）；与 KbService.getGlobal 同款查询，检索侧自含避免循环依赖。 */
    private KnowledgeBase globalKb() {
        return kbMapper.selectOne(new QueryWrapper<KnowledgeBase>()
                .isNull("agent_id").last("LIMIT 1"));
    }

    /** 检索命中的一条：知识块 + 所属库 + 相似度（供多库合并后按分统一截断）。 */
    private record Hit(KnowledgeBase kb, KnowledgeChunk chunk, double score) {}

    /**
     * 多库统一检索：一次请求跨<b>全部目标库</b>。Chroma 优先（where kb_id 过滤全部目标库 + 余弦 TopK），
     * 整体失败 / 无命中回退 MySQL 全量余弦（留存向量兜底，检索不依赖 Chroma 可用性）。
     * <p>
     * 相比逐库遍历：query 只向量化一次（embedding 为远程 API，省去 N-1 次重复调用），Chroma 只往返一次；
     * 且回退不再逐库独立判定，命中分数天然同源可比（避免 Chroma 分与 MySQL 余弦分混排的尺度偏差）。
     * 候选量按 库数×topK 放大请求，命中合并后统一按相似度降序、截断到 {@code TOP_K_RAG} 返回。
     *
     * @param targets 目标库（≥1，来自 {@link #ragTargets(Agent)}）
     * @param query   检索文本
     */
    private List<Hit> searchAll(List<KnowledgeBase> targets, String query) {
        Map<Long, KnowledgeBase> byId = new LinkedHashMap<>();
        for (KnowledgeBase kb : targets) {
            byId.put(kb.getId(), kb);
        }
        List<Long> kbIds = new ArrayList<>(byId.keySet());

        // —— Chroma 优先：跨库一次查，候选放大到 库数×topK，Java 侧统一排序截断 ——
        List<ChromaHit> chromaHits =
                chromaStore.search(kbIds, query, kbIds.size() * TOP_K_RAG, MIN_SCORE);
        if (!chromaHits.isEmpty()) {
            List<Hit> hits = new ArrayList<>(chromaHits.size());
            for (ChromaHit h : chromaHits) {
                KnowledgeBase kb = byId.get(h.kbId());
                if (kb == null) {
                    continue;   // 归属库不在本次目标内（理论上不可能），防御跳过
                }
                KnowledgeChunk c = new KnowledgeChunk();
                c.setId(h.chunkId());
                c.setKbId(kb.getId());
                c.setContent(h.content());
                c.setSource(h.source());
                hits.add(new Hit(kb, c, h.score()));
            }
            List<Hit> trimmed = trimHits(hits);
            if (log.isDebugEnabled()) {
                log.debug("知识库检索：Chroma 跨 {} 库命中 {} 块，收敛 {} 块（{}）",
                        kbIds.size(), chromaHits.size(), trimmed.size(), kbNames(targets));
            }
            return trimmed;
        }

        // —— 回退 MySQL：一次 IN 查询全部目标库块，query 只向量化一次 ——
        List<KnowledgeChunk> all = chunkMapper.selectList(new QueryWrapper<KnowledgeChunk>()
                .in("kb_id", kbIds));
        if (all.isEmpty()) return List.of();
        float[] queryVec = embedOne(query);
        if (queryVec == null) return List.of();
        List<double[]> scores = new ArrayList<>(all.size());
        for (KnowledgeChunk c : all) {
            double[] vec = parseVector(c.getEmbedding());
            if (vec != null) {
                scores.add(new double[]{cosine(queryVec, vec), scores.size()});
            }
        }
        scores.sort((a, b) -> Double.compare(b[0], a[0]));   // 按相似度降序
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < scores.size() && hits.size() < TOP_K_RAG; i++) {
            double s = scores.get(i)[0];
            if (s < MIN_SCORE) break;                          // 已降序，首个不达标即全不达标
            KnowledgeChunk c = all.get((int) scores.get(i)[1]);
            KnowledgeBase kb = byId.get(c.getKbId());
            if (kb == null) {
                continue;
            }
            hits.add(new Hit(kb, c, s));
        }
        if (log.isDebugEnabled()) {
            log.debug("知识库检索：回退 MySQL（{} 库共 {} 块）命中 {} 块，最高分={}",
                    kbIds.size(), all.size(), hits.size(),
                    hits.isEmpty() ? "-" : String.format("%.3f", scores.get(0)[0]));
        }
        return hits;
    }

    /** 命中列表按相似度降序，超出 {@code TOP_K_RAG} 截断。 */
    private List<Hit> trimHits(List<Hit> hits) {
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (hits.size() > TOP_K_RAG) {
            return new ArrayList<>(hits.subList(0, TOP_K_RAG));
        }
        return hits;
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
}
