package org.luo.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.luo.ai.entity.ConversationFact;

/**
 * 会话长期事实条目 Mapper。
 * <p>
 * 只有按会话的等值查询与按主键的增删改，{@link BaseMapper} 的 Wrapper 足够，无需显式 SQL（故无 XML）。
 */
@Mapper
public interface ConversationFactMapper extends BaseMapper<ConversationFact> {
}
