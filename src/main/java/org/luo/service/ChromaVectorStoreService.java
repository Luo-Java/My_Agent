package org.luo.service;

import lombok.extern.slf4j.Slf4j;
import org.luo.entity.KnowledgeChunk;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chroma 向量库客户端封装（Spring AI 2.0 官方 chroma-store 模块，非 starter）。
 * <p>
 * 与「MySQL 双写留存」方案配套：MySQL（kb_chunk.embedding）是留档源，Chroma 是加速检索的副本，
 * 本服务是<b>可选依赖</b>——任何失败只 warn 降级，绝不抛错影响写入/检索主流程（KbService 会
 * 在 Chroma 不可用时自动回退 MySQL 余弦检索）。
 * <p>
 * 关键约定：
 * <ul>
 *   <li><b>lazy 初始化</b>：首次使用时才连接服务并建 collection（initializeSchema +
 *       initializeImmediately），应用启动不依赖 Chroma；初始化失败后本会话内标记不可用直接跳过
 *       （重启应用即重试）。</li>
 *   <li><b>chunk id 即 Chroma 文档 id</b>（String.valueOf），删除按 id 精确命中，幂等。</li>
 *   <li><b>metadata</b>：每文档带 kb_id（数值）与 source；检索按 kb_id 过滤——单库精确等值，
 *       多库合并检索时（RAG 开启命中全局库 + 智能体专属库）由调用方传 Collection&lt;Long&gt;，where 子句
 *       OR 拼接一次查完（query 只向量化一次，跨库共享）。</li>
 *   <li><b>双算说明</b>：写入时 ChromaVectorStore 内部会用同一 Embedding 模型对文本再次向量化
 *       （确定性模型输出与 MySQL 侧一致），成本为每次入库多一次 embedding 调用，换来官方链路与
 *       向量自洽，可接受；也可后续换 ChromaApi 原生 upsert 传向量消除。</li>
 *   <li><b>相似度语义</b>：collection 建在 cosine 空间（hnsw:space=cosine），Chroma 返回 distance，
 *       相似度 = 1 - distance，与 KbService 现有 MySQL 余弦阈值（MIN_SCORE）口径一致。</li>
 * </ul>
 */
@Slf4j
@Service
public class ChromaVectorStoreService {

    /** Chroma 服务地址。 */
    @Value("${chroma.base-url:http://127.0.0.1:8000}")
    private String baseUrl;

    /** Chroma collection 名（全局单 collection，kb_id 用 metadata 区分）。 */
    @Value("${chroma.collection-name:kb_chunks}")
    private String collectionName;

    /** Chroma 单批 upsert / delete 上限（避免一次请求过大）。 */
    private static final int BATCH = 500;

    private final ObjectProvider<EmbeddingModel> embeddingProvider;

    /** 懒初始化的 store；null 且 initFailed=false 表示尚未尝试连接。 */
    private volatile ChromaVectorStore store;

    /** 初始化失败标记：true 后本会话不再重试（重启应用即恢复）。 */
    private volatile boolean initFailed;

    public ChromaVectorStoreService(ObjectProvider<EmbeddingModel> embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * Chroma 检索命中的一条（由 {@link KbService} 转成注入文本）。
     *
     * @param chunkId 命中的知识块 id
     * @param kbId    命中所属知识库 id（upsert 时写入 metadata，供跨库查询回溯归属库拼来源）
     * @param content 知识块内容
     * @param source  来源文件名（可能为 null）
     * @param score   余弦相似度
     */
    public record ChromaHit(Long chunkId, Long kbId, String content, String source, double score) {
    }

    /**
     * 把已入库（含自增 id）的知识块 upsert 到 Chroma（按 chunk id 幂等覆盖）。
     * 任何失败只 warn 降级（MySQL 已留档），返回 false 供调用方统计。
     *
     * @param kbId   所属知识库 ID（写入文档 metadata，检索过滤用）
     * @param chunks 已落库的知识块（id 必须非空）
     */
    public boolean upsertChunks(Long kbId, List<KnowledgeChunk> chunks) {
        ChromaVectorStore s = store();
        if (s == null || kbId == null || chunks == null || chunks.isEmpty()) {
            return false;
        }
        try {
            List<Document> docs = new ArrayList<>(chunks.size());
            for (KnowledgeChunk c : chunks) {
                if (c.getId() == null || c.getContent() == null) {
                    continue;   // 未落库/空内容块跳过
                }
                Map<String, Object> meta = new HashMap<>();
                meta.put("kb_id", kbId.intValue());
                if (c.getSource() != null && !c.getSource().isBlank()) {
                    meta.put("source", c.getSource());
                }
                docs.add(new Document(String.valueOf(c.getId()), c.getContent(), meta));
            }
            if (docs.isEmpty()) {
                return false;
            }
            for (int i = 0; i < docs.size(); i += BATCH) {
                s.add(docs.subList(i, Math.min(docs.size(), i + BATCH)));
            }
            return true;
        } catch (Exception e) {
            log.warn("Chroma upsert 失败（MySQL 已留档，忽略）：kbId={}，块数={}，原因={}",
                    kbId, chunks.size(), e.getMessage());
            return false;
        }
    }

    /** 按 chunk id 从 Chroma 删除向量（幂等；配合 MySQL 侧删除调用，保持双写一致）。 */
    public void deleteByChunkIds(Collection<Long> chunkIds) {
        ChromaVectorStore s = store();
        if (s == null || chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        try {
            List<String> ids = chunkIds.stream().map(String::valueOf).toList();
            for (int i = 0; i < ids.size(); i += BATCH) {
                s.delete(ids.subList(i, Math.min(ids.size(), i + BATCH)));
            }
        } catch (Exception e) {
            log.warn("Chroma 删除失败（MySQL 已删，忽略残留）：块数={}，原因={}", chunkIds.size(), e.getMessage());
        }
    }

    /**
     * Chroma <b>多库合并检索</b>：where 按 kb_id 一次过滤全部目标库（filter 用 OR 等值拼接，
     * 与既有单库等值过滤同构，避免数字数组 IN 字面量的转换兼容盲区；单库时退化为单等值）。
     * 合并查询使 query 文本只被向量化一次（模块内部对 query 做 embedding），跨库共享，
     * 不再每库各调一次 embedding API。
     * 服务不可用 / 检索失败返回空列表（调用方回退 MySQL 余弦检索），绝不抛错。
     *
     * @param kbIds    目标知识库 ID（≥1 个）
     * @param query    检索文本
     * @param topK     返回候选数上限（多库建议传 库数×单库上限，由调用方统一排序截断）
     * @param minScore 余弦相似度最低阈值
     */
    public List<ChromaHit> search(Collection<Long> kbIds, String query, int topK, double minScore) {
        ChromaVectorStore s = store();
        if (s == null || kbIds == null || kbIds.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            StringBuilder filter = new StringBuilder();
            for (Long kbId : kbIds) {
                if (filter.length() > 0) {
                    filter.append(" OR ");
                }
                filter.append("kb_id == ").append(kbId.intValue());
            }
            List<Document> docs = s.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(minScore)
                    .filterExpression(filter.toString())
                    .build());
            List<ChromaHit> hits = new ArrayList<>(docs.size());
            for (Document d : docs) {
                Long chunkId = parseId(d.getId());
                if (chunkId == null || d.getText() == null) {
                    continue;
                }
                Map<String, Object> meta = d.getMetadata() == null ? Map.of() : d.getMetadata();
                Object src = meta.get("source");
                Object kbId = meta.get("kb_id");
                double score = d.getScore() == null ? 0 : d.getScore();
                hits.add(new ChromaHit(chunkId,
                        kbId instanceof Number n ? n.longValue() : null,
                        d.getText(),
                        src == null ? null : String.valueOf(src),
                        score));
            }
            return hits;
        } catch (Exception e) {
            log.warn("Chroma 检索失败（回退 MySQL 余弦）：kbIds={}，原因={}", kbIds, e.getMessage());
            return List.of();
        }
    }

    /** 惰性获取 store：首次调用时连接并建 collection，失败后本会话降级。 */
    private ChromaVectorStore store() {
        ChromaVectorStore cur = store;
        if (cur != null || initFailed) {
            return cur;
        }
        synchronized (this) {
            if (store != null || initFailed) {
                return store;
            }
            try {
                EmbeddingModel model = embeddingProvider.getIfAvailable();
                if (model == null) {
                    initFailed = true;
                    log.warn("Chroma 未启用：未配置 Embedding 模型（spring.ai.openai.embedding），后续检索走 MySQL");
                    return null;
                }
                ChromaApi api = ChromaApi.builder().baseUrl(baseUrl).build();
                store = ChromaVectorStore.builder(api, model)
                        .collectionName(collectionName)
                        .initializeSchema(true)          // collection 不存在则自动创建（cosine 空间）
                        .initializeImmediately(true)     // build 即建库，失败立即抛错走降级分支
                        .build();
                log.info("Chroma 已连接：baseUrl={}，collection={}", baseUrl, collectionName);
                return store;
            } catch (Exception e) {
                initFailed = true;
                log.warn("Chroma 连接失败，本次运行降级为 MySQL 余弦检索：{}（如服务后启动可重启应用重试）",
                        e.getMessage());
                return null;
            }
        }
    }

    /** Chroma 文档 id 解析回 chunk id（非法返回 null）。 */
    private static Long parseId(String id) {
        if (id == null) return null;
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
