package org.luo.agent;

import lombok.extern.slf4j.Slf4j;
import org.luo.config.PromptProperties;
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

    /** 默认 System Prompt：未绑定智能体时使用的通用助手指令（约束 AI 输出为规范 Markdown）。从配置外置。 */
    private final String defaultSystemPrompt;

    /** 提示词生成指令（元提示词）。从配置外置。 */
    private final String promptGeneratorInstruction;

    private final ChatModel chatModel;

    public PromptService(ChatModel chatModel, PromptProperties promptProperties) {
        this.chatModel = chatModel;
        this.defaultSystemPrompt = promptProperties.defaultSystem();
        this.promptGeneratorInstruction = promptProperties.promptGenerator();
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
        return defaultSystemPrompt;
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
                    new SystemMessage(promptGeneratorInstruction),
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
