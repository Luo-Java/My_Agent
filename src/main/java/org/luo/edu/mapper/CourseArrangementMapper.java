package org.luo.edu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.luo.edu.dto.CourseArrangementDTO;
import org.luo.edu.dto.TimetableDTO;
import org.luo.edu.entity.CourseArrangement;
import org.luo.edu.vo.CourseArrangementVO;
import org.luo.edu.vo.OptionVO;
import org.luo.edu.vo.TimetableVO;
import java.util.List;

/** 排课 Mapper：基础 CRUD 走 BaseMapper；分页、下拉与只读关联查询的 SQL 都在 mapper/edu/CourseArrangementMapper.xml。 */
@Mapper
public interface CourseArrangementMapper extends BaseMapper<CourseArrangement> {

    /** 排课分页（join 出可读名，SQL 见 XML）。筛选条件由 dto 携带。 */
    List<CourseArrangementVO> selectCourseArrangementPage(Page<CourseArrangementVO> page, @Param("dto") CourseArrangementDTO dto);

    /**
     * 下拉选项：id + 可读文案（SQL 里 concat 拼好）。
     * <p>
     * 首参固定为分页对象：XML 里没有 LIMIT，由 {@code PaginationInnerInterceptor} 注入，
     * 调用方用 {@code new Page<>(1, 上限, false)} 把「无界查询」变成有界（false = 不额外跑 count）。
     */
    List<OptionVO> selectOptions(Page<OptionVO> page);

    /** 班级课表分页（跨表 join 出可读名称与节次区间）。筛选条件由 dto 携带。 */
    List<TimetableVO> selectTimetable(Page<TimetableVO> page, @Param("dto") TimetableDTO dto);
}
