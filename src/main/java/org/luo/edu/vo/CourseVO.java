package org.luo.edu.vo;

import lombok.Data;
import org.luo.edu.entity.Course;

/** 课程列表项：单表字段 + 跨表 join 出的可读名，前端不再做 id → 名称翻译。 */
@Data
public class CourseVO extends Course {

    /** 班级名（join class） */
    private String className;

    /** 科目名（join subject） */
    private String subjectName;

    /** 老师名（join teacher） */
    private String teacherName;

    /** 学期名（join semester） */
    private String semesterName;
}
