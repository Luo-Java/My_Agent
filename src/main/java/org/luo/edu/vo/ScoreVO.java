package org.luo.edu.vo;

import lombok.Data;
import org.luo.edu.entity.Score;

import java.time.LocalDate;
import java.time.LocalTime;

/** 成绩列表项：单表字段 + 跨表 join 出的可读名，前端不再做 id → 名称翻译。 */
@Data
public class ScoreVO extends Score {

    /** 学生姓名（join student） */
    private String studentName;

    /** 学号（join student） */
    private String studentNo;

    /** 班级名（join class） */
    private String className;

    /** 科目名（join subject） */
    private String subjectName;

    /** 考试日期（join exam） */
    private LocalDate examDate;

    /** 考试时间（join exam） */
    private LocalTime examTime;
}
