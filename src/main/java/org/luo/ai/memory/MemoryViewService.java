package org.luo.ai.memory;

import lombok.extern.slf4j.Slf4j;
import org.luo.ai.dto.InjectedMessage;
import org.luo.ai.entity.ChatMessage;
import org.luo.ai.properties.MemoryProperties;
import org.luo.ai.service.ConversationService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 记忆视图服务：把「这一轮会往 prompt 里注入哪些历史」摊开给页面看（只读，不参与任何写入）。
 * <p>
 * <b>为什么不放在 {@code ConversationService}</b>：窗口口径（{@code SQL_FETCH_LIMIT} 与
 * {@code computeWindowStart}）属于 memory 包，而 {@code ConversationService} 已经被 memory 包依赖
 * （{@code DbChatMemory} 的构造器持有它），让它反向依赖会成环。本类只做「取数 + 套同一份窗口口径」，
 * 依赖方向保持单向：{@code memory → service}。
 * <p>
 * <b>窗口算法必须与真实注入同源</b>：这里用的是 {@link DbChatMemory#snapshot} —— 与
 * {@link DbChatMemory#get} 完全同一个函数。各写一份的话，面板迟早会显示出一份「看着合理、但与模型
 * 实际收到的不同」的清单，那比黑盒更糟：黑盒最多让人不知道，错误的透明化会让人自信地判断错。
 */
@Slf4j
@Service
public class MemoryViewService {

    private final ConversationService conversationService;
    private final MemoryProperties props;

    public MemoryViewService(ConversationService conversationService, MemoryProperties props) {
        this.conversationService = conversationService;
        this.props = props;
    }

    /**
     * 当前记忆窗口构成（时间正序）。取数走记忆口径 —— 已排除「不参与记忆」的消息与未生效的分支版本，
     * 因此这里列出的就是模型这一轮真正能看到的那几条历史（不含本轮提问，本轮提问由 advisor 在读取之后追加）。
     *
     * @return 空表表示该会话没有可注入的历史（首轮 / 全被排除 / 读取失败）
     */
    public List<InjectedMessage> window(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        try {
            List<ChatMessage> recent = conversationService.getRecentHistory(conversationId,
                    DbChatMemory.SQL_FETCH_LIMIT);
            return toItems(DbChatMemory.snapshot(recent, props).injected());
        } catch (Exception e) {
            // 只读展示，取不到就当空 —— 不因旁路视图把主流程带崩
            log.warn("读取记忆窗口构成失败：会话={}", conversationId, e);
            return List.of();
        }
    }

    /** 消息列表 → 预览项列表：长度取「实际进上下文」的截断结果，与模型收到的长度一致。 */
    private List<InjectedMessage> toItems(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .map(m -> InjectedMessage.of(m.getRole(), DbChatMemory.truncateForContext(m.getContent(), props)))
                .toList();
    }
}
