package org.luo.infrastructure.vision;

import cn.hutool.core.exceptions.ExceptionUtil;
import lombok.extern.slf4j.Slf4j;
import org.luo.properties.VisionProperties;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.luo.controller.ChatController;

/**
 * 多模态视觉识别服务：把图片转成文本描述（caption），供 ChatRequest.attachments 注入对话。
 * <p>
 * 采用 <b>Spring AI 原生多模态</b>：注入裸 {@link ChatModel} 直接 {@code call(Prompt)}，图片由
 * {@code UserMessage.builder().media(Media)} 承载，per-request {@link OpenAiChatOptions} 覆盖为视觉模型
 * （{@code agent.vision.model}，默认 qwen3.5-ocr）。
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
 * <b>不进入 RAG / 不进入会话记忆</b>：caption 仅当轮注入上下文，不写 chat_message、不入库 kb_chunk。
 */
@Slf4j
@Service
public class VisionService {

    private final VisionProperties props;
    private final ChatModel chatModel;
    private final Executor visionExecutor;

    public VisionService(VisionProperties props,
                         ChatModel chatModel,
                         @Qualifier("visionExecutor") Executor visionExecutor) {
        this.props = props;
        this.chatModel = chatModel;
        this.visionExecutor = visionExecutor;
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

    /** 单图识别（私有实现）。 */
    private String describeOne(MultipartFile file, int index) {
        if (file == null || file.isEmpty()) return "[空图片]";
        long size = file.getSize();
        if (size > props.maxImageBytes()) {
            log.warn("图片过大跳过识别：index={}, size={}B, limit={}B", index, size, props.maxImageBytes());
            return "[图片过大，已跳过识别]";
        }
        String mime = file.getContentType();
        if (mime == null || !mime.startsWith("image/")) {
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
            String caption = (response == null || response.getResult() == null
                    || response.getResult().getOutput() == null)
                    ? null : response.getResult().getOutput().getText();
            if (caption == null || caption.isBlank()) {
                log.warn("视觉识别返回空文本：index={}", index);
                return "[图片识别失败：模型未返回文本]";
            }
            log.debug("视觉识别成功：index={}, caption.length={}", index, caption.length());
            return caption.trim();
        } catch (Exception e) {
            log.warn("视觉识别异常：index={}, 原因={}", index, ExceptionUtil.getRootCauseMessage(e));
            return "[图片识别失败：" + ExceptionUtil.getRootCauseMessage(e) + "]";
        }
    }

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
