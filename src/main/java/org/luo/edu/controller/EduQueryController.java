package org.luo.edu.controller;

import jakarta.annotation.Resource;
import org.luo.common.result.PageResult;
import org.luo.common.result.RestResult;
import org.luo.edu.dto.ScheduleDTO;
import org.luo.edu.dto.ScoreDetailDTO;
import org.luo.edu.dto.ScoreStatsDTO;
import org.luo.edu.dto.TimetableDTO;
import org.luo.edu.service.EduQueryService;
import org.luo.edu.vo.ScheduleVO;
import org.luo.edu.vo.ScoreDetailVO;
import org.luo.edu.vo.ScoreStatsVO;
import org.luo.edu.vo.TimetableVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教务关联查询接口：只读、跨表 join（SQL 见各 Mapper XML），返回已拼好的可读列。
 * 四个查询都分页，页码/每页条数的兜底与上限同单表页（继承 BaseBO），条件不传即不过滤。
 * <ul>
 *   <li>{@code GET /score-detail} —— 学生成绩明细（join 学生/班级/科目/学期/考试）：classId、subjectId、semesterId、keyword（模糊匹配学生姓名或学号）</li>
 *   <li>{@code GET /score-stats} —— 成绩统计（按考试聚合人数/均分/最高/最低）：classId、subjectId、semesterId</li>
 *   <li>{@code GET /timetable} —— 班级课表（join 课程/科目/老师/节次）：classId、teacherId、dayOfWeek</li>
 *   <li>{@code GET /schedule} —— 考试日程（join 班级/科目/学期）：classId、subjectId、semesterId</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/edu")
public class EduQueryController {

    @Resource
    private EduQueryService service;

    @GetMapping("/score-detail")
    public RestResult<PageResult<ScoreDetailVO>> scoreDetail(@ModelAttribute ScoreDetailDTO dto) {
        return RestResult.ok(service.scoreDetail(dto));
    }

    @GetMapping("/score-stats")
    public RestResult<PageResult<ScoreStatsVO>> scoreStats(@ModelAttribute ScoreStatsDTO dto) {
        return RestResult.ok(service.scoreStats(dto));
    }

    @GetMapping("/timetable")
    public RestResult<PageResult<TimetableVO>> timetable(@ModelAttribute TimetableDTO dto) {
        return RestResult.ok(service.timetable(dto));
    }

    @GetMapping("/schedule")
    public RestResult<PageResult<ScheduleVO>> schedule(@ModelAttribute ScheduleDTO dto) {
        return RestResult.ok(service.schedule(dto));
    }

}
