package org.luo.ai.infrastructure.vision;

import cn.hutool.core.exceptions.ExceptionUtil;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.properties.VisionProperties;
import org.luo.ai.trace.LlmUsageService;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.luo.ai.controller.ChatController;

/**
 * 多模态视觉识别服务：把图片转成文本描述（caption），供 ChatRequest.attachments 注入对话。
 * <p>
 * 采用 <b>Spring AI 原生多模态</b>：注入裸 {@link ChatModel} 直接 {@code call(Prompt)}，图片由
 * {@code UserMessage.builder().media(Media)} 承载，per-request {@link OpenAiChatOptions} 覆盖为视觉模型
 * （{@code agent.vision.model}，默认 {@link VisionProperties#DEFAULT_MODEL}）。
 * <p>
 * <b>为什么注入裸 ChatModel</b>（与 AgentRouter / MemoryMergeService / ParamFillingService / PromptService
 * 同一模式）：绕开 advisor 与记忆机制（视觉识别是同步、单轮、无状态的独立调用，不应污染会话记忆）；
 * per-request 覆盖 model，不动 yaml 里主对话模型，避免文本模型换视觉模型的兼容风险。
 * <p>
 * <b>并发 + 逐图调用</b>：多图在 {@code visionExecutor} 上并发执行（每图一次独立调用），结果按入参顺序回收，
 * 与 files <b>1:1 对齐</b>；单图失败/超时只影响该图（占位 caption），不抛错给调用方。
 * <p>
 * <b>为什么不是「一次请求传多图」</b>（设计决策，勿顺手改）：一次请求只返回一段合并文本，会丢失
 * 「哪段描述来自哪张图」的归属——而下游 {@link ChatController} 按
 * {@code 图片N（文件名）：caption} 逐条带标注抽取（如「工资条」与「聊天记录」需区分各自内容）。
 * 逐图调用保住了归属标注与单图失败隔离；延迟由并发压平（N×t → ≈t），每图同样只传一次、仅短指令重复 N 遍。
 * 仅当未来需要<b>跨图联合对比</b>时才应改为合并调用。
 * <p>
 * <b>两条消费路径，失败语义刻意不同</b>（见 {@link #describeAll} 与 {@link #describeStrict}）：
 * <ul>
 *   <li><b>对话附件</b>（{@link #describeAll}）：caption 仅当轮注入上下文，不写 chat_message、不入 kb_chunk。
 *       单图失败降级为占位文本 —— 一张图没认出来不该让整轮对话失败。</li>
 *   <li><b>知识库入库</b>（{@link #describeStrict}）：caption 会被切块写进 kb_chunk、长期参与检索。
 *       失败直接抛错 —— 把占位文本当知识存进去，等于往检索结果里灌噪声。</li>
 * </ul>
 */
@Slf4j
@Service
public class VisionService {

    private final VisionProperties props;
    private final ChatModel chatModel;
    private final Executor visionExecutor;
    /** 裸调用成本采集（全量成本口径，旁路异步，失败不影响识别）。 */
    private final LlmUsageService llmUsageService;

    public VisionService(VisionProperties props,
                         ChatModel chatModel,
                         @Qualifier("visionExecutor") Executor visionExecutor,
                         LlmUsageService llmUsageService) {
        this.props = props;
        this.chatModel = chatModel;
        this.visionExecutor = visionExecutor;
        this.llmUsageService = llmUsageService;
        log.info("VisionService 初始化：model={}（Spring AI 原生多模态，裸 ChatModel 直调，多图并发）", props.model());
    }

    /**
     * 识别多张图片为文本描述（严格按入参顺序）。多图并发执行；单图失败不抛错，caption 置为占位文本并 warn，
     * 保证调用方始终拿到与入参等长的结果列表（前端按 {@code captions[i] → attachments[i]} 对齐消费）。
     *
     * @param files 上传的文件列表（已过滤非图片）
     * @return 与 files 等长的 caption 列表
     */
    public List<String> describeAll(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) return List.of();
        List<CompletableFuture<String>> futures = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            final MultipartFile f = files.get(i);
            final int index = i;
            try {
                futures.add(CompletableFuture.supplyAsync(() -> describeOne(f, index), visionExecutor));
            } catch (Exception e) {
                // 线程池拒绝等提交期异常：降级为占位 caption，保持 1:1 契约
                log.warn("视觉识别任务提交失败：index={}, 原因={}", index, ExceptionUtil.getRootCauseMessage(e));
                futures.add(CompletableFuture.completedFuture("[图片识别失败：任务繁忙]"));
            }
        }
        List<String> captions = new ArrayList<>(files.size());
        for (CompletableFuture<String> future : futures) {
            captions.add(await(future));
        }
        return captions;
    }

    /**
     * 回收单个识别结果：超时/异常一律降级为占位 caption。超时在编排层控制
     * （{@code agent.vision.timeoutSeconds}），不等同于底层 HTTP 客户端超时；尽早返回占位文本，
     * 避免整轮对话被单张图拖住。
     */
    private String await(CompletableFuture<String> future) {
        try {
            return future.get(props.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("视觉识别超时：limit={}s", props.timeoutSeconds());
            return "[图片识别失败：超时（" + props.timeoutSeconds() + "s）]";
        } catch (Exception e) {
            return "[图片识别失败：" + ExceptionUtil.getRootCauseMessage(e) + "]";
        }
    }

    /** 单图识别（对话侧私有实现）：<b>任何失败都降级为占位文本</b>，维持 {@link #describeAll} 的 1:1 契约。 */
    private String describeOne(MultipartFile file, int index) {
        if (file == null || file.isEmpty()) return "[空图片]";
        long size = file.getSize();
        if (size > props.maxImageBytes()) {
            log.warn("图片过大跳过识别：index={}, size={}B, limit={}B", index, size, props.maxImageBytes());
            return "[图片过大，已跳过识别]";
        }
        String mime = resolveImageMime(file);
        if (mime == null) {
            return "[非图片类型，已跳过]";
        }
        MimeType mimeType;
        byte[] bytes;
        try {
            mimeType = MimeTypeUtils.parseMimeType(mime);
            bytes = file.getBytes();
        } catch (Exception e) {
            log.warn("读取图片失败：index={}, 原因={}", index, e.getMessage());
            return "[读取图片失败]";
        }
        try {
            String caption = recognize(file, mimeType, bytes);
            log.debug("视觉识别成功：index={}, caption.length={}", index, caption.length());
            return caption;
        } catch (Exception e) {
            log.warn("视觉识别异常：index={}, 原因={}", index, ExceptionUtil.getRootCauseMessage(e));
            return "[图片识别失败：" + ExceptionUtil.getRootCauseMessage(e) + "]";
        }
    }

    /**
     * 真正的模型调用：文本指令 + 图片 {@link Media} 同放一条 {@code UserMessage}，per-request 覆盖为视觉模型。
     * 成功返回去空白的 caption，<b>失败抛异常</b>（把「降级还是上抛」留给调用方决定 —— 对话要降级，
     * 知识库入库要上抛）。
     */
    private String recognize(MultipartFile file, MimeType mimeType, byte[] bytes) {
        // 原生多模态：文本指令 + 图片作为 Media 一起放进同一条 UserMessage
        UserMessage userMessage = UserMessage.builder()
                .text(props.prompt())
                .media(new Media(mimeType, namedResource(bytes, file.getOriginalFilename())))
                .build();
        // per-request 覆盖模型：只影响本次调用，不动主对话模型
        Prompt prompt = new Prompt(List.of(userMessage), OpenAiChatOptions.builder()
                .model(props.model())
                .temperature(0.2)     // 视觉描述偏向确定性输出
                .build());

        ChatResponse response = chatModel.call(prompt);
        llmUsageService.recordAsync("VISION", null, null, response);
        String caption = (response == null || response.getResult() == null
                || response.getResult().getOutput() == null)
                ? null : response.getResult().getOutput().getText();
        if (caption == null || caption.isBlank()) {
            throw new IllegalStateException("模型未返回文本");
        }
        return caption.trim();
    }

    /**
     * 单图<b>严格</b>识别：任何失败抛 {@link AiBusinessException}，<b>不给占位文本</b>。知识库入库专用。
     * <p>
     * 与 {@link #describeAll} 的差别只有「失败怎么办」，而那正是关键：对话里一张图认不出来，退化成一句
     * 占位说明仍然可读可答，不能让整轮对话失败；知识库是要<b>长期存起来供检索</b>的 —— 把
     * 「[图片识别失败：超时（30s）]」当成知识块写进去，等于往检索结果里灌噪声，比直接报错糟得多。
     * 这与项目「降级不许静默」的口径一致：要么真的拿到内容，要么明确失败并由上传结果逐条回报。
     */
    public String describeStrict(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "图片文件为空");
        }
        if (file.getSize() > props.maxImageBytes()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "图片超过大小上限（" + (props.maxImageBytes() / 1024 / 1024) + "MB）");
        }
        String mime = resolveImageMime(file);
        if (mime == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "不支持的图片格式（支持 png/jpg/jpeg/gif/webp/bmp/tif/tiff/heic/avif）");
        }
        try {
            return recognize(file, MimeTypeUtils.parseMimeType(mime), file.getBytes());
        } catch (Exception e) {
            String cause = ExceptionUtil.getRootCauseMessage(e);
            log.warn("知识库图片识别失败：file={}, 原因={}", file.getOriginalFilename(), cause);
            throw new AiBusinessException(AiErrorCode.INTERNAL_ERROR, "图片识别失败：" + cause);
        }
    }

    /**
     * 是否受支持的图片：MIME 优先、扩展名兜底；<b>刻意排除 svg</b>（MIME 为 {@code image/svg+xml}）——
     * 它是「可内嵌脚本的 XML」，既不适合视觉模型识别，落盘也会被白名单转成 .bin（缩略图必然显示不出来），
     * 不挡会白跑一次视觉调用。
     * <p>
     * <b>本项目唯一的图片判据</b>：对话附件（{@code AttachmentService}）与知识库入库（{@code KbTextExtractor}）
     * 共用这一份 —— 各写一份必然漂移，而漂移的后果是「同一张 png 在附件里能识别、在知识库里被拒」。
     */
    public static boolean isSupportedImage(MultipartFile f) {
        return f != null && resolveImageMime(f) != null;
    }

    /**
     * 解析可用的图片 MIME：声明为 {@code image/*}（且非 svg）就照用；否则按扩展名兜底；
     * 两者都不认返回 {@code null}（= 不是受支持的图片）。{@link #isSupportedImage} 与识别侧同用这一份，
     * 保证「判定通过 ⇒ 一定拿得到一个 MIME」，不会出现「判进来了又报非图片类型」。
     */
    private static String resolveImageMime(MultipartFile f) {
        String mime = f.getContentType();
        String low = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (low.startsWith("image/")) {
            return low.contains("svg") ? null : mime;
        }
        return IMAGE_EXT_MIME.get(extOf(f));
    }

    /** 小写扩展名（不含点）；无扩展名返回空串。 */
    private static String extOf(MultipartFile f) {
        String name = f.getOriginalFilename();
        if (name == null) return "";
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx == name.length() - 1) return "";
        return name.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 扩展名 → MIME（识别时的兜底映射）。<b>刻意不含 svg</b>：理由同上，它与 {@code AttachmentStorageService}
     * 的后缀白名单口径一致（svg 落盘会被转成 .bin，缩略图显示不出来）。
     */
    private static final Map<String, String> IMAGE_EXT_MIME = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("tif", "image/tiff"),
            Map.entry("tiff", "image/tiff"),
            Map.entry("heic", "image/heic"),
            Map.entry("avif", "image/avif"));

    /** 带文件名的字节资源：Media 传给模型时保留原始文件名，便于日志与多图定位。 */
    private static ByteArrayResource namedResource(byte[] bytes, String filename) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return (filename == null || filename.isBlank()) ? "image" : filename;
            }
        };
    }
}
