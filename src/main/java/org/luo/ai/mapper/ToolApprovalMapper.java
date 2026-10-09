package org.luo.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.luo.ai.entity.ToolApproval;

import java.util.List;

/**
 * 工具审批闸门 Mapper。
 * <p>
 * 读侧两条查询都<b>必须带归属过滤</b>，而本表没有 {@code user_id} 列 —— 归属靠 {@code JOIN conversation}
 * 判定，与 {@code AgentTraceMapper.selectOwned} 同一口径与同一理由（见该 XML 里的说明）。
 * 闸门自身的查 / 落 / 改走 {@link BaseMapper} 的 Wrapper：那些操作在上游已经过会话归属校验，
 * 不需要（也不应该）在这一层再判一次。
 */
@Mapper
public interface ToolApprovalMapper extends BaseMapper<ToolApproval> {

    /**
     * 某用户在某会话下的审批记录（时间倒序，最多 {@code limit} 条）；{@code conversationId} 为空则回该用户全部。
     */
    List<ToolApproval> selectOwned(@Param("userId") Long userId,
                                  @Param("conversationId") String conversationId,
                                  @Param("limit") int limit);

    /** 按 id 取本人可管的记录；不存在与「非本人」都返回 null（调用方统一按 404 处理，防 ID 探测）。 */
    ToolApproval selectOwnedById(@Param("id") Long id, @Param("userId") Long userId);
}
