package org.luo.edu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.luo.edu.entity.Subject;

/** 科目 Mapper：本表无外键，分页/下拉都走 MyBatis-Plus 条件构造器，不需要 XML。 */
@Mapper
public interface SubjectMapper extends BaseMapper<Subject> {
}
