package org.luo.edu.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/** 学生成绩明细行：跨表 join 后的扁平投影，列名与 XML 里的 AS 别名一一对应。 */
@Data
public class ScoreDetailVO {

    /** 成绩ID（score.id） */
    private Long id;

    /** 学生姓名（join student） */
    private String studentName;

    /** 学号（join student） */
    private String studentNo;

    /** 班级名（join class） */
    private String className;

    /** 科目名（join subject） */
    private String subjectName;

    /** 学期名称（join semester） */
    private String semesterName;

    /** 考试日期（join exam） */
    private LocalDate examDate;

    /** 考试时间（join exam） */
    private LocalTime examTime;

    /** 分数 */
    private BigDecimal score;
}
