package org.luo.edu.service;

import jakarta.annotation.Resource;
import org.luo.edu.entity.Clazz;
import org.luo.edu.entity.Student;
import org.luo.edu.entity.Subject;
import org.luo.edu.entity.Teacher;
import org.luo.edu.mapper.ClazzMapper;
import org.luo.edu.mapper.CourseMapper;
import org.luo.edu.mapper.ScoreMapper;
import org.luo.edu.mapper.StudentMapper;
import org.luo.edu.mapper.SubjectMapper;
import org.luo.edu.mapper.TeacherMapper;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 教务看板：跨表聚合统计。
 * <p>
 * 单表 CRUD 由各表独立 Service 承载，下拉选项由各表自己的 {@code GET /list} 提供，
 * 本服务只保留无法归到某一张表的看板统计。
 */
@Service
public class EduMetaService {

    @Resource
    private SubjectMapper subjectMapper;

    @Resource
    private TeacherMapper teacherMapper;

    @Resource
    private ClazzMapper clazzMapper;

    @Resource
    private StudentMapper studentMapper;

    @Resource
    private CourseMapper courseMapper;

    @Resource
    private ScoreMapper scoreMapper;

    /** 看板统计：总量 + 各年级学生分布 + 各科老师分布。 */
    public Map<String, Object> dashboard() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("studentCount", studentMapper.selectCount(null));
        d.put("teacherCount", teacherMapper.selectCount(null));
        d.put("classCount", clazzMapper.selectCount(null));
        d.put("courseCount", courseMapper.selectCount(null));
        d.put("scoreCount", scoreMapper.selectCount(null));

        List<Clazz> classes = clazzMapper.selectList(null);
        List<Student> students = studentMapper.selectList(null);
        List<Teacher> teachers = teacherMapper.selectList(null);

        // 各年级学生数：classId → grade → 聚合
        Map<Integer, Integer> classGrade = new HashMap<>();
        for (Clazz c : classes) classGrade.put(c.getId(), c.getGrade());
        Map<String, Long> gradeDist = new LinkedHashMap<>();
        for (Student s : students) {
            Integer g = classGrade.get(s.getClassId());
            if (g == null) continue;
            gradeDist.merge(g + "年级", 1L, Long::sum);
        }
        d.put("gradeDist", gradeDist);

        // 各科老师数：subjectId → 科目名
        Map<Integer, String> subjName = nameMap(subjectMapper.selectList(null),
                Subject::getId, Subject::getName);
        Map<String, Long> subjectTeacher = teachers.stream()
                .collect(Collectors.groupingBy(
                        t -> subjName.getOrDefault(t.getSubjectId(), "未知"),
                        LinkedHashMap::new, Collectors.counting()));
        d.put("subjectTeacher", subjectTeacher);
        return d;
    }

    private <T> Map<Integer, String> nameMap(List<T> rows, Function<T, Integer> idFn, Function<T, String> nameFn) {
        Map<Integer, String> m = new HashMap<>();
        for (T r : rows) m.put(idFn.apply(r), nameFn.apply(r));
        return m;
    }
}
