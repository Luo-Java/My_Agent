package org.luo.infrastructure.chroma;

import lombok.extern.slf4j.Slf4j;
import org.luo.entity.KnowledgeChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.luo.infrastructure.chroma.ChromaClient.ChromaDoc;
import org.luo.infrastructure.chroma.ChromaClient.ChromaHit;

/**
 * Chroma 向量库<b>业务门面</b>（与「MySQL 双写留存」配套）。连接生命周期下沉到 {@link ChromaConnection}，
 * 具体操作下沉到 {@link ChromaClient}；本类只做三件事：<b>实体转换</b>（KnowledgeChunk → ChromaDoc，
 * chunk id 即文档 id、kb_id/source 写 metadata）、<b>编排与降级</b>（经 connection.connected(op) 取客户端，
 * 未连接/失败即降级，MySQL 已留档）、<b>对外契约</b>（稳定的 upsert/delete/search/status，屏蔽 Chroma 版本细节）。
 */
@Slf4j
@Service
public class ChromaVectorStoreService {

    private final ChromaConnection connection;

    public ChromaVectorStoreService(ChromaConnection connection) {
        this.connection = connection;
    }

    /**
     * 把已落库知识块 upsert 到 Chroma（按 chunk id 幂等覆盖）。失败只 warn 降级（MySQL 已留档），返回 false 供调用方统计。
     *
     * @param kbId 所属知识库 ID（写入文档 metadata，检索过滤用）
     */
    public boolean upsertChunks(Long kbId, List<KnowledgeChunk> chunks) {
        if (kbId == null || chunks == null || chunks.isEmpty()) {
            return false;
        }
        ChromaClient client = connection.connected("写入");
        if (client == null) {
            return false;
        }
        try {
            List<ChromaDoc> docs = new ArrayList<>(chunks.size());
            for (KnowledgeChunk c : chunks) {
                if (c.getId() == null || c.getContent() == null) {
                    continue;   // 跳过未落库或空内容块
                }
                docs.add(new ChromaDoc(String.valueOf(c.getId()), c.getContent(), kbId, c.getSource()));
            }
            client.add(docs);
            log.debug("Chroma 副本写入：kbId={}，块数={}", kbId, chunks.size());
            return true;
        } catch (Exception e) {
            log.warn("Chroma upsert 失败（MySQL 已留档，可用 POST /api/kb/chroma/sync 回填）：kbId={}，块数={}，原因={}",
                    kbId, chunks.size(), e.getMessage());
            return false;
        }
    }

    /** 按 chunk id 从 Chroma 删除向量（幂等）；失败忽略残留（MySQL 已删）。 */
    public void deleteByChunkIds(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        ChromaClient client = connection.connected("删除");
        if (client == null) {
            return;
        }
        try {
            List<String> ids = chunkIds.stream().map(String::valueOf).toList();
            client.delete(ids);
        } catch (Exception e) {
            log.warn("Chroma 删除失败（MySQL 已删，忽略残留）：块数={}，原因={}", chunkIds.size(), e.getMessage());
        }
    }

    /**
     * 多库合并检索：委托 ChromaClient 完成 query + 余弦还原 + kb_id 过滤，本方法只管连接就绪与降级。
     * 服务不可用/失败返回空列表（调用方回退 MySQL 余弦），绝不抛错。
     *
     * @param topK 返回候选数上限（多库建议传 库数×单库上限，由调用方统一排序截断）
     */
    public List<ChromaHit> search(Collection<Long> kbIds, String query, int topK, double minScore) {
        if (kbIds == null || kbIds.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        ChromaClient client = connection.connected("检索");
        if (client == null) {
            return List.of();
        }
        try {
            List<ChromaHit> hits = client.search(kbIds, query, topK, minScore);
            if (log.isDebugEnabled()) {
                log.debug("Chroma 检索：kbIds={}，query 前 20 字={}，候选={}",
                        kbIds, query.length() > 20 ? query.substring(0, 20) : query, hits.size());
            }
            return hits;
        } catch (Exception e) {
            log.warn("Chroma 检索失败（回退 MySQL 余弦）：kbIds={}，原因={}", kbIds, e.getMessage());
            return List.of();
        }
    }

    /** 运行状态自检（GET /api/kb/chroma/status），委托 ChromaConnection。 */
    public Map<String, Object> status() {
        return connection.status();
    }
}
