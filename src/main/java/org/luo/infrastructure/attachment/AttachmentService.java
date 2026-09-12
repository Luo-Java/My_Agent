package org.luo.infrastructure.attachment;

import lombok.extern.slf4j.Slf4j;
import org.luo.dto.ChatAttachment;
import org.luo.exception.AiBusinessException;
import org.luo.infrastructure.document.DocumentParserService;
import org.luo.infrastructure.vision.VisionService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话附件处理：把任意上传文件转成可注入对话的纯文本（{@link ChatAttachment#content()}）。
 * <p>
 * 按文件类型确定性分派（依据 MIME / 扩展名，不做「是否该调 AI」的猜测，避免写死意图判断）：
 * <ul>
 *   <li>{@code image/*} → 委托 {@link VisionService} 用视觉模型识别为 caption（保留逐图并发与归属标注）；</li>
 *   <li>文本 / 文档（txt/md/csv/json/xml/yml/properties/log/sql/pdf/docx/xlsx）→ 委托 {@link DocumentParserService}
 *       解析为纯文本；</li>
 *   <li>不支持的二进制（zip/exe/ppt/...）→ 降级为占位说明，仍记录文件名，让 LLM 知道有文件但读不到内容。</li>
 * </ul>
 * 单文件解析失败（超限、抽不出文本、格式不支持）不抛错给调用方：转为占位 {@code ChatAttachment}
 * （{@code type="file"}），保证返回列表与入参等长、前端按序对齐消费。
 * <p>
 * <b>注入长度保护</b>：文档解析文本在 {@code DocumentParserService} 已截断到 50 万字符（知识库分块用），
 * 这里再截断到 {@code MAX_INLINE_CHARS}，避免大文档直接灌入对话上下文撑爆输入上限 / 浪费 token。
 * <p>
 * <b>原文件落盘</b>：解析的同时把原文件交给 {@link AttachmentStorageService} 落盘，回填 storedName/size；
 * 落盘仅服务历史回看 / 下载，与 LLM 上下文无关（落盘失败不影响本轮解析结果）。
 */
@Slf4j
@Service
public class AttachmentService {

    private final VisionService visionService;
    private final DocumentParserService documentParser;
    private final AttachmentStorageService storage;

    /** 单附件注入对话的纯文本上限（字符）：超出截断并附提示，控制上下文体积。 */
    private static final int MAX_INLINE_CHARS = 30000;

    public AttachmentService(VisionService visionService, DocumentParserService documentParser,
                             AttachmentStorageService storage) {
        this.visionService = visionService;
        this.documentParser = documentParser;
        this.storage = storage;
    }

    /**
     * 处理一批上传文件，返回与入参顺序对齐的附件列表。
     * <p>
     * 每个文件先解析为文本（图片→视觉、文档→解析），同时把原文件落盘（供历史回看 / 下载）；
     * 落盘失败不影响本轮解析（storedName 留空），保证返回列表与入参等长、前端按序对齐消费。
     *
     * @param files 上传文件（multipart/form-data，字段名 {@code files}）
     * @return 与 files 等长的 {@link ChatAttachment} 列表（每项 type ∈ image/text/file）
     */
    public List<ChatAttachment> process(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) return List.of();

        // 先抽出图片子集（保持原始顺序），整批交给 VisionService 并发识别，结果按图片序对齐
        List<MultipartFile> images = new ArrayList<>();
        for (MultipartFile f : files) {
            if (isImage(f)) images.add(f);
        }
        List<String> imageCaptions = visionService.describeAll(images);

        List<ChatAttachment> result = new ArrayList<>(files.size());
        int imgIdx = 0;
        for (MultipartFile f : files) {
            String filename = f.getOriginalFilename();
            ChatAttachment base = isImage(f)
                    ? ChatAttachment.image(imageCaptions.get(imgIdx++), filename)
                    : processDocument(f, filename);
            result.add(attachStorage(f, base));
        }
        return result;
    }

    /** 原文件落盘并回填 storedName/size；落盘失败只记日志、不影响本轮解析结果。 */
    private ChatAttachment attachStorage(MultipartFile f, ChatAttachment base) {
        try {
            AttachmentStorageService.Stored s = storage.store(f);
            return base.withStorage(s.storedName(), s.size());
        } catch (Exception e) {
            log.warn("附件落盘失败（不影响本轮解析）：{}", f.getOriginalFilename(), e);
            return base;
        }
    }

    /** 是否图片：优先按 MIME，MIME 缺失时按扩展名兜底（防止浏览器未带 MIME 时图片被误判为不支持）。 */
    private static boolean isImage(MultipartFile f) {
        String mime = f.getContentType();
        if (mime != null && mime.startsWith("image/")) return true;
        String name = f.getOriginalFilename();
        if (name == null) return false;
        int idx = name.lastIndexOf('.');
        if (idx < 0) return false;
        String ext = name.substring(idx + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (ext) {
            case "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg" -> true;
            default -> false;
        };
    }

    /** 文档类附件：解析为文本后截断；任何失败降级为占位（type=file）。 */
    private ChatAttachment processDocument(MultipartFile file, String filename) {
        try {
            String text = documentParser.parse(file);
            if (text.length() > MAX_INLINE_CHARS) {
                text = text.substring(0, MAX_INLINE_CHARS)
                        + "\n…（内容过长，已截断至 " + MAX_INLINE_CHARS + " 字符，完整内容请分段上传或放入知识库）";
            }
            return ChatAttachment.text(text, filename);
        } catch (AiBusinessException e) {
            log.warn("附件解析被拒：{}（{}）", filename, e.getMessage());
            return ChatAttachment.file("[文件无法解析：" + e.getMessage() + "]", filename);
        } catch (Exception e) {
            log.warn("附件解析失败：{}", filename, e);
            return ChatAttachment.file("[文件解析失败：" + e.getMessage() + "]", filename);
        }
    }
}
