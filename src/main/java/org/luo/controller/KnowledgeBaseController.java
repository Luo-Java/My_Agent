package org.luo.controller;

import org.luo.dto.AddChunksRequest;
import org.luo.dto.CreateKbRequest;
import org.luo.dto.KbChunkPageResult;
import org.luo.dto.KbRechunkResult;
import org.luo.entity.KbFile;
import org.luo.entity.KnowledgeBase;
import org.luo.enums.ChunkStrategy;
import org.luo.exception.AiBusinessException;
import org.luo.infrastructure.document.DocumentParserService;
import org.luo.service.ChunkingService;
import org.luo.service.KbService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.luo.exception.AiErrorCode;

/**
 * 知识库管理接口（RAG：全局库 + 每个智能体一个专属库）。
 * <p>
 * GET/POST /api/kb、GET /api/kb/global、PUT/DELETE /api/kb/{id}；
 * GET/POST /api/kb/{id}/chunks、DELETE /api/kb/{id}/chunks/{cid}；
 * GET /api/kb/chunk-strategies；GET /api/kb/{id}/files、POST /api/kb/{id}/upload、
 * DELETE /api/kb/{id}/files/{fid}、POST /api/kb/{id}/files/{fid}/rechunk；
 * POST /api/kb/chroma/sync、GET /api/kb/chroma/status。
 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeBaseController {

    private final KbService kbService;
    private final DocumentParserService documentParser;

    public KnowledgeBaseController(KbService kbService, DocumentParserService documentParser) {
        this.kbService = kbService;
        this.documentParser = documentParser;
    }

    /** 全部知识库列表（调用前确保全局库存在，全局库恒在首位）。 */
    @GetMapping
    public List<KnowledgeBase> list() {
        return kbService.listKbs();
    }

    /** 获取全局知识库（不存在则自动创建）。 */
    @GetMapping("/global")
    public KnowledgeBase global() {
        return kbService.getOrCreateGlobal();
    }

    /** 全部分片策略元数据 + 重叠字数默认值/上限（前端渲染下拉；动态新增策略无需改前端）。 */
    @GetMapping("/chunk-strategies")
    public Map<String, Object> chunkStrategies() {
        List<Map<String, String>> list = new ArrayList<>();
        for (ChunkStrategy s : ChunkStrategy.values()) {
            Map<String, String> m = new HashMap<>();
            m.put("key", s.getKey());
            m.put("label", s.getLabel());
            m.put("desc", s.getDesc());
            list.add(m);
        }
        return Map.of(
                "strategies", list,
                "defaultOverlap", ChunkingService.DEFAULT_OVERLAP,
                "maxOverlap", ChunkingService.MAX_OVERLAP);
    }

    @PostMapping
    public KnowledgeBase create(@RequestBody CreateKbRequest req) {
        return kbService.createKb(req);
    }

    @PutMapping("/{id}")
    public KnowledgeBase update(@PathVariable Long id, @RequestBody CreateKbRequest req) {
        return kbService.updateKb(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        kbService.deleteKb(id);
    }

    /** 知识块分页（默认 offset=0，limit=20，最大 100）。 */
    @GetMapping("/{id}/chunks")
    public KbChunkPageResult chunks(@PathVariable Long id,
                                    @RequestParam(defaultValue = "0") int offset,
                                    @RequestParam(defaultValue = "20") int limit) {
        return kbService.pageChunks(id, offset, limit);
    }

    /** 批量添加知识：自动分块 + 向量化，返回新增块数（手动文本入口保留兼容）。 */
    @PostMapping("/{id}/chunks")
    public java.util.Map<String, Object> addChunks(@PathVariable Long id, @RequestBody AddChunksRequest req) {
        int added = kbService.addChunks(id, req);
        return java.util.Map.of("added", added);
    }

    /**
     * 上传文件导入知识（multipart/form-data，参数名 files，可多文件）。每个文件独立：解析 → 分块 →
     * 向量化入库 → 登记文件列表。单文件失败不影响其它，逐条返回
     * {@code [{fileName, status: ok|error, added, fileId?, chunkStrategy?, chunkOverlap?, message?}]}。
     * 同库内同名文件 = 替换重传。
     *
     * @param chunkStrategy 策略 key：fixed/paragraph/recursive/markdown；缺省继承库默认，未知回退 recursive
     * @param overlap       重叠字数；缺省继承库默认，0 = 不重叠，上限 {@link ChunkingService#MAX_OVERLAP}
     */
    @PostMapping(value = "/{id}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@PathVariable Long id,
                                      @RequestParam("files") MultipartFile[] files,
                                      @RequestParam(value = "chunkStrategy", required = false) String chunkStrategy,
                                      @RequestParam(value = "overlap", required = false) Integer overlap) {
        List<Map<String, Object>> results = new ArrayList<>();
        MultipartFile[] arr = files == null ? new MultipartFile[0] : files;
        if (arr.length == 0) {
            throw new AiBusinessException(org.luo.exception.AiErrorCode.BAD_REQUEST, "未选择要上传的文件");
        }
        for (MultipartFile file : arr) {
            String fileName = file == null || file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                    ? "未命名文件" : file.getOriginalFilename();
            Map<String, Object> r = new HashMap<>();
            r.put("fileName", fileName);
            try {
                String text = documentParser.parse(file);
                KbFile saved = kbService.registerFile(id, fileName, file.getSize(), text, chunkStrategy, overlap);
                r.put("status", "ok");
                r.put("fileId", saved.getId());
                r.put("added", saved.getChunkCount());
                r.put("chunkStrategy", saved.getChunkStrategy());
                r.put("chunkOverlap", saved.getChunkOverlap());
                // 刻意不回传「向量副本是否已同步」：副本写入已延后到事务提交之后（见 ChromaSyncSupport），
                // 本方法返回时结果尚未产生，回传恒真/恒假的标志只会误导；副本实况以 /api/kb/chroma/status 为准
            } catch (AiBusinessException e) {
                r.put("status", "error");
                r.put("message", e.getMessage());
                logError("知识库文件导入失败（业务拒绝）", id, fileName, e.getMessage());
            } catch (Exception e) {
                r.put("status", "error");
                r.put("message", "处理失败：" + e.getMessage());
                logError("知识库文件导入失败（未知异常）", id, fileName, e.getMessage());
            }
            results.add(r);
        }
        return Map.of("results", results);
    }

    /** 某知识库的文件列表（文件是知识的上传与管理单元，删文件会连带删其知识块）。 */
    @GetMapping("/{id}/files")
    public List<KbFile> files(@PathVariable Long id) {
        return kbService.listFiles(id);
    }

    /**
     * 对库内已入库文件重新分片（切换策略 / 重叠，不要求重传）：读保存的原文 → 按 body 重切 → 删旧块写新块。
     *
     * @param body JSON：{"strategy":"paragraph","overlap":60}；strategy 缺省/未知回退 recursive，
     *             overlap 缺省沿用文件当前值（0 = 不重叠）
     * @return 重新分片后的块数与生效的策略/重叠
     */
    @PostMapping("/{id}/files/{fileId}/rechunk")
    public Map<String, Object> rechunk(@PathVariable Long id,
                                       @PathVariable Long fileId,
                                       @RequestBody(required = false) java.util.Map<String, String> body) {
        String strategy = body == null ? null : body.get("strategy");
        Integer overlap = null;
        if (body != null && body.get("overlap") != null && !body.get("overlap").isBlank()) {
            try {
                overlap = Integer.valueOf(body.get("overlap"));
            } catch (NumberFormatException ignore) {
                // 非法数值按缺省处理（沿用文件当前重叠）
            }
        }
        // 直接返回服务层的「生效值」：overlap 缺省时由文件当前配置补齐。
        // 切勿回显入参 overlap —— 它为 null 时 Map.of 会抛 NPE（缺省调用必 500）。
        KbRechunkResult r = kbService.rechunkFile(id, fileId, strategy, overlap);
        return Map.of("chunkCount", r.chunkCount(),
                "chunkStrategy", r.strategyKey(),
                "chunkOverlap", r.overlap());
    }

    /** 删除库中的文件（连带删除该文件解析出的全部知识块并回减 doc_count）。 */
    @DeleteMapping("/{id}/files/{fileId}")
    public Map<String, Object> deleteFile(@PathVariable Long id, @PathVariable Long fileId) {
        int removed = kbService.deleteFile(id, fileId);
        return Map.of("removed", removed);
    }

    /**
     * Chroma 幂等回填：把 MySQL 存量知识块分批 upsert 到 Chroma（按 chunk id 覆盖，重复调用安全；
     * 也用于故障恢复后补齐副本）。body 形如 {"kbId": 1}；kbId 缺省/null = 全部库。
     *
     * @return {synced: 成功回填的知识块数}
     */
    @PostMapping("/chroma/sync")
    public Map<String, Object> chromaSync(@RequestBody(required = false) Map<String, Object> body) {
        Long kbId = null;
        Object raw = body == null ? null : body.get("kbId");
        if (raw != null) {
            try {
                kbId = Long.valueOf(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                throw new AiBusinessException(org.luo.exception.AiErrorCode.BAD_REQUEST,
                        "kbId 必须是数字：" + raw);
            }
        }
        int synced = kbService.syncToChroma(kbId);
        return Map.of("synced", synced);
    }

    /**
     * Chroma 状态自检：确认向量副本是否可用、collection 里有多少条向量。
     * connected=true 且 documentCount 与 MySQL 知识块数接近即正常。
     *
     * @return {connected, documentCount(-1=未知), baseUrl, tenant, database, collection, lastError}
     */
    @GetMapping("/chroma/status")
    public Map<String, Object> chromaStatus() {
        return kbService.chromaStatus();
    }

    private void logError(String prefix, Long kbId, String fileName, String msg) {
        org.slf4j.LoggerFactory.getLogger(KnowledgeBaseController.class)
                .warn("{}：kbId={}，文件={}，原因={}", prefix, kbId, fileName, msg);
    }

    @DeleteMapping("/{id}/chunks/{chunkId}")
    public void deleteChunk(@PathVariable Long id, @PathVariable Long chunkId) {
        kbService.deleteChunk(id, chunkId);
    }
}
