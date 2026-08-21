package org.luo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.luo.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
