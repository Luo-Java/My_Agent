package org.luo.edu.vo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/** 考试日程行：跨表 join 后的扁平投影，列名与 XML 里的 AS 别名一一对应。 */
@Data
public class ScheduleVO {

    /** 考试ID（exam.id） */
    private Integer id;

    /** 班级名（join class） */
    private String className;

    /** 科目名（join subject） */
    private String subjectName;

    /** 学期名称（join semester） */
    private String semesterName;

    /** 考试日期 */
    private LocalDate examDate;

    /** 考试时间 */
    private LocalTime examTime;
}
