package org.luo.service;

import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.luo.config.VisionProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 多模态视觉识别服务：把图片转成文本描述（caption），供 ChatRequest.attachments 注入对话。
 * <p>
 * 直接 HTTP 调 dashscope OpenAI 兼容端点（{@code /chat/completions}），不复用主对话 ChatClient：
 * <ul>
 *   <li>主对话绑定 qwen3.7-plus（文本），强制改 model 会引入 vision 兼容风险；</li>
 *   <li>vision 调用是同步、单图、独立，不需要 ChatClient 的 advisor/记忆机制；</li>
 *   <li>复用 {@code spring.ai.openai.api-key / base-url}，不重复配置 key 与端点。</li>
 * </ul>
 * 单图独立调用，一张失败不影响其它图；最终 caption 为空/失败时返回占位文本
 * （{@code "[图片识别失败：...]"}），由 ChatService 拼到 message 后让 LLM 自然处理。
 *
 * <p><b>不进入 RAG / 不进入会话记忆</b>：caption 仅当轮注入上下文（注入点见 ChatService），
 * 不写入 chat_message 表，不入库 kb_chunk，刷新或重开会话后不会出现——保持对话存储的轻量。
 */
@Slf4j
@Service
public class VisionService {

    private final VisionProperties props;
    private final String apiKey;
    private final String baseUrl;

    public VisionService(VisionProperties props,
                         @Value("${spring.ai.openai.api-key:}") String apiKey,
                         @Value("${spring.ai.openai.base-url:}") String baseUrl) {
        this.props = props;
        this.apiKey = apiKey;
        // baseUrl 形如 https://dashscope.aliyuncs.com/compatible-mode/v1，保留前缀直连 /chat/completions
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://dashscope.aliyuncs.com/compatible-mode/v1" : baseUrl;
        log.info("VisionService 初始化：model={}, baseUrl={}", props.model(), this.baseUrl);
    }

    /**
     * 识别多张图片为文本描述，每张图对应一个 caption（按入参顺序）。
     * 单图失败不抛错，把 caption 置为占位文本并 warn；保证调用方始终拿到与入参等长的结果列表。
     *
     * @param files 上传的文件列表（已过滤非图片）
     * @return 与 files 等长的 caption 列表
     */
    public List<String> describeAll(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) return List.of();
        List<String> captions = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            MultipartFile f = files.get(i);
            captions.add(describeOne(f, i));
        }
        return captions;
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
        String dataUrl;
        try {
            byte[] bytes = file.getBytes();
            String b64 = Base64.getEncoder().encodeToString(bytes);
            dataUrl = "data:" + mime + ";base64," + b64;
        } catch (IOException e) {
            log.warn("读取图片失败：index={}, 原因={}", index, e.getMessage());
            return "[读取图片失败]";
        }

        // 构造 OpenAI 兼容 messages: [text 指令, image_url(dataUrl)]
        JSONArray content = JSONUtil.createArray();
        JSONObject textPart = JSONUtil.createObj();
        textPart.putOpt("type", "text");
        textPart.putOpt("text", props.prompt());
        content.add(textPart);
        JSONObject imgPart = JSONUtil.createObj();
        imgPart.putOpt("type", "image_url");
        JSONObject imageUrl = JSONUtil.createObj();
        imageUrl.putOpt("url", dataUrl);
        imgPart.putOpt("image_url", imageUrl);
        content.add(imgPart);

        JSONObject userMsg = JSONUtil.createObj();
        userMsg.putOpt("role", "user");
        userMsg.putOpt("content", content);

        JSONObject body = JSONUtil.createObj();
        body.putOpt("model", props.model());
        body.putOpt("messages", JSONUtil.createArray().put(userMsg));
        body.putOpt("temperature", 0.2);    // 视觉描述偏向确定性输出

        String url = baseUrl + "/chat/completions";
        try (HttpResponse resp = HttpRequest.post(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(props.timeoutSeconds() * 1000)
                .body(body.toString())
                .execute()) {
            if (!resp.isOk()) {
                log.warn("视觉识别 HTTP 失败：index={}, status={}, body={}", index, resp.getStatus(),
                        truncate(resp.body(), 200));
                return "[图片识别失败：HTTP " + resp.getStatus() + "]";
            }
            String respBody = resp.body();
            JSONObject json = JSONUtil.parseObj(respBody);
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                log.warn("视觉识别返回无 choices：index={}, body={}", index, truncate(respBody, 200));
                return "[图片识别失败：无返回内容]";
            }
            JSONObject first = choices.getJSONObject(0);
            JSONObject msg = first.getJSONObject("message");
            String caption = msg == null ? null : msg.getStr("content");
            if (caption == null || caption.isBlank()) {
                return "[图片识别失败：模型未返回文本]";
            }
            log.debug("视觉识别成功：index={}, caption.length={}", index, caption.length());
            return caption.trim();
        } catch (Exception e) {
            log.warn("视觉识别异常：index={}, 原因={}", index, ExceptionUtil.getRootCauseMessage(e));
            return "[图片识别失败：" + ExceptionUtil.getRootCauseMessage(e) + "]";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}