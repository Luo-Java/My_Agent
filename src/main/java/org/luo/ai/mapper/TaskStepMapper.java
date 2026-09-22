package org.luo.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.luo.ai.entity.TaskStep;

/**
 * 规划任务步骤 Mapper。
 */
@Mapper
public interface TaskStepMapper extends BaseMapper<TaskStep> {
}
