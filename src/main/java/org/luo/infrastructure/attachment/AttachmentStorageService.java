package org.luo.infrastructure.attachment;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.luo.exception.AiBusinessException;
import org.luo.exception.AiErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

/**
 * 附件原文件落盘：把用户在对话中上传的原始文件持久化到本地目录，供历史记录回看缩略图 / 下载。
 * <p>
 * <b>与记忆无关</b>：落盘的只是磁盘字节，既不进 LLM 上下文、也不影响 token（DbChatMemory 只读写
 * {@code chat_message.content}）；会话记忆里仅保留 {@code attachments_json} 的展示元数据。文件经
 * {@code /files/**} 静态映射对外提供只读访问（见 AttachmentWebConfig，刻意不带 {@code /api} 前缀，
 * 以免 {@code <img>} 无法附带 X-Api-Key 而被拦截）。
 * <p>
 * 存储名用随机 UUID + 原扩展名，不使用原始文件名，避免同名覆盖与路径穿越；{@link #resolve} 对入参
 * 再做一次目录穿越防护。
 */
@Slf4j
@Service
public class AttachmentStorageService {

    /** 静态访问前缀（与 AttachmentWebConfig 的 /files/** 映射一致）。 */
    public static final String WEB_PREFIX = "/files/";

    /** 原文件落盘目录（可配置）：app.attachment.dir，默认 ./data/attachments。 */
    private final Path baseDir;

    public AttachmentStorageService(@Value("${app.attachment.dir:./data/attachments}") String dir) {
        this.baseDir = Paths.get(dir).toAbsolutePath().normalize();
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(baseDir);
            log.info("附件存储目录：{}", baseDir);
        } catch (IOException e) {
            log.error("创建附件存储目录失败：{}", baseDir, e);
        }
    }

    /** 落盘结果：存储名（磁盘文件名，同时作为访问路径段）+ 字节数。 */
    public record Stored(String storedName, long size) {
    }

    /**
     * 把上传文件写入落盘目录，返回存储名与大小。存储名 = UUID + 原扩展名（小写）。
     *
     * @param file 上传文件
     * @return 存储名 + 字节数
     * @throws AiBusinessException 文件为空或写入失败
     */
    public Stored store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "空文件无法保存");
        }
        String storedName = UUID.randomUUID().toString().replace("-", "") + ext(file.getOriginalFilename());
        Path target = baseDir.resolve(storedName);
        try {
            Files.createDirectories(baseDir);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new Stored(storedName, file.getSize());
        } catch (IOException e) {
            log.error("附件落盘失败：{}", file.getOriginalFilename(), e);
            throw new AiBusinessException(AiErrorCode.INTERNAL_ERROR, "附件保存失败：" + e.getMessage());
        }
    }

    /** 由存储名拼出对外访问 URL（如 /files/ab12.pdf）。 */
    public static String url(String storedName) {
        return (storedName == null || storedName.isBlank()) ? null : WEB_PREFIX + storedName;
    }

    /** 解析存储名到磁盘路径；对入参做目录穿越防护（含路径分隔符或 .. 时返回 null）。 */
    public Path resolve(String storedName) {
        if (storedName == null || storedName.isBlank()) return null;
        if (storedName.contains("/") || storedName.contains("\\") || storedName.contains("..")) return null;
        return baseDir.resolve(storedName).normalize();
    }

    /** 取原文件名的小写扩展名（含点）；无扩展名或异常超长时返回空串。 */
    private static String ext(String filename) {
        if (filename == null) return "";
        int idx = filename.lastIndexOf('.');
        if (idx < 0 || idx == filename.length() - 1) return "";
        String e = filename.substring(idx).toLowerCase(Locale.ROOT);
        return e.length() > 12 ? "" : e;
    }
}
