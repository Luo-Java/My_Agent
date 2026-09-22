package org.luo.edu.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 成绩统计行：按考试聚合后的扁平投影，列名与 XML 里的 AS 别名一一对应。 */
@Data
public class ScoreStatsVO {

    /** 班级名（join class） */
    private String className;

    /** 科目名（join subject） */
    private String subjectName;

    /** 学期名称（join semester） */
    private String semesterName;

    /** 考试日期（join exam） */
    private LocalDate examDate;

    /** 参考人数（COUNT(*)） */
    private Long studentCount;

    /** 平均分（ROUND(AVG(score), 2)） */
    private BigDecimal avgScore;

    /** 最高分 */
    private BigDecimal maxScore;

    /** 最低分 */
    private BigDecimal minScore;
}
