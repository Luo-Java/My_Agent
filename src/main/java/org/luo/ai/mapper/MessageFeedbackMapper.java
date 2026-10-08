package org.luo.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.luo.ai.entity.MessageFeedback;

/**
 * 消息反馈 Mapper。
 * <p>
 * 只有按会话 / 按消息的等值查询，{@link BaseMapper} 的 Wrapper 查询足够，无需显式 SQL（故无 XML）。
 */
@Mapper
public interface MessageFeedbackMapper extends BaseMapper<MessageFeedback> {
}
