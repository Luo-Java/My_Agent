package org.luo.controller;

import org.luo.dto.AddChunksRequest;
import org.luo.dto.CreateKbRequest;
import org.luo.dto.KbChunkPageResult;
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
import org.luo.service.KbService.FileIngestResult;

/**
 * 知识库管理接口（RAG 知识库：全局库 + 每个智能体一个专属库）。
 * <p>
 * GET    /api/kb                          - 全部知识库列表（全局库置顶，不存在时自动创建）
 * GET    /api/kb/global                   - 全局知识库（不存在时自动创建）
 * GET    /api/kb/chunk-strategies         - 全部分片策略元数据（strategies: key/label/desc；
 *                                          defaultOverlap/maxOverlap: 重叠字数默认值与上限，供前端渲染下拉）
 * POST   /api/kb                          - 创建知识库（body.agentId 非空 = 智能体专属库；null = 普通库）
 * PUT    /api/kb/{id}                     - 重命名 / 更新说明
 * DELETE /api/kb/{id}                     - 删除知识库（连同其全部知识块与文件）
 * GET    /api/kb/{id}/chunks              - 知识块分页（?offset=&limit=）
 * POST   /api/kb/{id}/chunks              - 批量添加知识（文本自动分块 + 向量化入库，保留兼容，前端已不展示）
 * DELETE /api/kb/{id}/chunks/{cid}        - 删除单个知识块
 * GET    /api/kb/{id}/files               - 该库的文件列表（每个知识库维护一份文件清单）
 * POST   /api/kb/{id}/upload              - 上传文件导入知识（multipart，支持 txt/md/csv/pdf/docx/xlsx；
 *                                           ?chunkStrategy= 覆盖分片策略（fixed/paragraph/recursive/markdown，
 *                                           缺省继承该库「知识库设置」里的默认策略，默认 recursive）；
 *                                           ?overlap= 覆盖相邻块重叠字数（0=不重叠，缺省继承库默认 60）；
 *                                           解析后按策略分块向量化并登记文件（保存原文与策略/重叠，
 *                                           支持后续不重传直接切换）；同名文件=替换重传；逐文件返回结果，
 *                                           单个失败不影响其它）
 * DELETE /api/kb/{id}/files/{fid}         - 删除文件（连同其全部知识块）
 * POST   /api/kb/{id}/files/{fid}/rechunk - 对文件重新分片（body.strategy=新策略、body.overlap=重叠字数，
 *                                           缺省沿用文件当前值；读保存的原文重切，不要求重新上传）
 * POST   /api/kb/chroma/sync              - Chroma 幂等回填（body.kbId 缺省=全部库；按 chunk id 覆盖，
 *                                           首次启用 / Chroma 故障恢复后把 MySQL 存量块同步到副本）
 * GET    /api/kb/chroma/status            - Chroma 状态自检（连接状态 / 命名空间 / collection 文档数，
 *                                           用于确认上传的文件是否真的进到了向量副本）
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

    /** 全部知识库列表（调用前确保全局库存在，全局库恒在列表首位）。 */
    @GetMapping
    public List<KnowledgeBase> list() {
        return kbService.listKbs();
    }

    /** 获取全局知识库（可在任意会话的「资料库」选择器中选用；不存在则自动创建）。 */
    @GetMapping("/global")
    public KnowledgeBase global() {
        return kbService.getOrCreateGlobal();
    }

    /** 全部分片策略元数据 + 重叠字数默认值/上限（前端渲染分片方式与重叠下拉；动态新增策略无需改前端）。 */
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
     * 上传文件导入知识（multipart/form-data，参数名 files，可多文件）。
     * 每个文件独立：解析 → 分块（策略/重叠缺省继承该库「知识库设置」的默认配置，也可用请求参数覆盖）→
     * 向量化入库 → 在文件列表登记（保存原文/策略/重叠）；
     * 单文件失败不影响其它，结果逐条返回：
     * [{ fileName, status: ok|error, added, fileId?, chunkStrategy?, chunkOverlap?, message? }]
     * 同一库内同名文件 = 替换重传（旧知识块清空后写入新内容）。
     *
     * @param chunkStrategy 分片策略 key，可选：fixed/paragraph/recursive/markdown；缺省继承库默认（知识库设置），未知 key 回退 recursive
     * @param overlap       相邻块重叠字数；缺省继承库默认（知识库设置），0 = 不重叠，上限 {@link ChunkingService#MAX_OVERLAP}
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
                KbService.FileIngestResult res =
                        kbService.registerFileWithStatus(id, fileName, file.getSize(), text, chunkStrategy, overlap);
                KbFile saved = res.file();
                r.put("status", "ok");
                r.put("fileId", saved.getId());
                r.put("added", saved.getChunkCount());
                r.put("chunkStrategy", saved.getChunkStrategy());
                r.put("chunkOverlap", saved.getChunkOverlap());
                // false = 只进了 MySQL（源），向量副本未同步：前端应提示可用「同步到 Chroma」回填
                r.put("chromaSynced", res.chromaSynced());
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

    /** 某知识库的文件列表（文件是知识的上传与管理单元，删除文件会连带删除其知识块）。 */
    @GetMapping("/{id}/files")
    public List<KbFile> files(@PathVariable Long id) {
        return kbService.listFiles(id);
    }

    /**
     * 对库内已入库文件重新分片（动态切换分片策略 / 重叠字数）：读文件入库时保存的原文 →
     * 按 body 的 strategy / overlap 重切 → 删旧块写新块。不要求重新上传文件；
     * 文件列表每行的「重新分片」按钮调此接口。
     *
     * @param body JSON：{"strategy":"paragraph","overlap":60}；strategy 缺省 / 未知回退 recursive；
     *             overlap 缺省沿用文件当前重叠字数（0 = 不重叠）
     * @return 重新分片后的知识块数与生效的策略/重叠
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
        int added = kbService.rechunkFile(id, fileId, strategy, overlap);
        return Map.of("chunkCount", added,
                "chunkStrategy", ChunkStrategy.fromKey(strategy).getKey(),
                "chunkOverlap", overlap == null ? null : overlap);
    }

    /** 删除库中的文件（连带删除该文件解析出的全部知识块并回减 doc_count）。 */
    @DeleteMapping("/{id}/files/{fileId}")
    public Map<String, Object> deleteFile(@PathVariable Long id, @PathVariable Long fileId) {
        int removed = kbService.deleteFile(id, fileId);
        return Map.of("removed", removed);
    }

    /**
     * Chroma 幂等回填：把 MySQL 存量知识块分批 upsert 到 Chroma（按 chunk id 覆盖，重复调用安全；
     * 也可用于 Chroma 故障恢复后把副本补齐）。body 形如 {"kbId": 1}；kbId 缺省 / null = 全部库。
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
     * Chroma 状态自检：确认向量副本是否可用、collection 里到底有多少条向量。
     * 排查「上传的文件有没有进 Chroma」时先看这里：connected=true 且 documentCount 与 MySQL 知识块数接近即正常。
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
