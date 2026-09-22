package org.luo.edu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.luo.edu.dto.CourseDTO;
import org.luo.edu.entity.Course;
import org.luo.edu.vo.CourseVO;
import org.luo.edu.vo.OptionVO;
import java.util.List;

/** 课程 Mapper：基础 CRUD 走 BaseMapper，跨表分页与下拉选项见 mapper/edu/CourseMapper.xml。 */
@Mapper
public interface CourseMapper extends BaseMapper<Course> {

    /** 课程分页（join 出可读名，SQL 见 XML）。筛选条件由 dto 携带。 */
    List<CourseVO> selectCoursePage(Page<CourseVO> page, @Param("dto") CourseDTO dto);

    /** 下拉选项：id + 可读文案（SQL 里 concat 拼好）。 */
    List<OptionVO> selectOptions();
}
