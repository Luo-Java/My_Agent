package org.luo.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.luo.ai.entity.Notification;

/**
 * 通知 Mapper。
 * <p>
 * 只有「按用户取最近 N 条」「按用户数未读」「按 id 标已读」三类操作，{@link BaseMapper} 的 Wrapper 足够，
 * 无需显式 SQL（故无 XML）。
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}
