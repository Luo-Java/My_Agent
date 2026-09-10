package org.luo.infrastructure.chroma;

import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chroma <b>连接生命周期</b>管理：只负责把 Chroma 连上并提供一个随时可用的 {@link ChromaClient}，以及状态自检；
 * 不含实体转换与检索编排（在 {@link ChromaVectorStoreService}）。要点：
 * <ul>
 *   <li><b>lazy + 冷却重试</b>：首次使用才连；失败进 {@code chroma.retry-interval-seconds}（默认 60s）冷却，
 *       冷却期内 {@link #connected} 返回 null（调用方降级），冷却后自动重试——「先起应用后起 Chroma」无需重启。</li>
 *   <li><b>命名空间 + cosine 空间校正</b>：显式 default_tenant/default_database，建库前用原生 HTTP 幂等预建
 *       cosine 集合（见 {@link #ensureCollection}）。否则 Spring AI 2.0 默认命名空间 + 不认 400 InvalidCollection
 *       会导致初始化必失败；且 Chroma 0.5 不从 metadata 读 space，必须用原生 HTTP 建 cosine。</li>
 * </ul>
 */
@Slf4j
@Service
public class ChromaConnection {

    /** Chroma 服务地址。 */
    @Value("${chroma.base-url:http://127.0.0.1:8000}")
    private String baseUrl;

    /** Chroma collection 名（全局单 collection，kb_id 用 metadata 区分）。 */
    @Value("${chroma.collection-name:kb_chunks}")
    private String collectionName;

    /**
     * Chroma 租户名。必须显式指定（不要用 Spring AI 默认 SpringAiTenant）：
     * 本地 Chroma 0.5.x 只有 default_tenant/default_database，且 getCollection 对不存在的 collection 返回
     * 400 InvalidCollection（非 404），Spring AI 只认 404 → 初始化必失败。配合 {@link #ensureCollection} 原生预建解决。
     */
    @Value("${chroma.tenant:default_tenant}")
    private String tenant;

    /** Chroma 数据库名，理由同 {@link #tenant}。 */
    @Value("${chroma.database:default_database}")
    private String database;

    /** 连接失败后的重试冷却秒数：冷却期内 {@link #connected} 不再触发连接（避免每轮都连一次拖慢响应），到期自愈、无需重启。 */
    @Value("${chroma.retry-interval-seconds:60}")
    private long retryIntervalSeconds;

    /**
     * 单批 upsert 文档数（受 Embedding 模型单次上限约束，非 Chroma 体积限制）。
     * {@code store.add()} 整批 embed，超上限被 400：DashScope text-embedding-v3 单次 ≤ 20 条。默认 10 留余量，换模型后调 {@code chroma.upsert-batch-size}。
     */
    @Value("${chroma.upsert-batch-size:10}")
    private int upsertBatchSize;

    /** 「副本未同步」提示的限频间隔（毫秒）：避免上传/检索高频调用刷屏，同时保证异常可见。 */
    private static final long SKIP_LOG_INTERVAL = 5 * 60 * 1000L;

    private final ObjectProvider<EmbeddingModel> embeddingProvider;

    /** 已连接后构造的原生操作客户端（持有 api / 向量化模型 / store / 集合 id，封装全部 Chroma 增删查）。 */
    private volatile ChromaClient chromaClient;

    /** 最近一次初始化失败的时间戳（配合 {@link #retryIntervalSeconds} 冷却重试）。 */
    private volatile long nextRetryAt;

    /** 最近一次连接失败的原因（供 {@link #status()} 与降级日志展示，不抛错）。 */
    private volatile String lastError;

    /** 集合实际空间（cosine/l2），初始化时探测；现已统一建成 cosine，检索阈值与 MySQL 同口径。 */
    private volatile String space = "cosine";

    /** 跳过提示的限频时间戳。 */
    private volatile long lastSkipLogAt;

    public ChromaConnection(ObjectProvider<EmbeddingModel> embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * 取已连接的客户端；未连接（冷却/失败）返回 null 并限频打印跳过提示。内部先 {@link #ensureConnected()}。
     * 各公开方法只需一行 {@code ChromaClient c = connection.connected("写入"); if (c == null) return ...;}。
     *
     * @param op 操作名（写入/删除/检索），仅用于降级日志
     */
    public ChromaClient connected(String op) {
        ensureConnected();
        if (chromaClient == null) {
            logSkipped(op);
            return null;
        }
        return chromaClient;
    }

    /**
     * 惰性建连并构造 {@link ChromaClient}。失败进 {@link #retryIntervalSeconds} 秒冷却，冷却内返回、到期重试——
     * 「先起应用后起 Chroma」无需重启自愈。DCL 防止并发重复建连。
     */
    private void ensureConnected() {
        if (chromaClient != null) {
            return;
        }
        if (System.currentTimeMillis() < nextRetryAt) {
            return;   // 冷却中：快速返回，交给调用方降级
        }
        synchronized (this) {
            if (chromaClient != null) {
                return;
            }
            if (System.currentTimeMillis() < nextRetryAt) {
                return;
            }
            try {
                EmbeddingModel model = embeddingProvider.getIfAvailable();
                if (model == null) {
                    // 未配置 embedding 属配置问题、不会自行恢复：同样进冷却，避免每次都取一遍 bean
                    markFailed("未配置 Embedding 模型（spring.ai.openai.embedding），后续检索走 MySQL");
                    return;
                }
                ChromaApi api = ChromaApi.builder().baseUrl(baseUrl).build();
                // 预建 tenant/database/collection，否则 Spring AI 初始化必抛错（见 ensureCollection）
                ensureCollection(api);
                ChromaApi.Collection c = api.getCollection(tenant, database, collectionName);
                if (c == null || c.id() == null) {
                    markFailed("Chroma 集合 " + collectionName + " 不存在，建库未生效");
                    return;
                }
                ChromaVectorStore store = ChromaVectorStore.builder(api, model)
                        .tenantName(tenant)
                        .databaseName(database)
                        .collectionName(collectionName)
                        .initializeSchema(true)          // collection 不存在则自动创建（cosine 空间）
                        .initializeImmediately(true)     // build 即建库，失败立即抛错走降级分支
                        .build();
                this.chromaClient = new ChromaClient(api, model, store, c.id(), tenant, database, upsertBatchSize);
                lastError = null;
                nextRetryAt = 0;
                log.info("Chroma 已连接：baseUrl={}，tenant={}，database={}，collection={}",
                        baseUrl, tenant, database, collectionName);
            } catch (Exception e) {
                markFailed(ExceptionUtil.getRootCauseMessage(e));
                log.warn("Chroma 连接失败，本次运行降级为 MySQL 余弦检索：{} | 根因：{}（{} 秒后自动重试，无需重启）",
                        e.getMessage(), ExceptionUtil.getRootCauseMessage(e), retryIntervalSeconds);
            }
        }
    }

    /** 标记一次连接失败：进入冷却并记录原因。 */
    private void markFailed(String reason) {
        lastError = reason;
        nextRetryAt = System.currentTimeMillis() + Math.max(1, retryIntervalSeconds) * 1000L;
    }

    /**
     * 副本不可用时的限频提示：chromaClient 为 null 时调用，保证「文件入 MySQL 但向量副本没写」可见，又不刷屏。
     */
    private void logSkipped(String op) {
        long now = System.currentTimeMillis();
        if (now - lastSkipLogAt < SKIP_LOG_INTERVAL) {
            return;
        }
        lastSkipLogAt = now;
        log.warn("Chroma 副本{}跳过：服务不可用（{}），MySQL 已留档；恢复后可调用 POST /api/kb/chroma/sync 回填",
                op, lastError == null ? "未初始化" : lastError);
    }

    /**
     * 运行状态自检（GET /api/kb/chroma/status）：baseUrl / tenant / database / collection / connected / documentCount(-1=未知) / lastError。
     */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baseUrl", baseUrl);
        m.put("tenant", tenant);
        m.put("database", database);
        m.put("collection", collectionName);
        ensureConnected();
        m.put("connected", chromaClient != null);
        m.put("lastError", lastError == null ? "" : lastError);
        m.put("space", space);
        long count = -1;
        if (chromaClient != null) {
            try {
                Long n = chromaClient.count();
                if (n != null) count = n;
            } catch (Exception e) {
                log.debug("Chroma 文档数查询失败：{}", e.getMessage());
            }
        }
        m.put("documentCount", count);
        return m;
    }

    /**
     * 幂等预建 tenant → database → collection（已存在 Chroma 返回 409，忽略）。
     * <p>
     * <b>为什么 build 前必须做</b>：Spring AI 2.0 的 afterPropertiesSet 先 getCollection，而 Chroma 0.5.x 对不存在的
     * collection 返回 400 InvalidCollection（非 404），Spring AI 只认 404 → 抛 "Collection does not exist."，
     * initializeSchema(true) 永远没机会建库。这里预先创建拉回正轨，新装 Chroma 也能自愈。
     * <p>
     * <b>为什么原生 HTTP 建库</b>：Chroma 0.5.x 不再从 metadata 读 hnsw:space（会建成 l2），必须走
     * configuration.hnsw.space 才能建成 cosine。原生建库优先、Spring AI 建库兜底。
     * <p>
     * 存量集合：l2 且<b>为空</b>则删后按 cosine 重建（零损失）；非空保留并换算阈值。任一步失败不抛错，交给 build 统一降级。
     */
    private void ensureCollection(ChromaApi api) {
        try {
            api.createTenant(tenant);
        } catch (Exception e) {
            log.debug("Chroma tenant 创建跳过（多半已存在）：{}，原因={}", tenant, chop(e.getMessage()));
        }
        try {
            api.createDatabase(tenant, database);
        } catch (Exception e) {
            log.debug("Chroma database 创建跳过（多半已存在）：{}，原因={}", database, chop(e.getMessage()));
        }
        createCollectionNative();   // 首选：带 configuration.hnsw.space=cosine
        try {
            api.createCollection(tenant, database,
                    new ChromaApi.CreateCollectionRequest(collectionName, Map.of("hnsw:space", "cosine")));
        } catch (Exception e) {
            log.debug("Chroma collection 创建跳过（多半已存在）：{}，原因={}", collectionName, chop(e.getMessage()));
        }
        // 空间校正：l2 空集合 → 按 cosine 重建；非空保留并换算阈值（见 ChromaClient 检索）
        String sp = detectSpace();
        if (sp != null) {
            space = sp;
        }
        if (!"cosine".equalsIgnoreCase(space)) {
            Long n = countQuietly(api);
            if (n != null && n == 0) {
                deleteCollectionNative();
                createCollectionNative();
                String again = detectSpace();
                if (again != null) {
                    space = again;
                }
                log.info("Chroma collection {} 原为 {} 空间且为空，已重建为 {} 空间（相似度阈值与 MySQL 余弦口径一致）",
                        collectionName, sp, space);
            } else {
                log.warn("Chroma collection {} 使用 {} 空间（非 cosine），检索阈值按该空间换算；"
                                + "如需完全对齐余弦语义，可清空该 collection 后重启应用由本服务重建",
                        collectionName, space);
            }
        }
    }

    /**
     * 原生 HTTP 建集合（cosine，get_or_create）。Chroma 1.x 用 configuration.hnsw，0.5.x 用
     * configuration.hnsw_configuration 且内层带 _type；按「新版优先、老版兜底」各发一次，都失败则交由 Spring AI 建库兜底。
     */
    private void createCollectionNative() {
        if (postCollection("hnsw")) {
            return;
        }
        postCollection("hnsw_configuration");
    }

    /** 发一次建集合请求（get_or_create），返回是否 2xx。 */
    private boolean postCollection(String configKey) {
        try (cn.hutool.http.HttpResponse resp = cn.hutool.http.HttpRequest.post(collectionsUrl())
                .body(JSONUtil.createObj()
                        .set("name", collectionName)
                        .set("get_or_create", true)
                        .set("configuration", JSONUtil.createObj()
                                .set(configKey, hnswConfig(configKey))
                                .set("_type", "CollectionConfigurationInternal"))
                        .toString())
                .timeout(5000)
                .execute()) {
            boolean ok = resp.getStatus() >= 200 && resp.getStatus() < 300;
            if (!ok) {
                // 正常现象：0.5.x 不认 configuration.hnsw（返回 400），接下来会换 hnsw_configuration 再试
                log.debug("Chroma 建集合（{} 载荷）未被接受，将换用兼容载荷：collection={}，status={}，body={}",
                        configKey, collectionName, resp.getStatus(), chop(resp.body()));
            }
            return ok;
        } catch (Exception e) {
            log.debug("Chroma 原生建集合失败（{}={}）：原因={}", configKey, collectionName, chop(e.getMessage()));
            return false;
        }
    }

    /** 构造 hnsw 配置片段：0.5.x 的 hnsw_configuration 需要显式 _type。 */
    private static cn.hutool.json.JSONObject hnswConfig(String configKey) {
        cn.hutool.json.JSONObject inner = JSONUtil.createObj().set("space", "cosine");
        if ("hnsw_configuration".equals(configKey)) {
            inner.set("_type", "HNSWConfigurationInternal");
        }
        return inner;
    }

    /** 删除集合（仅用于把空的错误空间集合重建为 cosine）。 */
    private void deleteCollectionNative() {
        try {
            cn.hutool.http.HttpRequest.delete(collectionsUrl(collectionName)).timeout(5000).execute().close();
        } catch (Exception e) {
            log.debug("Chroma 删除集合失败：{}，原因={}", collectionName, chop(e.getMessage()));
        }
    }

    /**
     * 探测集合实际空间（cosine/l2）：真实空间在 configuration_json 里，只能原生 HTTP 读取。失败返回 null（按 cosine 处理）。
     */
    private String detectSpace() {
        try {
            String s = cn.hutool.http.HttpRequest.get(collectionsUrl(collectionName)).timeout(5000).execute().body();
            String sp = JSONUtil.parseObj(s)
                    .getByPath("configuration_json.hnsw_configuration.space", String.class);
            return sp == null || sp.isBlank() ? null : sp;
        } catch (Exception e) {
            log.debug("Chroma 空间探测失败（按 cosine 处理）：原因={}", chop(e.getMessage()));
            return null;
        }
    }

    /** 静默统计集合向量条数（失败返回 null）。 */
    private Long countQuietly(ChromaApi api) {
        try {
            ChromaApi.Collection c = api.getCollection(tenant, database, collectionName);
            return c == null || c.id() == null ? null : api.countEmbeddings(tenant, database, c.id());
        } catch (Exception e) {
            log.debug("Chroma 条数统计失败：原因={}", chop(e.getMessage()));
            return null;
        }
    }

    /** /api/v1/collections 基地址（带 tenant/database 查询参数）。 */
    private String collectionsUrl() {
        return trimSlash(baseUrl) + "/api/v1/collections?tenant=" + tenant + "&database=" + database;
    }

    /** 单个集合地址（原生 GET / DELETE）：/api/v1/collections/{name}。 */
    private String collectionsUrl(String name) {
        return trimSlash(baseUrl) + "/api/v1/collections/" + name + "?tenant=" + tenant + "&database=" + database;
    }

    private static String trimSlash(String s) {
        return s == null ? "" : (s.endsWith("/") ? s.substring(0, s.length() - 1) : s);
    }

    /** 截断异常信息，避免 409 的完整响应体刷屏 debug 日志。 */
    private static String chop(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }
}
