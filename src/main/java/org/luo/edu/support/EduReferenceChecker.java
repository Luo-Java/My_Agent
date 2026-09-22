package org.luo.edu.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.edu.entity.Clazz;
import org.luo.edu.entity.Course;
import org.luo.edu.entity.CourseArrangement;
import org.luo.edu.entity.Exam;
import org.luo.edu.entity.Score;
import org.luo.edu.entity.Student;
import org.luo.edu.entity.Teacher;
import org.luo.edu.mapper.ClazzMapper;
import org.luo.edu.mapper.CourseArrangementMapper;
import org.luo.edu.mapper.CourseMapper;
import org.luo.edu.mapper.ExamMapper;
import org.luo.edu.mapper.ScoreMapper;
import org.luo.edu.mapper.StudentMapper;
import org.luo.edu.mapper.TeacherMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 教务表删除前的「被引用」校验：外键引用关系集中登记在本类，各 Service 删除前调一次。
 * <p>
 * business.sql 里只有普通索引、没有物理外键，删掉仍被引用的行不会报错，只会留下悬空 id
 * （例如删掉正在带班的老师，class.head_teacher_id / course.teacher_id 就指向了不存在的老师）。
 * <p>
 * 新增一张表的校验只需两步：在本构造器里 <b>加一条 rule(...)</b>，并在对应 Service 的 deleteXxx 里调一次
 * {@link #assertDeletable}。规则只有这一处，别把判断散到各个 Controller 里。
 */
@Component
public class EduReferenceChecker {

    /** 被引用表的可读名（用于提示语）。 */
    private static final Map<String, String> TABLE_LABELS = Map.of(
            "subject", "科目", "teacher", "教师", "class", "班级", "student", "学生",
            "semester", "学期", "course", "课程", "period", "节次", "exam", "考试");

    /** 一条引用规则：引用方的可读说明 + 按主键统计引用条数。 */
    private record RefRule(String label, Function<Integer, Long> counter) {
    }

    /** 被引用表名 → 引用它的规则列表。 */
    private final Map<String, List<RefRule>> rules = new LinkedHashMap<>();

    public EduReferenceChecker(TeacherMapper teacherMapper, ClazzMapper clazzMapper, CourseMapper courseMapper,
                               StudentMapper studentMapper, ExamMapper examMapper, ScoreMapper scoreMapper,
                               CourseArrangementMapper arrangementMapper) {
        // 教师 ← 班级（班主任）、课程（授课老师）
        rule("teacher", "班级-班主任", id -> clazzMapper.selectCount(
                Wrappers.<Clazz>lambdaQuery().eq(Clazz::getHeadTeacherId, id)));
        rule("teacher", "课程-授课老师", id -> courseMapper.selectCount(
                Wrappers.<Course>lambdaQuery().eq(Course::getTeacherId, id)));
        // 班级 ← 学生、课程、考试
        rule("class", "学生", id -> studentMapper.selectCount(
                Wrappers.<Student>lambdaQuery().eq(Student::getClassId, id)));
        rule("class", "课程", id -> courseMapper.selectCount(
                Wrappers.<Course>lambdaQuery().eq(Course::getClassId, id)));
        rule("class", "考试", id -> examMapper.selectCount(
                Wrappers.<Exam>lambdaQuery().eq(Exam::getClassId, id)));
        // 科目 ← 教师（主教科目）、课程、考试
        rule("subject", "教师-主教科目", id -> teacherMapper.selectCount(
                Wrappers.<Teacher>lambdaQuery().eq(Teacher::getSubjectId, id)));
        rule("subject", "课程", id -> courseMapper.selectCount(
                Wrappers.<Course>lambdaQuery().eq(Course::getSubjectId, id)));
        rule("subject", "考试", id -> examMapper.selectCount(
                Wrappers.<Exam>lambdaQuery().eq(Exam::getSubjectId, id)));
        // 学生 ← 成绩
        rule("student", "成绩", id -> scoreMapper.selectCount(
                Wrappers.<Score>lambdaQuery().eq(Score::getStudentId, id)));
        // 学期 ← 课程、考试
        rule("semester", "课程", id -> courseMapper.selectCount(
                Wrappers.<Course>lambdaQuery().eq(Course::getSemesterId, id)));
        rule("semester", "考试", id -> examMapper.selectCount(
                Wrappers.<Exam>lambdaQuery().eq(Exam::getSemesterId, id)));
        // 课程 ← 排课
        rule("course", "排课", id -> arrangementMapper.selectCount(
                Wrappers.<CourseArrangement>lambdaQuery().eq(CourseArrangement::getCourseId, id)));
        // 节次 ← 排课（开始 / 结束节次各算一条）
        rule("period", "排课-开始节次", id -> arrangementMapper.selectCount(
                Wrappers.<CourseArrangement>lambdaQuery().eq(CourseArrangement::getStartPeriodId, id)));
        rule("period", "排课-结束节次", id -> arrangementMapper.selectCount(
                Wrappers.<CourseArrangement>lambdaQuery().eq(CourseArrangement::getEndPeriodId, id)));
        // 考试 ← 成绩
        rule("exam", "成绩", id -> scoreMapper.selectCount(
                Wrappers.<Score>lambdaQuery().eq(Score::getExamId, id)));
    }

    /**
     * 校验目标行能否删除：无引用正常返回；有引用抛 {@link AiErrorCode#CONFLICT}（409）+ 逐条引用明细，
     * 提示语直接透给前端弹窗，不吞成「服务器错误」。
     */
    public void assertDeletable(String table, Integer id) {
        List<RefRule> refs = rules.get(table);
        if (refs == null || id == null) {
            return;
        }
        List<String> hits = new ArrayList<>();
        for (RefRule r : refs) {
            long count = r.counter().apply(id);
            if (count > 0) {
                hits.add(r.label() + " " + count + " 条");
            }
        }
        if (!hits.isEmpty()) {
            throw new AiBusinessException(AiErrorCode.CONFLICT,
                    TABLE_LABELS.getOrDefault(table, table) + "已被引用，无法删除："
                            + String.join("、", hits) + "。请先解除关联。");
        }
    }

    private void rule(String table, String label, Function<Integer, Long> counter) {
        rules.computeIfAbsent(table, k -> new ArrayList<>()).add(new RefRule(label, counter));
    }
}
