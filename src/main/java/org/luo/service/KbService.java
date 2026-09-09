package org.luo.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.dto.AddChunksRequest;
import org.luo.dto.CreateKbRequest;
import org.luo.dto.KbChunkPageResult;
import org.luo.entity.Agent;
import org.luo.entity.KbFile;
import org.luo.entity.KnowledgeBase;
import org.luo.entity.KnowledgeChunk;
import org.luo.enums.ChunkStrategy;
import org.luo.exception.AiBusinessException;
import org.luo.exception.AiErrorCode;
import org.luo.mapper.KbFileMapper;
import org.luo.mapper.KnowledgeBaseMapper;
import org.luo.mapper.KnowledgeChunkMapper;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库（RAG）管理服务：建库 / 文件上传解析分块入库 / 删除 / Chroma 双写同步（只写不读检索）。
 * <p>
 * 知识库模型：全局库（{@code kb.agent_id IS NULL}，名称「通用知识库」）+ 每个智能体至多一个专属库；
 * 上传文件按库管理（见 kb_file / registerFile），文件内容按库分块并向量化入库：
 * <b>MySQL 永远先落（源，kb_chunk.embedding 存向量 JSON），Chroma 跟随 upsert（加速检索副本），
 * Chroma 失败只 warn 不阻断（副本可被 {@link #syncToChroma} 回填）</b>。
 * <p>
 * 对话侧检索（会话级 RAG 开关 → 自动多库合并检索 → 注入「[知识库资料]」）已拆到
 * {@link KbSearchService}：本服务只负责写入与管理，避免上帝类继续膨胀。
 * <p>
 * <b>容错契约</b>：写入侧（添加知识）向量化失败则明确报错，避免把无向量垃圾数据入库；
 * 检索侧任何失败只降级、绝不阻断对话（见 KbSearchService）。
 */
@Slf4j
@Service
public class KbService {

    /** 全局知识库的默认名称（agent_id 为 NULL 的那一层）。 */
    private static final String GLOBAL_KB_NAME = "通用知识库";

    /** 批量向量化的单批条数（一次 HTTP 请求的条数上限，避免超限）。 */
    private static final int EMBED_BATCH = 16;


    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KbFileMapper fileMapper;
    private final AgentService agentService;
    private final ChunkingService chunkingService;
    private final ChromaVectorStoreService chromaStore;
    private final ObjectProvider<EmbeddingModel> embeddingProvider;

    public KbService(KnowledgeBaseMapper kbMapper,
                     KnowledgeChunkMapper chunkMapper,
                     KbFileMapper fileMapper,
                     AgentService agentService,
                     ChunkingService chunkingService,
                     ChromaVectorStoreService chromaStore,
                     ObjectProvider<EmbeddingModel> embeddingProvider) {
        this.kbMapper = kbMapper;
        this.chunkMapper = chunkMapper;
        this.fileMapper = fileMapper;
        this.agentService = agentService;
        this.chunkingService = chunkingService;
        this.chromaStore = chromaStore;
        this.embeddingProvider = embeddingProvider;
    }

    // ==================== 库管理 ====================

    /** 查询全局知识库（不存在返回 null，不创建；供列表/检索用，避免误触发建库）。 */
    public KnowledgeBase getGlobal() {
        return kbMapper.selectOne(new QueryWrapper<KnowledgeBase>()
                .isNull("agent_id").last("LIMIT 1"));
    }

    /**
     * 获取全局知识库，不存在则创建（全局库单例由本方法保证，检索/列表前置调用）。
     * synchronized 防并发重复创建（唯一索引对 NULL 不生效，只能靠代码约束）。
     */
    public synchronized KnowledgeBase getOrCreateGlobal() {
        KnowledgeBase g = getGlobal();
        if (g != null) return g;
        LocalDateTime now = LocalDateTime.now();
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(GLOBAL_KB_NAME);
        kb.setDescription("全局知识库：可在任意会话的「资料库」选择器中选用（选中后该会话每轮检索此库）");
        kb.setDocCount(0);
        kb.setChunkStrategy(ChunkStrategy.defaultStrategy().getKey());
        kb.setChunkOverlap(ChunkingService.DEFAULT_OVERLAP);
        kb.setCreatedAt(now);
        kb.setUpdatedAt(now);
        kbMapper.insert(kb);
        log.info("知识库：全局库不存在，已自动创建 id={}", kb.getId());
        return kb;
    }

    /**
     * 创建知识库：传 agentId 则为该智能体建专属库（至多一个），否则视为普通库（前端用它建全局库不推荐，
     * 全局库走 {@link #getOrCreateGlobal()}）。
     *
     * @throws AiBusinessException 名称空 / agentId 已存在专属库 / 智能体不存在时抛出
     */
    @Transactional
    public KnowledgeBase createKb(CreateKbRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "知识库名称不能为空");
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(req.name().trim());
        kb.setDescription(req.description() != null ? req.description().trim() : null);
        if (req.agentId() != null) {
            Agent a = agentService.getAgent(req.agentId());
            if (a == null) {
                throw new AiBusinessException(AiErrorCode.NOT_FOUND, "智能体不存在：" + req.agentId());
            }
            if (kbMapper.selectCount(new QueryWrapper<KnowledgeBase>().eq("agent_id", req.agentId())) > 0) {
                throw new AiBusinessException(AiErrorCode.CONFLICT,
                        "智能体「" + a.getName() + "」已有一个专属知识库，每个智能体至多维护一个");
            }
            kb.setAgentId(req.agentId());
        }
        LocalDateTime now = LocalDateTime.now();
        kb.setDocCount(0);
        // 默认分片配置：未指定用全局默认（recursive / 60 字重叠），作为该库新上传文件的继承值
        kb.setChunkStrategy(ChunkStrategy.fromKey(req.chunkStrategy()).getKey());
        kb.setChunkOverlap(req.chunkOverlap() == null ? ChunkingService.DEFAULT_OVERLAP : clampOverlap(req.chunkOverlap()));
        kb.setCreatedAt(now);
        kb.setUpdatedAt(now);
        kbMapper.insert(kb);
        log.info("知识库创建：id={}，name={}，agentId={}，默认分片策略={}，默认重叠={}",
                kb.getId(), kb.getName(), req.agentId(), kb.getChunkStrategy(), kb.getChunkOverlap());
        return kb;
    }

    /**
     * 更新知识库（重命名 / 改说明 / 调整默认分片配置）。
     * name、description、chunkStrategy、chunkOverlap 任一非空（chunkOverlap 为 null 视为不修改，0 是合法值）即生效；
     * 全空则忽略。
     */
    public KnowledgeBase updateKb(Long id, CreateKbRequest req) {
        KnowledgeBase kb = requireKb(id);
        boolean changed = false;
        if (req != null && req.name() != null && !req.name().isBlank() && !req.name().trim().equals(kb.getName())) {
            kb.setName(req.name().trim());
            changed = true;
        }
        if (req != null && req.description() != null && !req.description().trim().equals(
                kb.getDescription() == null ? "" : kb.getDescription())) {
            kb.setDescription(req.description().trim());
            changed = true;
        }
        if (req != null && req.chunkStrategy() != null && !req.chunkStrategy().isBlank()) {
            String ns = ChunkStrategy.fromKey(req.chunkStrategy()).getKey();
            if (!ns.equals(kb.getChunkStrategy())) {
                kb.setChunkStrategy(ns);
                changed = true;
            }
        }
        if (req != null && req.chunkOverlap() != null && !req.chunkOverlap().equals(kb.getChunkOverlap())) {
            kb.setChunkOverlap(clampOverlap(req.chunkOverlap()));
            changed = true;
        }
        if (changed) {
            kb.setUpdatedAt(LocalDateTime.now());
            kbMapper.updateById(kb);
        }
        return kb;
    }

    /** 删除知识库（连同其全部知识块与文件登记；Chroma 侧向量按块 id 同步清理）。 */
    @Transactional
    public void deleteKb(Long id) {
        KnowledgeBase kb = requireKb(id);
        List<Long> chunkIds = chunkIdsOfKb(id);
        chunkMapper.delete(new QueryWrapper<KnowledgeChunk>().eq("kb_id", id));
        fileMapper.delete(new QueryWrapper<KbFile>().eq("kb_id", id));
        kbMapper.deleteById(id);
        chromaStore.deleteByChunkIds(chunkIds);   // Chroma 失败仅 warn（MySQL 已删，残留可 sync 清）
        log.info("知识库删除：id={}，name={}（含 {} 个知识块，同步清 Chroma {} 条）",
                id, kb.getName(), chunkIds.size(), chunkIds.size());
    }

    /** 全部知识库列表（全局库置顶，其余按更新时间倒序）。 */
    public List<KnowledgeBase> listKbs() {
        // 先确保全局库存在（列表页恒有「通用知识库」入口）
        getOrCreateGlobal();
        QueryWrapper<KnowledgeBase> qw = new QueryWrapper<>();
        qw.orderByAsc("agent_id").orderByDesc("updated_at"); // MySQL 中 NULL 排最前 → 全局库置顶
        return kbMapper.selectList(qw);
    }

    // ==================== 知识块管理 ====================

    /** 分页查看某库的知识块（按时间倒序，最新在前）。 */
    public KbChunkPageResult pageChunks(Long kbId, int offset, int limit) {
        requireKb(kbId);
        long total = chunkMapper.selectCount(new QueryWrapper<KnowledgeChunk>().eq("kb_id", kbId));
        int size = Math.min(Math.max(limit, 1), 100);
        int off = Math.max(offset, 0);
        List<KnowledgeChunk> list = chunkMapper.selectList(new QueryWrapper<KnowledgeChunk>()
                .eq("kb_id", kbId).orderByDesc("created_at").last("LIMIT " + off + ", " + size));
        return new KbChunkPageResult(total, list);
    }

    /**
     * 批量添加知识（手动文本入口，保留兼容）：文本按默认分片策略切块 → 批量向量化 →
     * 逐块入库并刷新库的 doc_count。
     *
     * @return 实际新增的知识块数
     * @throws AiBusinessException 向量化服务不可用或调用失败时抛出（无向量数据不入库）
     */
    @Transactional
    public int addChunks(Long kbId, AddChunksRequest req) {
        KnowledgeBase kb = requireKb(kbId);
        if (req == null || req.texts() == null || req.texts().isEmpty()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "知识文本不能为空");
        }
        // 手动添加文本同样服从该库的默认分片配置（策略 + 重叠）
        ChunkStrategy strategy = kbStrategy(kb, null);
        int ov = kbOverlap(kb, null);
        List<String> blocks = new ArrayList<>();
        for (String t : req.texts()) {
            if (t == null || t.isBlank()) continue;
            blocks.addAll(chunkingService.chunk(t, strategy, ov));
        }
        return doAddBlocks(kb, normSource(req.source()), blocks);
    }

    /**
     * 上传文件内容入库并登记到该库的文件列表（核心文件上传入口）。
     * <p>
     * 语义：解析出的文本 → 按指定策略分块 → 向量化 → 写入 kb_chunk（source = 文件名），并在 kb_file
     * 登记 / 刷新文件记录（保存所选分片策略与解析原文，供日后不重传直接「重新分片」）。
     * 同一库内文件名唯一：<b>同名重传 = 替换</b>——先清掉该文件旧知识块
     * （解析与向量化全部成功后才删旧写新，中途失败整体回滚，旧数据不受影响）。
     *
     * @param kbId        知识库 ID
     * @param fileName    原始文件名（入库时截断规范到 200 字符，kb_file.file_name 与块 source 一致）
     * @param sizeBytes   文件大小（字节）
     * @param fullText    解析出的全文（为空则明确报错，避免空文件刷数据）
     * @param strategyKey 分片策略 key（fixed/paragraph/recursive/markdown）；null/空 = 继承该库默认分片策略（知识库设置）
     * @param overlap     相邻块重叠字符数；null = 继承该库默认重叠（知识库设置），0=不重叠
     * @return 登记后的文件记录（含 id / chunkCount / chunkStrategy / chunkOverlap）
     * @throws AiBusinessException 文本为空 / 向量化失败时抛出（事务回滚）
     */
    @Transactional
    public KbFile registerFile(Long kbId, String fileName, long sizeBytes, String fullText,
                               String strategyKey, Integer overlap) {
        KnowledgeBase kb = requireKb(kbId);
        if (fullText == null || fullText.isBlank()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "未能从文件中提取到可入库的文本");
        }
        ChunkStrategy strategy = kbStrategy(kb, strategyKey);
        int ov = kbOverlap(kb, overlap);
        List<String> blocks = chunkingService.chunk(fullText, strategy, ov);
        if (blocks.isEmpty()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "未能从文件中提取到可入库的文本");
        }
        List<float[]> vectors = embedAll(blocks);   // 失败抛异常 → 整体回滚，旧数据不受影响
        String src = normSource(fileName);
        if (src == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "文件名不能为空");
        }
        LocalDateTime now = LocalDateTime.now();

        // 同名重传：先清旧知识块（按 kb_id + source），再写新块（MySQL 为源，Chroma 副本跟随：
        // 旧 id 文档先删避免孤儿残留，新块 upsert；任一步失败仅 warn，MySQL 已留档）
        List<Long> oldIds = chunkIdsOf(kbId, src);
        if (!oldIds.isEmpty()) {
            chunkMapper.delete(new QueryWrapper<KnowledgeChunk>()
                    .eq("kb_id", kbId).eq("source", src));
            chromaStore.deleteByChunkIds(oldIds);
        }
        List<KnowledgeChunk> inserted = insertChunks(kbId, src, blocks, vectors, now);
        chromaStore.upsertChunks(kbId, inserted);   // Chroma 失败仅 warn（MySQL 已留档，可用 sync 回填）

        // 文件登记：首次上传插入，同名重传仅刷新（保留首次上传时间）；保存策略与原文（供重新分片）
        KbFile file = fileMapper.selectOne(new QueryWrapper<KbFile>()
                .eq("kb_id", kbId).eq("file_name", src).last("LIMIT 1"));
        if (file == null) {
            file = new KbFile();
            file.setKbId(kbId);
            file.setFileName(src);
            file.setCreatedAt(now);
        }
        file.setFileType(extOf(src));
        file.setChunkStrategy(strategy.getKey());
        file.setChunkOverlap(ov);
        file.setSizeBytes(sizeBytes);
        file.setChunkCount(blocks.size());
        file.setRawText(fullText);
        file.setUpdatedAt(now);
        if (file.getId() == null) {
            fileMapper.insert(file);
        } else {
            fileMapper.updateById(file);
        }

        int old = kb.getDocCount() == null ? 0 : kb.getDocCount();
        kb.setDocCount(old - oldIds.size() + blocks.size());
        kb.setUpdatedAt(now);
        kbMapper.updateById(kb);
        log.info("知识库上传文件：kbId={}，文件={}（{} 字节），分片策略={}，重叠={}，分块={}，替换旧块={}，总计={}",
                kbId, src, sizeBytes, strategy.getKey(), ov, blocks.size(), oldIds.size(), kb.getDocCount());
        return file;
    }

    /** 某知识库的文件列表（按最近更新时间倒序，最新在前；rawText 大字段不查询不回传，避免流量浪费）。 */
    public List<KbFile> listFiles(Long kbId) {
        requireKb(kbId);
        // 显式列清单：排除 raw_text（LONGTEXT 大字段），列表场景不需要原文
        return fileMapper.selectList(new QueryWrapper<KbFile>()
                .select("id", "kb_id", "file_name", "file_type", "chunk_strategy", "chunk_overlap",
                        "size_bytes", "chunk_count", "created_at", "updated_at")
                .eq("kb_id", kbId).orderByDesc("updated_at"));
    }

    /**
     * 对已入库文件重新分片（动态切换分片策略 / 重叠字数，无需重新上传）。
     * <p>
     * 语义：读该文件入库时保存的原文 → 按新策略与重叠切块 → 向量化 → 删除旧知识块并写入新块 →
     * 刷新文件的分块数 / 策略 / 重叠与库的 doc_count（事务：中途失败整体回滚，旧块保留）。
     *
     * @param kbId        知识库 ID
     * @param fileId      文件记录 ID
     * @param strategyKey 目标分片策略 key；null/未知回退默认
     * @param overlap     目标重叠字符数；null 沿用文件当前值（再缺省用默认），0 = 不重叠
     * @return 重新分片后的知识块数
     * @throws AiBusinessException 文件不存在 / 无保存原文（老数据）/ 向量化失败时抛出
     */
    @Transactional
    public int rechunkFile(Long kbId, Long fileId, String strategyKey, Integer overlap) {
        requireKb(kbId);
        KbFile file = fileMapper.selectById(fileId);
        if (file == null || !kbId.equals(file.getKbId())) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "文件不存在：" + fileId);
        }
        ChunkStrategy strategy = ChunkStrategy.fromKey(strategyKey);
        int oldOverlap = file.getChunkOverlap() == null ? ChunkingService.DEFAULT_OVERLAP : file.getChunkOverlap();
        int ov = overlap == null ? oldOverlap : overlap;
        String oldStrategy = file.getChunkStrategy();
        if (strategy.getKey().equals(oldStrategy) && ov == oldOverlap) {
            return file.getChunkCount() == null ? 0 : file.getChunkCount();   // 策略与重叠都未变，直接返回现块数
        }
        String raw = file.getRawText();
        if (raw == null || raw.isBlank()) {
            throw new AiBusinessException(AiErrorCode.CONFLICT,
                    "文件「" + file.getFileName() + "」未保存原文，无法直接切换分片策略；请删除后重新上传");
        }
        List<String> blocks = chunkingService.chunk(raw, strategy, ov);
        if (blocks.isEmpty()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "原文为空，无法重新分片");
        }
        List<float[]> vectors = embedAll(blocks);   // 失败抛异常 → 整体回滚，旧块保留
        String src = file.getFileName();
        List<Long> oldIds = chunkIdsOf(kbId, src);
        if (!oldIds.isEmpty()) {
            chunkMapper.delete(new QueryWrapper<KnowledgeChunk>()
                    .eq("kb_id", kbId).eq("source", src));
            chromaStore.deleteByChunkIds(oldIds);   // 旧 id Chroma 文档同步清理，避免孤儿残留
        }
        LocalDateTime now = LocalDateTime.now();
        List<KnowledgeChunk> inserted = insertChunks(kbId, src, blocks, vectors, now);
        chromaStore.upsertChunks(kbId, inserted);   // Chroma 失败仅 warn（MySQL 已留档，可用 sync 回填）
        file.setChunkStrategy(strategy.getKey());
        file.setChunkOverlap(ov);
        file.setChunkCount(blocks.size());
        file.setUpdatedAt(now);
        fileMapper.updateById(file);

        KnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb != null) {
            int old = kb.getDocCount() == null ? 0 : kb.getDocCount();
            kb.setDocCount(Math.max(0, old - oldIds.size()) + blocks.size());
            kb.setUpdatedAt(now);
            kbMapper.updateById(kb);
        }
        log.info("知识库重新分片：kbId={}，文件={}，{}@重叠{} → {}@重叠{}，分块 {} → {}",
                kbId, src, oldStrategy, oldOverlap, strategy.getKey(), ov, oldIds.size(), blocks.size());
        return blocks.size();
    }

    /**
     * 删除知识库中的文件：删除文件登记，并级联删除该文件全部知识块、回减库的 doc_count，
     * Chroma 侧按块 id 同步清理（失败仅 warn，MySQL 已删）。
     *
     * @return 级联删除的知识块数
     * @throws AiBusinessException 文件不存在或不属于该库时抛出
     */
    @Transactional
    public int deleteFile(Long kbId, Long fileId) {
        requireKb(kbId);
        KbFile file = fileMapper.selectById(fileId);
        if (file == null || !kbId.equals(file.getKbId())) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "文件不存在：" + fileId);
        }
        List<Long> chunkIds = chunkIdsOf(kbId, file.getFileName());
        int removed = chunkMapper.delete(new QueryWrapper<KnowledgeChunk>()
                .eq("kb_id", kbId).eq("source", file.getFileName()));
        KnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb != null && kb.getDocCount() != null && removed > 0) {
            kb.setDocCount(Math.max(0, kb.getDocCount() - removed));
            kb.setUpdatedAt(LocalDateTime.now());
            kbMapper.updateById(kb);
        }
        fileMapper.deleteById(fileId);
        chromaStore.deleteByChunkIds(chunkIds);   // Chroma 失败仅 warn（副本残留可用 sync 清）
        log.info("知识库删除文件：kbId={}，文件={}，级联删除知识块={}（同步清 Chroma {} 条）",
                kbId, file.getFileName(), removed, chunkIds.size());
        return removed;
    }

    // ==================== 分片配置解析（知识库级默认 + 调用方覆盖） ====================

    /**
     * 解析本次入库生效的分片策略：显式传入（非空）优先；否则继承该库设置的默认策略；
     * 库未配置（老数据）回退全局默认 {@link ChunkStrategy#defaultStrategy()}。未知 key 宽容回退默认。
     */
    private static ChunkStrategy kbStrategy(KnowledgeBase kb, String key) {
        if (key != null && !key.isBlank()) {
            return ChunkStrategy.fromKey(key);
        }
        return ChunkStrategy.fromKey(kb == null ? null : kb.getChunkStrategy());
    }

    /**
     * 解析本次入库生效的重叠字数：显式传入（非 null，0 是合法值）优先；否则继承该库设置的默认重叠；
     * 库未配置回退 {@link ChunkingService#DEFAULT_OVERLAP}。统一收敛到 [0, MAX_OVERLAP]。
     */
    private static int kbOverlap(KnowledgeBase kb, Integer overlap) {
        if (overlap != null) {
            return clampOverlap(overlap);
        }
        Integer def = kb == null ? null : kb.getChunkOverlap();
        return def == null ? ChunkingService.DEFAULT_OVERLAP : clampOverlap(def);
    }

    /** 重叠字数收敛到合法区间 [0, {@link ChunkingService#MAX_OVERLAP}]。 */
    private static int clampOverlap(int v) {
        return Math.max(0, Math.min(v, ChunkingService.MAX_OVERLAP));
    }

    /** 文件扩展名小写（无扩展名返回 null），如 "README.MD" → "md"。 */
    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) return null;
        return name.substring(i + 1).toLowerCase();
    }

    /** 来源标注规范化：去首尾空白、超 200 字符截断；空串归一为 null。 */
    private static String normSource(String source) {
        if (source == null) return null;
        String s = source.strip();
        if (s.isEmpty()) return null;
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /**
     * 分块入库核心：向量化 → 逐块写入 → 刷新 doc_count。调用方需处于事务中，
     * 向量化失败抛错即可整体回滚。
     */
    private int doAddBlocks(KnowledgeBase kb, String source, List<String> blocks) {
        if (blocks.isEmpty()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "知识文本为空，无法入库");
        }
        List<float[]> vectors = embedAll(blocks);   // 失败抛异常 → 事务回滚，不落半截数据
        LocalDateTime now = LocalDateTime.now();
        List<KnowledgeChunk> inserted = insertChunks(kb.getId(), source, blocks, vectors, now);
        chromaStore.upsertChunks(kb.getId(), inserted);   // Chroma 失败仅 warn（MySQL 已留档）
        int old = kb.getDocCount() == null ? 0 : kb.getDocCount();
        kb.setDocCount(old + blocks.size());
        kb.setUpdatedAt(now);
        kbMapper.updateById(kb);
        log.info("知识库添加知识：kbId={}，来源={}，分块={}，总计={}", kb.getId(), source, blocks.size(), kb.getDocCount());
        return blocks.size();
    }

    /** 删除单个知识块，并同步扣减库的 doc_count（该块属于某登记文件时同步扣减其 chunk_count）。 */
    @Transactional
    public void deleteChunk(Long kbId, Long chunkId) {
        requireKb(kbId);
        KnowledgeChunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null) return;
        if (chunkMapper.deleteById(chunkId) > 0) {
            chromaStore.deleteByChunkIds(List.of(chunkId));   // Chroma 失败仅 warn
            KnowledgeBase kb = kbMapper.selectById(kbId);
            if (kb != null && kb.getDocCount() != null && kb.getDocCount() > 0) {
                kb.setDocCount(kb.getDocCount() - 1);
                kb.setUpdatedAt(LocalDateTime.now());
                kbMapper.updateById(kb);
            }
            if (chunk.getSource() != null && !chunk.getSource().isBlank()) {
                KbFile f = fileMapper.selectOne(new QueryWrapper<KbFile>()
                        .eq("kb_id", kbId).eq("file_name", chunk.getSource()).last("LIMIT 1"));
                if (f != null && f.getChunkCount() != null && f.getChunkCount() > 0) {
                    f.setChunkCount(f.getChunkCount() - 1);
                    f.setUpdatedAt(LocalDateTime.now());
                    fileMapper.updateById(f);
                }
            }
        }
    }

    // ==================== 写入辅助（MySQL 源 + Chroma 副本双写共用） ====================

    /**
     * 批量插入知识块到 MySQL（源）。MP insert 会回填自增 id，供随后 Chroma upsert 作文档 id。
     *
     * @return 已落库（含 id）的知识块列表
     */
    private List<KnowledgeChunk> insertChunks(Long kbId, String source, List<String> blocks,
                                              List<float[]> vectors, LocalDateTime now) {
        List<KnowledgeChunk> inserted = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            KnowledgeChunk c = new KnowledgeChunk();
            c.setKbId(kbId);
            c.setContent(blocks.get(i));
            c.setSource(source);
            c.setEmbedding(JSONUtil.toJsonStr(vectors.get(i)));
            c.setCreatedAt(now);
            chunkMapper.insert(c);
            inserted.add(c);
        }
        return inserted;
    }

    /** 某库内指定来源（文件名）的全部知识块 id（替换重传 / 删除文件时同步清理 Chroma）。 */
    private List<Long> chunkIdsOf(Long kbId, String source) {
        return chunkMapper.selectList(new QueryWrapper<KnowledgeChunk>()
                        .select("id").eq("kb_id", kbId).eq("source", source))
                .stream().map(KnowledgeChunk::getId).toList();
    }

    /** 某库全部知识块 id（删除知识库时同步清理 Chroma）。 */
    private List<Long> chunkIdsOfKb(Long kbId) {
        return chunkMapper.selectList(new QueryWrapper<KnowledgeChunk>()
                        .select("id").eq("kb_id", kbId))
                .stream().map(KnowledgeChunk::getId).toList();
    }

    /**
     * Chroma 幂等回填（POST /api/kb/chroma/sync）：把 MySQL 存量知识块按库分批 upsert 到 Chroma。
     * 按 chunk id 覆盖，重复执行安全，无需先清空副本；用于 Chroma 首次启用或故障恢复后补齐副本。
     *
     * @param kbId null = 全部知识库；非空 = 仅该库
     * @return 成功回填的知识块数（Chroma 不可用 / 无数据时为 0）
     */
    public int syncToChroma(Long kbId) {
        int synced = 0;
        int pageSize = 200;
        for (int offset = 0; ; offset += pageSize) {
            List<KnowledgeChunk> page = chunkMapper.selectList(new QueryWrapper<KnowledgeChunk>()
                    .eq(kbId != null, "kb_id", kbId)
                    .last("LIMIT " + offset + ", " + pageSize));
            if (page.isEmpty()) break;
            // 页内按 kb 分组攒批，减少 Chroma 调用次数（upsert 内部会再次向量化文本）
            Map<Long, List<KnowledgeChunk>> byKb = new LinkedHashMap<>();
            for (KnowledgeChunk c : page) {
                if (c.getKbId() == null || c.getContent() == null) continue;
                byKb.computeIfAbsent(c.getKbId(), k -> new ArrayList<>()).add(c);
            }
            for (Map.Entry<Long, List<KnowledgeChunk>> e : byKb.entrySet()) {
                if (chromaStore.upsertChunks(e.getKey(), e.getValue())) {
                    synced += e.getValue().size();
                }
            }
            if (page.size() < pageSize) break;
        }
        log.info("Chroma 幂等回填完成：kbId={}，成功回填={} 块", kbId, synced);
        return synced;
    }

    // ==================== 向量化 ====================

    /** Embedding 模型可用性（null = 未配置，检索降级为空、写入明确报错）。 */
    private EmbeddingModel embedding() {
        return embeddingProvider.getIfAvailable();
    }

    /** 批量向量化（分批调用，任一批失败即抛错，由事务保证不落数据）。 */
    private List<float[]> embedAll(List<String> texts) {
        EmbeddingModel model = embedding();
        if (model == null) {
            throw new AiBusinessException(AiErrorCode.INTERNAL_ERROR,
                    "未配置 Embedding 模型（spring.ai.openai.embedding），无法为知识生成向量");
        }
        List<float[]> all = new ArrayList<>();
        try {
            for (int i = 0; i < texts.size(); i += EMBED_BATCH) {
                List<String> batch = texts.subList(i, Math.min(texts.size(), i + EMBED_BATCH));
                all.addAll(model.embed(batch));
            }
            return all;
        } catch (Exception e) {
            log.error("知识向量化失败：{}", e.getMessage(), e);
            throw new AiBusinessException(AiErrorCode.INTERNAL_ERROR,
                    "向量化服务调用失败：" + e.getMessage() + "（请确认 Embedding 服务地址与模型配置）");
        }
    }

    private KnowledgeBase requireKb(Long id) {
        if (id == null) throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "知识库 ID 不能为空");
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) throw new AiBusinessException(AiErrorCode.NOT_FOUND, "知识库不存在：" + id);
        return kb;
    }
}
