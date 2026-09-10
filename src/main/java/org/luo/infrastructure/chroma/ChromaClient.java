package org.luo.infrastructure.chroma;

import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.luo.entity.KnowledgeChunk;

/**
 * Chroma 原生操作客户端（从门面抽出的「操作适配器」）。
 * <p>
 * 封装 Chroma 全部增删查：{@link #add}、{@link #delete}、{@link #search}、{@link #count}。
 * 把 Chroma 0.5 的嵌套响应拆包、余弦距离还原、多库 {@code $or} 合并、分批写入都藏在这一层，
 * 调用方看到的就是和 LangChain {@code similarity_search} 等价的一行。
 * <p>
 * 无连接/降级逻辑（由 {@code ChromaConnection} 负责），任何异常向上抛、由门面统一降级。
 * <p>
 * <b>相似度语义（重点）</b>：Chroma 0.5 cosine 空间 {@code distance = 2·(1 − cos)}，
 * 故其 score {@code = 2·cos − 1}，并非真实余弦；须用 {@link #toRealCosine} 还原
 * {@code realCos = (score+1)/2} 才能与 MySQL 余弦阈值（MIN_SCORE）同口径，否则相关结果被系统性滤掉。
 */
public class ChromaClient {

    private final ChromaApi api;
    private final EmbeddingModel embedding;
    private final ChromaVectorStore store;
    private final String collectionId;
    private final String tenant;
    private final String database;
    private final int upsertBatchSize;

    /** 单批 delete 上限（删除不涉及向量化，可较大）。 */
    private static final int DELETE_BATCH = 500;

    public ChromaClient(ChromaApi api, EmbeddingModel embedding, ChromaVectorStore store,
                        String collectionId, String tenant, String database, int upsertBatchSize) {
        this.api = api;
        this.embedding = embedding;
        this.store = store;
        this.collectionId = collectionId;
        this.tenant = tenant;
        this.database = database;
        // 受 Embedding 模型单次批量上限约束：DashScope text-embedding-v3 ≤ 20 条，留余量默认 10
        this.upsertBatchSize = Math.max(1, upsertBatchSize);
    }

    /**
     * upsert 知识块（按文档 id 幂等覆盖）。按 {@code upsertBatchSize} 分批，
     * 因 {@code store.add()} 内部整批 embed，超模型单次上限会被 400 拒绝（见 ChromaConnection 的批大小配置说明）。
     * 失败向上抛，由门面降级。
     */
    public void add(List<ChromaDoc> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        List<Document> documents = new ArrayList<>(docs.size());
        for (ChromaDoc d : docs) {
            if (d.id() == null || d.text() == null) {
                continue;   // 未落库 / 空内容块跳过
            }
            Map<String, Object> meta = new HashMap<>();
            if (d.kbId() != null) {
                meta.put("kb_id", d.kbId().intValue());
            }
            if (d.source() != null && !d.source().isBlank()) {
                meta.put("source", d.source());
            }
            documents.add(new Document(d.id(), d.text(), meta));
        }
        if (documents.isEmpty()) {
            return;
        }
        for (int i = 0; i < documents.size(); i += upsertBatchSize) {
            store.add(documents.subList(i, Math.min(documents.size(), i + upsertBatchSize)));
        }
    }

    /** 按文档 id 删除向量（幂等）；失败向上抛。 */
    public void delete(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<String> list = new ArrayList<>(ids);
        for (int i = 0; i < list.size(); i += DELETE_BATCH) {
            store.delete(list.subList(i, Math.min(list.size(), i + DELETE_BATCH)));
        }
    }

    /** 集合内向量条数（失败抛，由门面 catch 降级）。 */
    public Long count() {
        return api.countEmbeddings(tenant, database, collectionId);
    }

    /**
     * 多库合并检索：query 只向量化一次，按 kb_id 一次过滤（单库等值 / 多库 {@code $or}），命中按真实余弦过滤。
     *
     * @param kbIds    目标知识库 ID（≥1 个）
     * @param query    检索文本
     * @param topK     返回候选数上限
     * @param minScore 余弦相似度最低阈值（与 MySQL 余弦检索同口径）
     */
    public List<ChromaHit> search(Collection<Long> kbIds, String query, int topK, double minScore) {
        if (kbIds == null || kbIds.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        float[] q = embedding.embed(query);
        if (q == null) {
            return List.of();
        }
        ChromaApi.QueryRequest req = new ChromaApi.QueryRequest(q, topK, buildWhere(kbIds));
        ChromaApi.QueryResponse resp = api.queryCollection(tenant, database, collectionId, req);
        return parseHits(resp, minScore);
    }

    /**
     * 构造 Chroma 的 where 过滤（按 kb_id 一次过滤所有目标库）：单库退化为单等值，多库用 {@code $or} 拼接。
     */
    private Map<String, Object> buildWhere(Collection<Long> kbIds) {
        List<Map<String, Object>> ors = new ArrayList<>(kbIds.size());
        for (Long kbId : kbIds) {
            ors.add(Map.of("kb_id", kbId.intValue()));
        }
        if (ors.size() == 1) {
            return ors.get(0);
        }
        return Map.of("$or", ors);
    }

    /**
     * 解析 Chroma 原生 query 响应：拆双层列表 → 按 {@link #toRealCosine} 还原真实余弦 → 低于 minScore 丢弃。
     */
    private List<ChromaHit> parseHits(ChromaApi.QueryResponse resp, double minScore) {
        List<ChromaHit> hits = new ArrayList<>();
        if (resp == null || resp.ids() == null || resp.ids().isEmpty()
                || resp.distances() == null || resp.distances().get(0) == null
                || resp.documents() == null || resp.documents().get(0) == null
                || resp.ids().get(0).size() != resp.distances().get(0).size()) {
            return hits;
        }
        List<String> ids = resp.ids().get(0);
        List<Double> dists = resp.distances().get(0);
        List<String> docs = resp.documents().get(0);
        List<Map<String, Object>> metas = (resp.metadata() == null || resp.metadata().isEmpty())
                ? null : resp.metadata().get(0);
        for (int i = 0; i < ids.size(); i++) {
            Long chunkId = parseId(ids.get(i));
            if (chunkId == null) {
                continue;
            }
            String content = docs.get(i);
            if (content == null) {
                continue;
            }
            double dist = dists.get(i) == null ? 2.0 : dists.get(i);
            // raw score = 1 − distance；再还原成真实余弦
            double realCos = toRealCosine(1.0 - dist);
            if (realCos < minScore) {
                continue;   // 真实余弦低于阈值，不注入（与 MySQL 侧 MIN_SCORE 口径一致）
            }
            Map<String, Object> meta = (metas != null && i < metas.size()) ? metas.get(i) : null;
            if (meta == null) {
                meta = Map.of();
            }
            Object src = meta.get("source");
            Object kbId = meta.get("kb_id");
            hits.add(new ChromaHit(chunkId,
                    kbId instanceof Number n ? n.longValue() : null,
                    content,
                    src == null ? null : String.valueOf(src),
                    realCos));
        }
        return hits;
    }

    /**
     * Chroma score → 真实余弦：{@code (score+1)/2}。
     * 背景见类注释（Chroma 0.5 cosine 空间 distance=2(1−cos)，score=2cos−1）。
     */
    private static double toRealCosine(double chromaScore) {
        return (chromaScore + 1) / 2.0;
    }

    /** Chroma 文档 id（= chunk id 字符串）→ Long；非法返回 null。 */
    private static Long parseId(String id) {
        if (id == null) return null;
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 通用 Chroma 文档载体（与 KnowledgeChunk 解耦）：id=chunkId 字符串、text=内容、kbId/source 写 metadata。 */
    public record ChromaDoc(String id, String text, Long kbId, String source) {
    }

    /** Chroma 检索命中：chunkId / 归属 kbId / 内容 / 来源 / 真实余弦 score。 */
    public record ChromaHit(Long chunkId, Long kbId, String content, String source, double score) {
    }
}
