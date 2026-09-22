package org.luo.edu.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.luo.common.result.PageResult;
import org.luo.edu.dto.ScheduleDTO;
import org.luo.edu.dto.ScoreDetailDTO;
import org.luo.edu.dto.ScoreStatsDTO;
import org.luo.edu.dto.TimetableDTO;
import org.luo.edu.mapper.CourseArrangementMapper;
import org.luo.edu.mapper.ExamMapper;
import org.luo.edu.mapper.ScoreMapper;
import org.luo.edu.vo.ScheduleVO;
import org.luo.edu.vo.ScoreDetailVO;
import org.luo.edu.vo.ScoreStatsVO;
import org.luo.edu.vo.TimetableVO;
import org.springframework.stereotype.Service;

/**
 * 教务关联查询：跨表 join（SQL 见各 Mapper XML），返回可读列。
 * <p>
 * 四个查询都是只读分页，分页对象的兜底与限幅统一由 BaseBO 提供，Controller 只做转发。
 */
@Service
public class EduQueryService {

    @Resource
    private ScoreMapper scoreMapper;

    @Resource
    private CourseArrangementMapper courseArrangementMapper;

    @Resource
    private ExamMapper examMapper;

    /** 学生成绩明细（join 学生/班级/科目/学期/考试），筛选条件由 dto 携带。 */
    public PageResult<ScoreDetailVO> scoreDetail(ScoreDetailDTO dto) {
        Page<ScoreDetailVO> p = dto.toPage();
        return PageResult.of(p, scoreMapper.selectScoreDetail(p, dto));
    }

    /** 成绩统计（按考试聚合人数/均分/最高/最低），筛选条件由 dto 携带。 */
    public PageResult<ScoreStatsVO> scoreStats(ScoreStatsDTO dto) {
        Page<ScoreStatsVO> p = dto.toPage();
        return PageResult.of(p, scoreMapper.selectStats(p, dto));
    }

    /** 班级课表（join 课程/科目/老师/节次），筛选条件由 dto 携带。 */
    public PageResult<TimetableVO> timetable(TimetableDTO dto) {
        Page<TimetableVO> p = dto.toPage();
        return PageResult.of(p, courseArrangementMapper.selectTimetable(p, dto));
    }

    /** 考试日程（join 班级/科目/学期），筛选条件由 dto 携带。 */
    public PageResult<ScheduleVO> schedule(ScheduleDTO dto) {
        Page<ScheduleVO> p = dto.toPage();
        return PageResult.of(p, examMapper.selectSchedule(p, dto));
    }
}
