package org.luo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.luo.infrastructure.vision.VisionService;

/**
 * 多模态视觉识别配置（{@code agent.vision.*}）。
 * <p>
 * 识别复用自动装配的 {@code ChatModel}（即 {@code spring.ai.openai.*} 的 api-key / base-url），
 * 仅通过 per-request {@code OpenAiChatOptions} 切换模型：
 * <ul>
 *   <li>主对话模型：{@code spring.ai.openai.chat.model} = qwen3.7-plus（文本）</li>
 *   <li>视觉识别模型：{@code agent.vision.model} = qwen-vl-plus（视觉，单独调用）</li>
 * </ul>
 * 这样既能吃图理解，又不会污染主对话模型的稳定性。
 *
 * @param model 视觉模型名（dashscope OpenAI 兼容接口支持：qwen-vl-plus / qwen-vl-ocr 等）
 * @param prompt 注入视觉模型的固定指令（中文：客观描述 / 保留原题与选项 / 表格结构化输出）
 * @param maxImageBytes 单张图片字节上限；超出返回「图片过大」占位文本，避免大图击穿超时
 * @param timeoutSeconds 单次识别的编排层超时（秒）——Spring AI 无 per-request 超时，
 *                       由 {@code VisionService} 用 {@code future.get(timeout)} 控制，超时降级为占位 caption
 */
@ConfigurationProperties(prefix = "agent.vision")
public record VisionProperties(String model, String prompt, long maxImageBytes, int timeoutSeconds) {

    public VisionProperties {
        if (model == null || model.isBlank()) model = "qwen3.5-ocr";
        if (prompt == null || prompt.isBlank()) {
            prompt = "请用中文客观描述这张图片的内容，重点提取关键信息（文字、数字、图表数据、问题题目等）。"
                    + "如果图片是表格请以结构化文本输出，如果是题目请保留原题和选项，不要省略关键信息。";
        }
        if (maxImageBytes <= 0) maxImageBytes = 10L * 1024 * 1024;     // 默认 10MB
        if (timeoutSeconds <= 0) timeoutSeconds = 30;
    }
}