package org.luo.service;

import lombok.extern.slf4j.Slf4j;
import org.luo.entity.Agent;
import org.luo.exception.AiBusinessException;
import org.luo.exception.AiErrorCode;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 提示词服务：解析本次对话应使用的系统提示词，以及根据智能体名称/描述用 LLM 生成提示词。
 * 使用裸 {@link ChatModel} 调用（不走 advisor，不写入任何会话记忆、不影响对话历史）。
 */
@Slf4j
@Service
public class PromptService {

    /** 默认 System Prompt：未绑定智能体时使用的通用助手指令（约束 AI 输出为规范 Markdown）。 */
    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是一个专业、友好的 AI 助手。请严格遵守以下输出格式要求：

            1. 使用 Markdown 格式组织回复内容：
               - 用 ## 或 ### 分段标题（不要用 # 一级标题）
               - 代码片段用 ```语言 包裹代码块，行内变量/函数用 `反引号`
               - 列表用 - 或数字编号
               - 表格用标准 Markdown 表格语法

            2. 内容结构要求：
               - 先给出结论或直接回答，再展开解释
               - 分点说明时每条不超过两行
               - 代码示例必须附带简短注释说明关键步骤
               - 长回复请用分段和空行保持可读性

            3. 禁止事项：
               - 不要输出原始 Markdown 源码符号作为装饰（如 ### 冒泡排序 **算法**）
               - 不要在正文里混入 HTML 标签
               - 不要使用过多的 emoji 或特殊符号
               - 回复开头不要加「你好」「您好」等寒暄，直接回答问题
            """;

    private final ChatModel chatModel;

    public PromptService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 解析本次请求应使用的系统提示词：存在可用智能体（显式绑定或路由命中）且带 systemPrompt 时用之，否则用默认。
     */
    public String resolveSystemPrompt(Agent agent) {
        if (agent != null) {
            String p = agent.getSystemPrompt();
            if (p != null && !p.isBlank()) {
                return p;
            }
        }
        return DEFAULT_SYSTEM_PROMPT;
    }

    /**
     * 根据智能体名称与描述，用 LLM 生成一段系统提示词（人设）。
     *
     * @param name        智能体名称（必填）
     * @param description 智能体描述（可选）
     * @return 生成的提示词纯文本
     * @throws AiBusinessException 名称为空或 LLM 调用失败时抛出
     */
    public String generateAgentPrompt(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "智能体名称不能为空");
        }
        log.info("生成智能体提示词：名称={}", name);
        try {
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage("""
                            你是一位专业的提示词（Prompt）工程师。根据用户提供的智能体名称和描述，创作一段高质量的中文系统提示词（人设设定）。
                            要求：
                            1. 直接输出提示词正文本身，不要输出任何解释、前言、后语，不要用代码块包裹。
                            2. 用第二人称「你」开头，明确角色定位、职责范围与目标用户。
                            3. 包含对回答风格与输出格式的具体要求（涉及代码时用 Markdown 代码块等）。
                            4. 列出 2~4 条具体行为准则，例如回复结构、禁止事项、处理边界。
                            5. 若智能体执行任务需要特定输入（如翻译的目标语言），在行为准则中明确：缺少必要输入时必须先向用户追问确认，不可臆测。
                            6. 全文 150~400 字，语气专业、指令明确。
                            """),
                    new UserMessage("智能体名称：" + name
                            + "\n描述：" + (description == null || description.isBlank() ? "（无）" : description)))));
            var generation = response.getResult();
            var assistantMessage = generation != null ? generation.getOutput() : null;
            String reply = assistantMessage != null ? assistantMessage.getText() : null;
            if (reply == null || reply.isBlank()) {
                throw new AiBusinessException(AiErrorCode.INTERNAL_ERROR, "AI 未能生成提示词，请稍后重试");
            }
            String trimmed = reply.trim();
            log.info("智能体提示词生成完成：长度={}", trimmed.length());
            return trimmed;
        } catch (AiBusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("智能体提示词生成失败：LLM 调用异常", e);
            throw new AiBusinessException(AiErrorCode.INTERNAL_ERROR, "提示词生成失败，请检查 AI 服务配置后重试");
        }
    }
}
