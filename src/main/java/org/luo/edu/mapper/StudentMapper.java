package org.luo.edu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.luo.edu.dto.StudentDTO;
import org.luo.edu.entity.Student;
import org.luo.edu.vo.StudentVO;
import java.util.List;

/** 学生 Mapper：基础 CRUD 走 BaseMapper，跨表分页与下拉选项见 mapper/edu/StudentMapper.xml。 */
@Mapper
public interface StudentMapper extends BaseMapper<Student> {

    /** 学生分页（join 出可读名，SQL 见 XML）。筛选条件由 dto 携带。 */
    List<StudentVO> selectStudentPage(Page<StudentVO> page, @Param("dto") StudentDTO dto);
}
