package org.luo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.luo.entity.Task;

/**
 * 规划任务 Mapper。
 */
@Mapper
public interface TaskMapper extends BaseMapper<Task> {
}
