package org.luo.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.luo.ai.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /**
     * 跨会话关键词召回：在 {@code userId} 名下、<b>除当前会话以外</b>的历史消息里做字面匹配。
     * <p>
     * 归属判定靠 {@code JOIN conversation}（{@code chat_message} 没有 {@code user_id} 列）；
     * 排序按「命中关键词个数」降序、同分按 id 降序。SQL 见 {@code resources/mapper/ai/ChatMessageMapper.xml}。
     * <p>
     * <b>关键词由调用方清洗</b>（去掉 {@code %} / {@code _} 等 LIKE 通配符）后传入，绑定走 {@code #{}}，
     * 不做字符串拼接 —— 关键词来源是 LLM 输出，不能当作可信输入。
     *
     * @param userId                当前登录用户（归属过滤，必填）
     * @param excludeConversationId 要排除的会话（当前会话；传 null 表示不排除）
     * @param keywords              已清洗的关键词（非空列表，否则 SQL 会生成空的 {@code OR} 链）
     * @param limit                 候选条数上限
     * @return 命中的历史消息（只回 {@code chat_message} 自身列，命中数由调用方在内存里重算）
     */
    List<ChatMessage> searchOwned(@Param("userId") Long userId,
                                  @Param("excludeConversationId") String excludeConversationId,
                                  @Param("keywords") List<String> keywords,
                                  @Param("limit") int limit);

    /**
     * 取某条消息<b>之前</b>、同一会话里最近的一条用户输入（消息反馈转回归用例时用它还原「用户当时说了什么」）。
     * <p>
     * 三个口径要点：
     * <ul>
     *   <li>按 {@code id} 而不是 {@code created_at} 排序：同轮 user/assistant 的时间戳完全相同（秒级），
     *       {@code created_at} 定不了先后；</li>
     *   <li>带分支过滤（{@code turn_group_id IS NULL OR turn_active = 1}）：被翻走的历史版本不算「用户说过」；</li>
     *   <li><b>刻意不过滤 {@code memory_excluded}</b> —— 这是<b>展示侧</b>口径。那条消息是否参与记忆，
     *       与「用户当时到底问了什么」无关，这正是反馈要还原的事实。</li>
     * </ul>
     * SQL 见 {@code resources/mapper/ai/ChatMessageMapper.xml}。
     *
     * @return 用户输入文本；找不到（如该消息是本会话第一条）返回 null
     */
    String selectPrevUserInput(@Param("conversationId") String conversationId,
                               @Param("messageId") Long messageId);
}
