package org.luo.edu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.luo.edu.dto.TeacherDTO;
import org.luo.edu.entity.Teacher;
import org.luo.edu.vo.TeacherVO;
import java.util.List;

/** 老师 Mapper：基础 CRUD 走 BaseMapper，跨表分页与下拉选项见 mapper/edu/TeacherMapper.xml。 */
@Mapper
public interface TeacherMapper extends BaseMapper<Teacher> {

    /** 老师分页（join 出可读名，SQL 见 XML）。筛选条件由 dto 携带。 */
    List<TeacherVO> selectTeacherPage(Page<TeacherVO> page, @Param("dto") TeacherDTO dto);
}
