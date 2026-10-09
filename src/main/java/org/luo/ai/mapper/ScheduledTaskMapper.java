package org.luo.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.luo.ai.entity.ScheduledTask;

/**
 * 定时任务 Mapper。
 * <p>
 * 只有定义行的增删改查与「按 next_run_at 取到点的任务」，{@link BaseMapper} 的 Wrapper 足够，无需显式 SQL
 * （故无 XML，与 {@code ConversationFactMapper} 同约定）。
 */
@Mapper
public interface ScheduledTaskMapper extends BaseMapper<ScheduledTask> {
}
