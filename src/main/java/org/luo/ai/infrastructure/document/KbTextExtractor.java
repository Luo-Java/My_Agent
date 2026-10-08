package org.luo.ai.infrastructure.document;

import lombok.extern.slf4j.Slf4j;
import org.luo.ai.infrastructure.vision.VisionService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库入库的「文件 → 文本」统一入口：图片走多模态识别（{@link VisionService}），其余走本地文档解析
 * （{@link DocumentParserService}）。
 * <p>
 * <b>为什么单独一层，而不是把图片分支塞进 DocumentParserService</b>：两者性质不同 —— 文档解析是纯本地、
 * 同步、零外部依赖；视觉识别要调多模态模型、有超时与计费。混在一起会让「解析」这个动作凭空带上网络行为
 * 与成本，也让 DocumentParserService 的既有用途（对话附件）莫名多出模型依赖。
 * <p>
 * <b>与 {@code AttachmentService} 的区别在「服务对象」</b>：那边服务「本轮对话上下文」（截断到 3 万字符、
 * 失败降级为占位说明、顺带把原文件落盘），这边服务「长期知识入库」——<b>识别失败直接抛错</b>：把
 * 「[图片识别失败：超时（30s）]」这样的占位文本切成知识块写进库，等于往检索结果里灌噪声，比报错糟得多。
 * 上传接口按文件逐条回报结果，一条失败不影响同批其它文件。
 */
@Slf4j
@Service
public class KbTextExtractor {

    private final DocumentParserService documentParser;
    private final VisionService visionService;

    public KbTextExtractor(DocumentParserService documentParser, VisionService visionService) {
        this.documentParser = documentParser;
        this.visionService = visionService;
    }

    /**
     * 抽取入库文本。图片 → 视觉识别为描述文本；其余 → 文档解析。两者失败都抛
     * {@code AiBusinessException}（由上传接口按文件捕获并回报，不吞不降级）。
     *
     * @param file 上传文件（调用方保证非空）
     * @return 可切块入库的纯文本
     */
    public String extract(MultipartFile file) {
        // 判据与对话附件同源（VisionService#isSupportedImage），避免「同一张图在两处结论不同」
        if (VisionService.isSupportedImage(file)) {
            String caption = visionService.describeStrict(file);
            log.info("知识库图片识别入库：file={}，描述 {} 字", file.getOriginalFilename(), caption.length());
            return caption;
        }
        return documentParser.parse(file);
    }
}
