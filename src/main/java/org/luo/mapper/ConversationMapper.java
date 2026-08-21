package org.luo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.luo.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
