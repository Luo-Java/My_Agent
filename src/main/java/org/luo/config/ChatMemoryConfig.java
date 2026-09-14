package org.luo.config;

import org.luo.memory.DbChatMemory;
import org.luo.properties.MemoryProperties;
import org.luo.service.ConversationService;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会话记忆（ChatMemory）相关 Bean 配置。
 *
 * <ul>
 *   <li>{@link ChatMemory}：基于 MySQL（chat_message 表）的自定义实现，替代 Spring AI 默认的内存实现</li>
 *   <li>{@link MessageChatMemoryAdvisor}：自动把历史注入 prompt，并在对话前后把新消息写回记忆</li>
 * </ul>
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    @ConditionalOnMissingBean(ChatMemory.class)
    public ChatMemory chatMemory(ConversationService conversationService, MemoryProperties memoryProperties) {
        return new DbChatMemory(conversationService, memoryProperties);
    }

    @Bean
    @ConditionalOnMissingBean(MessageChatMemoryAdvisor.class)
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }
}
