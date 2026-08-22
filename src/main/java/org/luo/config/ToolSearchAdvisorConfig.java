package org.luo.config;

import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.tool.toolsearch.index.regex.RegexToolIndex;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 动态工具发现（ToolSearchToolCallingAdvisor）配置。
 * <p>
 * 机制：请求挂载全量工具后，本 Advisor 会把工具索引进 {@link RegexToolIndex}，
 * 但只向模型暴露一个内置的 {@code toolSearchTool}（渐进式工具暴露）。
 * 对话时由模型自己按语义搜索出要用的工具，再注入执行——无需按 Agent 手动配置工具。
 * <ul>
 *   <li>索引按会话（sessionId，默认取 ChatMemory.CONVERSATION_ID）缓存，工具集未变不重复索引；</li>
 *   <li>{@code maxResults} 限制单次搜索返回的工具引用数；</li>
 *   <li>会话索引默认 LRU(1000) 淘汰，超时会话自动清理。</li>
 * </ul>
 */
@Configuration
public class ToolSearchAdvisorConfig {

    @Bean
    public ToolSearchToolCallingAdvisor toolSearchToolCallingAdvisor() {
        return ToolSearchToolCallingAdvisor.builder()
                .toolIndex(new RegexToolIndex())
                .maxResults(5)
                .build();
    }
}
