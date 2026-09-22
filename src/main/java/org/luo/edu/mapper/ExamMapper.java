package org.luo.edu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.luo.edu.dto.ExamDTO;
import org.luo.edu.dto.ScheduleDTO;
import org.luo.edu.entity.Exam;
import org.luo.edu.vo.ExamVO;
import org.luo.edu.vo.OptionVO;
import org.luo.edu.vo.ScheduleVO;
import java.util.List;

/** 考试 Mapper：基础 CRUD 走 BaseMapper；分页、下拉与只读关联查询的 SQL 都在 mapper/edu/ExamMapper.xml。 */
@Mapper
public interface ExamMapper extends BaseMapper<Exam> {

    /** 考试分页（join 出可读名，SQL 见 XML）。筛选条件由 dto 携带。 */
    List<ExamVO> selectExamPage(Page<ExamVO> page, @Param("dto") ExamDTO dto);

    /** 下拉选项：id + 可读文案（SQL 里 concat 拼好）。 */
    List<OptionVO> selectOptions();

    /** 考试日程分页（跨表 join 出可读名称）。筛选条件由 dto 携带。 */
    List<ScheduleVO> selectSchedule(Page<ScheduleVO> page, @Param("dto") ScheduleDTO dto);
}
